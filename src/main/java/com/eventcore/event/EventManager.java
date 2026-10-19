package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Owns the event registry and every running event: start, stop, force, rig, cooldowns,
 * concurrency limits, bossbars and effect clean-up on join/quit/respawn/disable.
 */
public final class EventManager implements Listener {

    public static final class ActiveEvent {
        public final GameEvent ev;
        public final long start;
        public final long end;
        public final int totalSec;
        public volatile BossBar bar;
        public volatile ScheduledTask aura;
        /** players who already got the join info (or saw the start broadcast): never message them twice */
        public final Set<UUID> informed = ConcurrentHashMap.newKeySet();

        ActiveEvent(GameEvent ev, long start, int minutes) {
            this.ev = ev;
            this.start = start;
            this.totalSec = minutes * 60;
            this.end = start + minutes * 60_000L;
        }
        public int secondsLeft() { return (int) Math.max(0, (end - System.currentTimeMillis() + 999) / 1000); }
    }

    private final EventCorePlugin pl;
    private final Map<String, GameEvent> registry = new LinkedHashMap<>();
    private final Map<String, ActiveEvent> active = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Deque<String[]> rigs = new ConcurrentLinkedDeque<>();
    private ScheduledTask ticker;

    public EventManager(EventCorePlugin pl) { this.pl = pl; }

    public void register(GameEvent e) {
        registry.put(e.id(), e);
        Bukkit.getPluginManager().registerEvents(e, pl);
    }

    public Map<String, GameEvent> registry() { return registry; }
    public GameEvent get(String id) { return registry.get(id); }
    public boolean isActive(String id) { return active.containsKey(id); }
    public ActiveEvent active(String id) { return active.get(id); }
    public Collection<ActiveEvent> actives() { return active.values(); }

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", ""); }

    /** Resolves an id, display name or alias to an event id (or null). */
    public String find(String input) {
        if (input == null) return null;
        String n = norm(input);
        for (GameEvent e : registry.values()) {
            if (norm(e.id()).equals(n) || norm(e.displayName()).equals(n)) return e.id();
            for (String a : e.sec().getStringList("aliases")) if (norm(a).equals(n)) return e.id();
        }
        return null;
    }

    /**
     * Tab completion: exactly ONE name per event, the display name as a single word ("golden_tree", "duodeck").
     * Aliases and ids still work when typed (see find) but are never suggested.
     */
    public List<String> allNames() {
        List<String> out = new ArrayList<>();
        for (GameEvent e : registry.values()) out.add(e.displayName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_"));
        return out;
    }

    public void startTicker() {
        ticker = pl.sched.globalTimer(t -> tickSecond(), 20, 20);
    }

    public Map<String, Object> placeholders(ActiveEvent ae) {
        ConfigurationSection s = ae.ev.sec();
        Map<String, Object> m = Text.ph(
                "event", pl.text.sc(ae.ev.displayName()),
                "event_name", Util.title(ae.ev.displayName()),
                "description", pl.text.sc(s.getString("description", "")),
                "minutes", ae.totalSec / 60,
                "time", Util.mmss(ae.secondsLeft()),
                "color_raw", s.getString("color", "#ffffff"),
                "id", ae.ev.id());
        m.putAll(ae.ev.extra(ae));
        return m;
    }

    /** Returns null on success, otherwise a key under errors.* in messages.yml. */
    public String start(String id, Integer minutesOverride, boolean force) {
        GameEvent ev = registry.get(id);
        if (ev == null) return "unknown-event";
        ConfigurationSection s = ev.sec();
        if (active.containsKey(id)) return "already-running";
        if (!force) {
            if (!s.getBoolean("enabled", true)) return "disabled";
            Long cd = cooldowns.get(id);
            if (cd != null && cd > System.currentTimeMillis()) return "cooldown";
            int max = pl.cfg.config().getInt("max-concurrent-events", 2);
            if (max > 0 && active.size() >= max) return "max-concurrent";
            for (Object o : pl.cfg.config().getList("incompatible-events", List.of())) {
                if (!(o instanceof List<?> group)) continue;
                Set<String> ids = new HashSet<>();
                for (Object g : group) { String f = find(String.valueOf(g)); if (f != null) ids.add(f); }
                if (!ids.contains(id)) continue;
                for (String other : ids) if (!other.equals(id) && active.containsKey(other)) return "incompatible";
            }
        }
        int minutes = minutesOverride != null ? Math.max(1, minutesOverride) : Math.max(1, s.getInt("duration-minutes", 10));
        minutes = Math.max(minutes, ev.minMinutes());
        launch(ev, minutes);
        return null;
    }

    private void launch(GameEvent ev, int minutes) {
        ConfigurationSection s = ev.sec();
        ActiveEvent ae = new ActiveEvent(ev, System.currentTimeMillis(), minutes);
        active.put(ev.id(), ae);
        for (Player pp : Bukkit.getOnlinePlayers()) ae.informed.add(pp.getUniqueId());   // they see the start broadcast
        Map<String, Object> ph = placeholders(ae);

        ConfigurationSection bb = s.getConfigurationSection("bossbar");
        if (bb != null && bb.getBoolean("enabled", true) && wantsBar(ev)) ae.bar = createBar(ae, bb);
        pl.fx.playAll(s.getConfigurationSection("sounds.start"));
        pl.fx.title(Bukkit.getServer(), s.getConfigurationSection("titles.start"), ph);
        pl.fx.broadcast(s, "messages.start", ph, pl.cfg.messages().getString("prefix", ""));
        pl.discord.eventStarted(Util.title(ev.displayName()), s.getString("description", ""), minutes);

        ConfigurationSection anim = s.getConfigurationSection("start-animation");
        if (anim != null && anim.getBoolean("enabled", true))
            pl.particles.animate(pl.particles.read(anim.getConfigurationSection("layers")),
                    anim.getInt("duration-ticks", 40), Math.max(1, anim.getInt("interval-ticks", 5)));
        ConfigurationSection aura = s.getConfigurationSection("aura");
        if (aura != null && aura.getBoolean("enabled", false))
            ae.aura = pl.particles.aura(pl.particles.read(aura.getConfigurationSection("layers")),
                    Math.max(1, aura.getInt("interval-ticks", 10)), () -> active.get(ev.id()) == ae);

        try { ev.onStart(ae); } catch (Exception ex) { pl.getLogger().warning("onStart " + ev.id() + ": " + ex); }
        for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> ev.apply(p));
        pl.teams.refreshLater();
    }

