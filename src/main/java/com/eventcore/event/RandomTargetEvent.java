package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.LocatorRule;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RandomTarget: every online player is given a random other player as target (two players can hunt each other).
 *  - Action bar: "Target [head] name : 200m", refreshed every update-ticks on the hunter's own scheduler (Folia-safe).
 *    The head uses SkinsRestorer and the premium UUID (see SkinHeads).
 *  - Locator bar: the locator bar gamerule is switched on for the event, and each hunter's waypoint RECEIVE range is
 *    shrunk to the distance of the target, so the bar only shows the target. The vanilla waypoint system has no
 *    per-viewer filter, so anyone standing closer to the hunter than the target still shows as well.
 *  - A target that has been offline for reassign-after-seconds (120) is replaced by another random online player.
 *  - Sidebar scoreboard with the top 3 killers (#1 gold, #2 gray, #3 bronze, small caps title, lines centred).
 *  - When the event ends a title announces the top killer.
 * Receive ranges are restored on strip (event end, quit) and by the PDC marker sweep after a crash.
 */
public final class RandomTargetEvent extends GameEvent {
    private static final String OBJECTIVE = "ec_randomtarget";
    private static final double MAX_RANGE = 60_000_000.0;

    private record Snap(UUID world, String worldName, double x, double y, double z) { }

    private final Map<UUID, UUID> targets = new ConcurrentHashMap<>();        // hunter -> target
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private final Map<UUID, Long> killSeq = new ConcurrentHashMap<>();        // when a player reached their current kill count (tie-break: earlier wins)
    private final Map<UUID, Long> offlineSince = new ConcurrentHashMap<>();
    private final Map<UUID, Snap> snaps = new ConcurrentHashMap<>();          // last known position, written on each player's own thread
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<Scoreboard, List<String>> shown = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Boolean> savedRule = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Random rnd = new Random();

    public RandomTargetEvent(EventCorePlugin pl) { super(pl, "random_target"); }

    @Override public int minMinutes() { return 1; }

    // ---------- config ----------
    private boolean worldAllowed(World w) {
        List<String> l = settings().getStringList("worlds");
        return l.isEmpty() || l.contains(w.getName());
    }

    private String tpl(String key, String def) {
        String v = pl.cfg.messages().getString("random-target." + key);
        return v == null ? def : v;
    }

    private static Attribute attr(String key) {
        try { return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key)); } catch (Throwable t) { return null; }
    }

    // ---------- lifecycle ----------
    @Override public void onStart(ActiveEvent ae) {
        targets.clear(); kills.clear(); killSeq.clear(); offlineSince.clear(); snaps.clear(); names.clear(); seq.set(0);
        savedRule.clear();
        for (World w : Bukkit.getWorlds()) {
            if (!worldAllowed(w)) continue;
            Boolean old = LocatorRule.get(w);
            if (old == null) { pl.getLogger().warning("RandomTarget: this server has no locator bar gamerule (needs 1.21.6+); world " + w.getName()); continue; }
            savedRule.put(w.getName(), old);
            if (!LocatorRule.set(w, true)) pl.getLogger().warning("RandomTarget: could not enable the locator bar gamerule in " + w.getName());
        }
        for (Player p : Bukkit.getOnlinePlayers()) names.put(p.getUniqueId(), p.getName());
        assign();
        refreshBoards();
    }

    @Override public void onStop(ActiveEvent ae) {
        List<Map.Entry<UUID, Integer>> top = ranking(1);
        for (Map.Entry<String, Boolean> e : savedRule.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w != null) LocatorRule.set(w, e.getValue());
        }
        savedRule.clear();
        clearBoards();
        if (pl.isEnabled()) {   // false while the plugin shuts down: no titles then
            if (top.isEmpty()) {
                pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("random-target.no-winner-title"), Text.ph());
            } else {
                UUID w = top.get(0).getKey();
                pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("random-target.winner-title"),
                        Text.ph("player", names.getOrDefault(w, "?"), "kills", top.get(0).getValue()));
            }
        }
        targets.clear(); kills.clear(); killSeq.clear(); offlineSince.clear(); snaps.clear();
    }

    @Override public void onSecond(ActiveEvent ae, int secondsLeft) {
        assign();
        refreshBoards();
    }

    // ---------- per player (runs on the player's own thread) ----------
    @Override protected void doApply(Player p) {
        UUID id = p.getUniqueId();
        names.put(id, p.getName());
        offlineSince.remove(id);
        pl.skins.prefetch(p);
        Sched.cancel(tasks.remove(id));
        long period = Math.max(2, settings().getInt("update-ticks", 10));
        ScheduledTask t = pl.sched.entityTimer(p, task -> tick(p), () -> { }, 1, period);
        if (t != null) tasks.put(id, t);
    }

    @Override protected void doStrip(Player p) {
        Sched.cancel(tasks.remove(p.getUniqueId()));
        setReceiveRange(p, -1);
        if (p.isOnline()) p.sendActionBar(Component.empty());
    }

    private void tick(Player p) {
        if (!active() || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        Location l = p.getLocation();
        Snap me = new Snap(l.getWorld().getUID(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
        snaps.put(id, me);

        UUID tid = targets.get(id);
        Snap ts = tid == null ? null : snaps.get(tid);
        boolean offline = tid != null && offlineSince.containsKey(tid);
        boolean sameWorld = ts != null && ts.world().equals(me.world());
        double dist = sameWorld ? Math.sqrt(sq(ts.x() - me.x()) + sq(ts.y() - me.y()) + sq(ts.z() - me.z())) : -1;

        Component bar;
        if (tid == null) {
            bar = pl.text.render(tpl("no-target", "<g>target</g> <gray>waiting for another player</gray>"), Text.ph());
        } else {
            String name = names.getOrDefault(tid, "?");
            String shownDist;
            if (offline) shownDist = tpl("offline", "offline");
            else if (ts == null) shownDist = "...";
            else if (!sameWorld) shownDist = tpl("other-world", "other dimension");
            else shownDist = Math.round(dist) + "m";
            bar = pl.text.render(tpl("actionbar", "<g>target</g> {targethead} {player} <g>:</g> {distance}"),
                    Text.ph("targethead", head(tid, name), "player", name, "distance", shownDist));
        }
        p.sendActionBar(bar);

        if (settings().getBoolean("locator-filter", true)) {
            double range = (tid == null || offline || !sameWorld) ? 0 : dist + Math.max(0, settings().getDouble("locator-margin", 3.0));
            setReceiveRange(p, range);
        }
    }

    private static double sq(double d) { return d * d; }

    private Component head(UUID id, String name) {
        if (!settings().getBoolean("head", true)) return Component.empty();
        Component c = pl.skins.head(id, name);
        return c != null ? c : Component.text(settings().getString("head-fallback", "⌖"));
    }

    /** Waypoint receive range of this player. Negative = back to the attribute default (60,000,000). */
    private void setReceiveRange(Player p, double range) {
        Attribute a = attr("waypoint_receive_range");
        if (a == null) return;
        try {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) return;
            double v = range < 0 ? inst.getDefaultValue() : Math.max(0, Math.min(MAX_RANGE, range));
            if (Math.abs(inst.getBaseValue() - v) > 0.5 || (v == 0 && inst.getBaseValue() != 0)) inst.setBaseValue(v);
        } catch (Throwable ignored) { }
    }

    // ---------- target assignment (global thread, once a second) ----------
    private void assign() {
        List<UUID> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) { online.add(p.getUniqueId()); names.put(p.getUniqueId(), p.getName()); }
        long graceMs = Math.max(5, settings().getInt("reassign-after-seconds", 120)) * 1000L;
        long now = System.currentTimeMillis();
        for (UUID hunter : online) {
            UUID t = targets.get(hunter);
            boolean replace = t == null || t.equals(hunter);
            if (!replace) {
                Long off = offlineSince.get(t);
                if (off != null && now - off >= graceMs) replace = true;   // target has been gone too long
            }
            if (!replace) continue;
            List<UUID> pool = new ArrayList<>(online);
            pool.remove(hunter);
            if (pool.isEmpty()) targets.remove(hunter);
            else targets.put(hunter, pool.get(rnd.nextInt(pool.size())));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!active()) return;
        offlineSince.remove(e.getPlayer().getUniqueId());
        names.put(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!active()) return;
        UUID id = e.getPlayer().getUniqueId();
        offlineSince.put(id, System.currentTimeMillis());
        snaps.remove(id);
    }

    // ---------- kills ----------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!active()) return;
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        UUID k = killer.getUniqueId();
        names.put(k, killer.getName());
        kills.merge(k, 1, Integer::sum);
        killSeq.put(k, seq.incrementAndGet());
        pl.sched.global(this::refreshBoards);
    }

    /** Players with at least one kill, best first. A tie goes to whoever reached the count first. */
    private List<Map.Entry<UUID, Integer>> ranking(int limit) {
        List<Map.Entry<UUID, Integer>> l = new ArrayList<>(kills.entrySet());
        l.removeIf(e -> e.getValue() <= 0);
        l.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : Long.compare(killSeq.getOrDefault(a.getKey(), 0L), killSeq.getOrDefault(b.getKey(), 0L));
        });
        return l.size() > limit ? new ArrayList<>(l.subList(0, limit)) : l;
    }

    // ---------- sidebar ----------
    private List<Scoreboard> boards() {
        List<Scoreboard> out = new ArrayList<>();
        try { out.add(Bukkit.getScoreboardManager().getMainScoreboard()); } catch (Throwable ignored) { }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Scoreboard s = p.getScoreboard();
                boolean known = false;
                for (Scoreboard o : out) if (o == s) { known = true; break; }
                if (s != null && !known) out.add(s);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    private Component boardTitle() {
        ConfigurationSection sb = settings().getConfigurationSection("scoreboard");
        String t = sb == null ? "top kills" : sb.getString("title", "top kills");
        return pl.text.paletteGradient(Text.smallCaps(t), 0);
    }

    private TextColor rankColor(int rank) {
        ConfigurationSection c = settings().getConfigurationSection("scoreboard.colors");
        String hex = c == null ? null : c.getString(rank == 1 ? "first" : rank == 2 ? "second" : "third");
        String def = rank == 1 ? "#FFD700" : rank == 2 ? "#C0C0C0" : "#CD7F32";
        TextColor tc = TextColor.fromHexString(hex == null ? def : hex);
        return tc != null ? tc : NamedTextColor.WHITE;
    }

    /** Approximate width of the default font in pixels (good enough to centre lines under the title). */
    private static int px(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            w += switch (c) {
                case ' ' -> 4;
                case 'i', '!', '.', ',', ':', ';', '\'', '|', 'ɪ' -> 2 + 1;
                case 'l', '[', ']', '(', ')', '"', '*', 't' -> 4;
                default -> 6;
            };
        }
        return w;
    }

    private List<String> buildEntries(Component title) {
        List<Map.Entry<UUID, Integer>> top = ranking(3);
        List<Component> rows = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            UUID id = top.get(i).getKey();
            rows.add(Component.text("#" + (i + 1) + " " + names.getOrDefault(id, "?") + " : " + top.get(i).getValue(), rankColor(i + 1)));
        }
        if (rows.isEmpty()) rows.add(Component.text(Text.smallCaps("no kills yet"), NamedTextColor.GRAY));

        int max = px(Text.plain(title));
        for (Component r : rows) max = Math.max(max, px(Text.plain(r)));
        List<String> out = new ArrayList<>();
        LegacyComponentSerializer ser = LegacyComponentSerializer.legacySection();
        for (Component r : rows) {
            int pad = (int) Math.round((max - px(Text.plain(r))) / 2.0 / 4.0);
            out.add(ser.serialize(Component.text(" ".repeat(Math.max(0, pad))).append(r)));
        }
        return out;
    }

    private void refreshBoards() {
        if (!settings().getBoolean("scoreboard.enabled", true)) return;
        Component title = boardTitle();
        List<String> entries = buildEntries(title);
        for (Scoreboard sb : boards()) {
            try {
                Objective o = sb.getObjective(OBJECTIVE);
                List<String> old = shown.get(sb);
                boolean fresh = false;
                if (o == null) {
                    o = sb.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title);
                    hideNumbers(o);
                    fresh = true;
                }
                Objective side = sb.getObjective(DisplaySlot.SIDEBAR);
                boolean ours = side != null && OBJECTIVE.equals(side.getName());
                if (!ours) o.setDisplaySlot(DisplaySlot.SIDEBAR);
                if (!fresh && ours && entries.equals(old)) continue;
                if (old != null) for (String s : old) if (!entries.contains(s)) sb.resetScores(s);
                for (int i = 0; i < entries.size(); i++) o.getScore(entries.get(i)).setScore(entries.size() - i);
                shown.put(sb, new ArrayList<>(entries));
            } catch (Throwable t) {
                pl.getLogger().fine("RandomTarget scoreboard: " + t);
            }
        }
    }

    private void clearBoards() {
        for (Scoreboard sb : boards()) {
            try {
                Objective o = sb.getObjective(OBJECTIVE);
                if (o != null) o.unregister();
            } catch (Throwable ignored) { }
        }
        shown.clear();
    }

    /** The scores only order the three lines, so hide the red numbers (Paper number formats, looked up by reflection). */
    private static void hideNumbers(Objective o) {
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", nf).invoke(o, blank);
        } catch (Throwable ignored) { }
    }
}