    // ---------- bossbar / actionbar ----------
    /** BOSSBAR, ACTIONBAR or BOTH: per event display-mode, falling back to config.yml event-display. */
    private String mode(GameEvent ev) {
        String m = ev.sec().getString("display-mode", "DEFAULT").toUpperCase(Locale.ROOT);
        if (m.equals("DEFAULT")) m = pl.cfg.config().getString("event-display", "BOSSBAR").toUpperCase(Locale.ROOT);
        return m;
    }
    private boolean wantsBar(GameEvent ev) { String m = mode(ev); return m.equals("BOSSBAR") || m.equals("BOTH"); }
    private boolean wantsAction(GameEvent ev) { String m = mode(ev); return m.equals("ACTIONBAR") || m.equals("BOTH"); }

    /** "Golden Tree : 01:00" in small caps, coloured with the event's own gradient. */
    private Component barText(ActiveEvent ae) {
        Component custom = ae.ev.displayText(ae);
        if (custom != null) return custom;
        ConfigurationSection bb = ae.ev.sec().getConfigurationSection("bossbar");
        String tpl = bb == null ? "{event} : {time}" : bb.getString("text", "{event} : {time}");
        String plain = pl.text.expandPlain(tpl, placeholders(ae));
        List<String> g = bb == null ? List.of() : bb.getStringList("gradient");
        return g.isEmpty() ? pl.text.bossbar(plain) : pl.text.gradient(plain, g, 0);
    }

    private BossBar createBar(ActiveEvent ae, ConfigurationSection bb) {
        BossBar.Color col = com.eventcore.util.BossBars.enumOf(BossBar.Color.class, bb.getString("color", "YELLOW"), BossBar.Color.YELLOW);
        BossBar.Overlay ov = com.eventcore.util.BossBars.enumOf(BossBar.Overlay.class, bb.getString("style", "PROGRESS"), BossBar.Overlay.PROGRESS);
        BossBar b = BossBar.bossBar(barText(ae), 1f, col, ov);
        pl.bars.show(b);
        return b;
    }

    public boolean stop(String id, boolean announce) { return stopInternal(id, announce, false); }

    private boolean stopInternal(String id, boolean announce, boolean direct) {
        ActiveEvent ae = active.remove(id);
        if (ae == null) return false;
        GameEvent ev = ae.ev;
        ConfigurationSection s = ev.sec();
        Sched.cancel(ae.aura);
        if (ae.bar != null) pl.bars.hide(ae.bar);
        try { ev.onStop(ae); } catch (Exception ex) { pl.getLogger().warning("onStop " + ev.id() + ": " + ex); }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (direct) { try { ev.strip(p); } catch (Throwable ignored) { } }
            else pl.sched.entity(p, () -> ev.strip(p));
        }
        int cd = s.getInt("cooldown-minutes", 0);
        if (cd > 0) cooldowns.put(id, System.currentTimeMillis() + cd * 60_000L);
        if (announce) {
            Map<String, Object> ph = Text.ph("event", pl.text.sc(ev.displayName()), "description", pl.text.sc(s.getString("description", "")),
                    "color_raw", s.getString("color", "#ffffff"), "minutes", ae.totalSec / 60, "time", "00:00");
            pl.fx.playAll(s.getConfigurationSection("sounds.end"));
            pl.fx.title(Bukkit.getServer(), s.getConfigurationSection("titles.end"), ph);
            pl.fx.broadcast(s, "messages.end", ph, pl.cfg.messages().getString("prefix", ""));
        }
        if (!direct) pl.teams.refreshLater();
        return true;
    }

    public int stopAll(boolean announce) {
        int n = 0;
        for (String id : new ArrayList<>(active.keySet())) if (stop(id, announce)) n++;
        return n;
    }

    /** Plugin disable: stop everything synchronously, strip every online player, no announcements. */
    public void shutdown() {
        Sched.cancel(ticker);
        for (String id : new ArrayList<>(active.keySet())) stopInternal(id, false, true);
        for (Player p : Bukkit.getOnlinePlayers()) for (GameEvent ev : registry.values()) {
            try { ev.strip(p); ev.sweep(p); } catch (Throwable ignored) { }
        }
    }

    private int teamTick;

    private void tickSecond() {
        long now = System.currentTimeMillis();
        if ((active.containsKey("anonymous") || active.containsKey("glow")) && ++teamTick % 5 == 0) pl.teams.refresh();
        for (ActiveEvent ae : new ArrayList<>(active.values())) {
            int left = ae.secondsLeft();
            ConfigurationSection bb = ae.ev.sec().getConfigurationSection("bossbar");
            boolean bar = bb != null && bb.getBoolean("enabled", true) && wantsBar(ae.ev);
            if (bar && ae.bar == null) ae.bar = createBar(ae, bb);
            else if (!bar && ae.bar != null) { pl.bars.hide(ae.bar); ae.bar = null; }
            if (ae.bar != null) {
                if (!ae.ev.ownsBarTitle()) {   // events that own their bar (airdrop) update title AND progress themselves
                    ae.bar.name(barText(ae));
                    ae.bar.progress(bb.getBoolean("countdown", true) ? com.eventcore.util.BossBars.clamp(left / (double) Math.max(1, ae.totalSec)) : 1f);
                }
            }
            if (wantsAction(ae.ev)) {
                Component c = barText(ae);
                for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(c);
            }
            try { ae.ev.onSecond(ae, left); } catch (Exception ex) { pl.getLogger().warning("onSecond " + ae.ev.id() + ": " + ex); }
            if (now >= ae.end) stop(ae.ev.id(), true);
        }
    }

    // ---- rigging & random rolls ----
    public void rig(String id, Integer minutes) { rigs.add(new String[]{id, minutes == null ? null : String.valueOf(minutes)}); }
    public void clearRigs() { rigs.clear(); }
    public List<String> rigged() {
        List<String> l = new ArrayList<>();
        for (String[] r : rigs) l.add(r[0] + (r[1] != null ? " (" + r[1] + "m)" : ""));
        return l;
    }

    /** Starts up to count events: rigged ones first, then random eligible ones. Returns the started ids. */
    public List<String> roll(int count, Integer minutes) {
        List<String> started = new ArrayList<>();
        Set<String> tried = new HashSet<>();
        while (started.size() < count) {
            String[] r = rigs.poll();
            String id; Integer m = minutes;
            if (r != null) { id = r[0]; if (r[1] != null) m = Integer.parseInt(r[1]); }
            else {
                List<String> pool = new ArrayList<>();
                for (GameEvent e : registry.values())
                    if (!tried.contains(e.id()) && !active.containsKey(e.id()) && e.sec().getBoolean("enabled", true)) pool.add(e.id());
                if (pool.isEmpty()) break;
                id = pool.get(new Random().nextInt(pool.size()));
            }
            tried.add(id);
            if (start(id, m, false) == null) started.add(id);
        }
        return started;
    }

    // ---- stale effect hygiene ----
    public void stripStale(Player p) {
        for (GameEvent ev : registry.values()) {
            if (!isActive(ev.id())) { ev.strip(p); ev.sweep(p); }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pl.sched.entityLater(p, () -> {
            stripStale(p);
            for (ActiveEvent ae : active.values()) {
                ae.ev.apply(p);
                if (ae.bar != null) p.showBossBar(ae.bar);   // same bar, same timer: nothing is restarted
                if (ae.informed.add(p.getUniqueId())) sendJoinInfo(p, ae);   // once per player per event run
            }
        }, 5);
        pl.teams.refreshLater();
    }

    /** Explains the effects and rules of a running event to a player who joined late. */
    private void sendJoinInfo(Player p, ActiveEvent ae) {
        pl.sched.entityLater(p, () -> {
            if (active.get(ae.ev.id()) != ae) return;
            Map<String, Object> ph = placeholders(ae);
            String prefix = pl.cfg.messages().getString("prefix", "");
            List<String> lines = ae.ev.sec().getStringList("messages.join");
            if (lines.isEmpty()) lines = List.of("{description}");
            p.sendMessage(pl.text.render(prefix + pl.cfg.messages().getString("event-join.header", "<g>{event} is running ({time} left)</g>"), ph));
            String bullet = pl.cfg.messages().getString("event-join.bullet", "<g>-</g> ");
            for (String l : lines) p.sendMessage(pl.text.render(bullet + l, ph));
        }, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        for (GameEvent ev : registry.values()) ev.strip(p);
        pl.sched.global(() -> pl.teams.release(p));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        pl.sched.entityLater(p, () -> { for (ActiveEvent ae : active.values()) ae.ev.apply(p); }, 2);
    }
}
