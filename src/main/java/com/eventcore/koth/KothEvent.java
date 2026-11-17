package com.eventcore.koth;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.event.GameEvent;
import com.eventcore.gui.dialog.DialogSupport;
import com.eventcore.gui.dialog.Ui;
import com.eventcore.koth.KothData.Arena;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * King of the Hill. Admins paste a build (FAWE / WorldEdit schematic), pick the capture area by hand (pos1 / pos2) and start it with
 * /event start koth. Players join through a Yes/No menu (like the disaster event), stand in the capture area alone to fill the capture
 * bar, and the first to 100% wins the reward. The controlling player glows and gets particles.
 */
public final class KothEvent extends GameEvent {
    public final KothData data;
    public final KothBuilds builds;
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet(), viewers = ConcurrentHashMap.newKeySet(), glowing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> points = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>(), denyAt = new ConcurrentHashMap<>();
    private final AtomicBoolean won = new AtomicBoolean();
    private volatile UUID controller;
    private volatile boolean contested, empty = true;
    private volatile long startedAt;
    private volatile BossBar bar;
    private ScheduledTask tickTask, fxTask;

    public KothEvent(EventCorePlugin pl) {
        super(pl, "koth");
        data = new KothData(pl);
        builds = new KothBuilds(pl);
        Bukkit.getPluginManager().registerEvents(new KothProtection(pl, this, data), pl);
    }

    // ------------------------------------------------------------------ small helpers
    private String prefix() { return pl.cfg.messages().getString("prefix", ""); }
    public void say(CommandSender s, String mini) { s.sendMessage(pl.text.render(prefix() + mini, Map.of())); }
    public boolean running() { return active(); }
    public boolean isParticipant(Player p) { return participants.contains(p.getUniqueId()); }
    public boolean inGrace(Player p) {
        long now = System.currentTimeMillis();
        return now < startedAt + (long) (data.num("grace-seconds", 30) * 1000) || now < graceUntil.getOrDefault(p.getUniqueId(), 0L);
    }

    /** Short "you can't do that" message, at most once per 2 seconds per player. */
    public void deny(Player p, String text) {
        long now = System.currentTimeMillis();
        if (now - denyAt.getOrDefault(p.getUniqueId(), 0L) < 2000) return;
        denyAt.put(p.getUniqueId(), now); say(p, "<#EE2424>" + text);
    }

    private static String name(UUID id) { return Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("?"); }

    private TextColor colourOf(UUID id) {
        List<String> hex = settings().getStringList("colors");
        if (hex.isEmpty()) hex = List.of("#FF8FD8", "#5490FF", "#80ED26", "#FADE55", "#FF7A3D", "#B07CFF", "#3DE0D0", "#FF5555");
        TextColor c = TextColor.fromHexString(hex.get(Math.floorMod(id.hashCode(), hex.size())));
        return c == null ? NamedTextColor.LIGHT_PURPLE : c;
    }

    @Override public Map<String, Object> extra(ActiveEvent ae) {
        Arena a = data.active();
        return Text.ph("arena", a == null ? "-" : a.name, "need", (int) data.num("points-to-win", 120));
    }

    @Override public Component displayText(ActiveEvent ae) { return null; }          // KoTH shows its own capture bar

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onStart(ActiveEvent ae) {
        Arena a = data.active();
        if (a == null || !a.hasArea() || data.world(a) == null) {
            say(Bukkit.getConsoleSender(), "<#EE2424>KoTH cannot start: place a build and set the capture area first (/eventcore koth menu).");
            Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("eventcore.koth")).forEach(p -> say(p, "<#EE2424>KoTH cannot start: place a build and set the capture area first (/eventcore koth menu)."));
            pl.sched.globalLater(() -> pl.events.stop(id(), false), 2);
            return;
        }
        won.set(false); participants.clear(); points.clear(); graceUntil.clear(); controller = null; contested = false; empty = true;
        startedAt = System.currentTimeMillis();
        bar = BossBar.bossBar(Component.text("KoTH"), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        World w = data.world(a); Location anchor = new Location(w, a.min[0], a.min[1], a.min[2]);
        tickTask = pl.sched.globalTimer(t -> pl.sched.region(anchor, this::tick), 20, 20);
        fxTask = pl.sched.globalTimer(t -> pl.sched.region(anchor, this::fx), 20, 5);
        Map<String, Object> ph = new HashMap<>(pl.events.placeholders(ae));
        pl.fx.broadcast(sec(), "messages.invite", ph, prefix());
        for (Player p : Bukkit.getOnlinePlayers()) if (eligible(p)) pl.sched.entity(p, () -> openJoinMenu(p));
    }

    @Override
    public void onStop(ActiveEvent ae) {
        if (tickTask != null) { Sched.cancel(tickTask); tickTask = null; }
        if (fxTask != null) { Sched.cancel(fxTask); fxTask = null; }
        if (!won.get() && data.bool("timeout-winner", true)) {
            points.entrySet().stream().max(Map.Entry.comparingByValue()).filter(e -> e.getValue() > 0).map(Map.Entry::getKey).ifPresent(id -> {
                won.set(true); announceWinner(id); reward(id); });
        }
        BossBar b = bar; bar = null;
        for (UUID id : new ArrayList<>(viewers)) { Player p = Bukkit.getPlayer(id); if (p != null && b != null) pl.sched.entity(p, () -> p.hideBossBar(b)); }
        viewers.clear();
        for (UUID id : new ArrayList<>(glowing)) setGlow(id, false);
        Arena a = data.active();
        for (UUID id : new ArrayList<>(participants)) { Player p = Bukkit.getPlayer(id); if (p != null && a != null) toExit(p, a); }
        participants.clear(); controller = null;
    }

    public void shutdown() {
        if (tickTask != null) Sched.cancel(tickTask);
        if (fxTask != null) Sched.cancel(fxTask);
        for (UUID id : new ArrayList<>(glowing)) setGlow(id, false);
        data.save();
    }

    // ------------------------------------------------------------------ joining + teleport (same flow as the disaster event)
    private boolean eligible(Player p) { return p.getGameMode() != GameMode.SPECTATOR && !participants.contains(p.getUniqueId()); }

    private boolean joinOpen() {
        double until = data.num("join-until-minutes", 10);
        return until <= 0 || System.currentTimeMillis() < startedAt + (long) (until * 60_000);
    }

    /** Yes / No menu (a dialog, or a clickable chat line when dialogs are off). */
    public void openJoinMenu(Player p) {
        Arena a = data.active();
        if (!running() || a == null) { say(p, "<#EE2424>no KoTH is running."); return; }
        if (participants.contains(p.getUniqueId())) { say(p, "you are already in the KoTH."); return; }
        if (!joinOpen()) { say(p, "<#EE2424>joining is closed."); return; }
        if (!DialogSupport.enabled(pl)) {
            p.sendMessage(pl.text.render(prefix() + "<#FFD54A>KoTH is running!</#FFD54A> ", Map.of()).append(Component.text("[click to join]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpkoth join"))));
            return;
        }
        new DialogSupport.Screen("King of the Hill", "Join the KoTH?")
                .content(Ui.text("Hold the capture area alone to fill the bar. The first player to 100% wins."), Ui.secondary("Arena: " + a.name + "  |  PvP: " + (data.flag(KothData.Flag.PVP) ? "on" : "off")))
                .columns(2).action(Ui.button(Ui.mark(true, "Yes, teleport me"), null, Ui.HALF_WIDTH, 1, (pp, v) -> join(pp)))
                .exit(Ui.button(Ui.mark(false, "No"), null, Ui.HALF_WIDTH, 1, null)).show(p);
    }

    public void join(Player p) {
        Arena a = data.active();
        if (!running() || a == null) { say(p, "<#EE2424>no KoTH is running."); return; }
        if (!joinOpen()) { say(p, "<#EE2424>joining is closed."); return; }
        if (p.getGameMode() == GameMode.SPECTATOR || !participants.add(p.getUniqueId())) return;
        graceUntil.put(p.getUniqueId(), System.currentTimeMillis() + (long) (data.num("entry-grace-seconds", 8) * 1000));
        World w = data.world(a); if (w == null) return;
        double[] s = a.spawn != null ? a.spawn : a.centre(); double r = data.num("spread-radius", 4);
        double dx = r <= 0 ? 0 : (ThreadLocalRandom.current().nextDouble() * 2 - 1) * r, dz = r <= 0 ? 0 : (ThreadLocalRandom.current().nextDouble() * 2 - 1) * r;
        Location to = new Location(w, s[0] + dx, s[1] + (a.spawn == null ? 1 : 0), s[2] + dz, a.spawn != null && s.length > 3 ? (float) s[3] : 0f, a.spawn != null && s.length > 4 ? (float) s[4] : 0f);
        if (data.bool("heal-on-start", true)) { var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH); p.setHealth(attr == null ? 20 : attr.getValue()); p.setFoodLevel(20); }
        p.teleportAsync(to);
        say(p, "<#00FF55>you joined the KoTH.</#00FF55> stand in the capture area alone to score.");
    }

    public void leave(Player p) {
        Arena a = data.active();
        if (!participants.remove(p.getUniqueId())) { say(p, "you are not in the KoTH."); return; }
        setGlow(p.getUniqueId(), false);
        if (a != null) toExit(p, a);
    }

    private void toExit(Player p, Arena a) {
        World w = a.exitWorld == null ? null : Bukkit.getWorld(a.exitWorld);
        Location to = a.exit != null && w != null ? new Location(w, a.exit[0], a.exit[1], a.exit[2], a.exit.length > 3 ? (float) a.exit[3] : 0f, a.exit.length > 4 ? (float) a.exit[4] : 0f)
                : Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleportAsync(to);
    }

    @EventHandler public void onJoinServer(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (running() && eligible(p)) pl.sched.entityLater(p, () -> openJoinMenu(p), 60);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { setGlow(e.getPlayer().getUniqueId(), false); viewers.remove(e.getPlayer().getUniqueId()); }

    @EventHandler public void onDeath(PlayerDeathEvent e) { setGlow(e.getPlayer().getUniqueId(), false); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Arena a = data.active();
        if (!running() || a == null || !participants.contains(e.getPlayer().getUniqueId()) || !data.flag(KothData.Flag.RESPAWN_AT_SPAWN) || a.spawn == null) return;
        World w = data.world(a); if (w != null) e.setRespawnLocation(new Location(w, a.spawn[0], a.spawn[1], a.spawn[2]));
    }

    // ------------------------------------------------------------------ capture round (runs every second in the arena's region)
    private boolean canCapture(Player p, Arena a) {
        return participants.contains(p.getUniqueId()) && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && a.inArea(p.getLocation());
    }

    private void tick() {
        Arena a = data.active(); World w = a == null ? null : data.world(a); BossBar b = bar;
        if (!running() || w == null || b == null) return;
        int need = Math.max(1, (int) data.num("points-to-win", 120)), pps = Math.max(1, (int) data.num("points-per-second", 1));
        boolean cg = data.bool("contested-gives-points", false);
        List<Player> inside = new ArrayList<>(); Set<UUID> near = new HashSet<>();
        int margin = (int) data.num("zone-margin", 30);
        for (Player p : w.getPlayers()) {
            if (a.in(p.getLocation(), margin)) near.add(p.getUniqueId());
            if (canCapture(p, a)) inside.add(p);
        }
        empty = inside.isEmpty(); contested = inside.size() > 1;
        Player holder = inside.size() == 1 ? inside.get(0) : null;
        UUID old = controller;
        if (holder != null) {
            controller = holder.getUniqueId();
            points.merge(controller, pps, Integer::sum);
            if (!controller.equals(old)) takeover(holder, near);
        } else if (cg) for (Player p : inside) points.merge(p.getUniqueId(), pps, Integer::sum);
        for (UUID id : new ArrayList<>(glowing)) if (holder == null || !id.equals(holder.getUniqueId())) setGlow(id, false);
        if (holder != null) setGlow(holder.getUniqueId(), true);

        UUID shown = controller != null ? controller : points.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        int score = shown == null ? 0 : points.getOrDefault(shown, 0), pct = Math.min(100, score * 100 / need);
        TextColor col = shown == null ? NamedTextColor.WHITE : colourOf(shown);
        Component title = contested ? Component.text("▣ The point is contested!", NamedTextColor.RED)
                : holder != null ? Component.text("▣ " + holder.getName() + " ", col).append(Component.text("is controlling the point!", NamedTextColor.GRAY))
                : Component.text("▣ The point is empty", NamedTextColor.GRAY);
        b.name(title); b.progress(Math.max(0f, Math.min(1f, pct / 100f)));
        b.color(contested ? BossBar.Color.RED : holder != null ? BossBar.Color.PINK : BossBar.Color.WHITE);
        Component actionbar = captureBar(pct, col);
        for (Player p : w.getPlayers()) {
            boolean show = near.contains(p.getUniqueId()) || participants.contains(p.getUniqueId());
            if (show && viewers.add(p.getUniqueId())) pl.sched.entity(p, () -> p.showBossBar(b));
            else if (!show && viewers.remove(p.getUniqueId())) pl.sched.entity(p, () -> p.hideBossBar(b));
            if (show && (a.in(p.getLocation(), 0) || a.inArea(p.getLocation()))) pl.sched.entity(p, () -> p.sendActionBar(actionbar));
        }
        if (score >= need && shown != null) finish(shown);
    }

    /** "65% ████████████░░░░░░░░" in the controller's colour. */
    private static Component captureBar(int pct, TextColor c) {
        int filled = Math.round(pct / 5f);
        return Component.text(pct + "% ", c).append(Component.text("█".repeat(filled), c)).append(Component.text("█".repeat(20 - filled), NamedTextColor.WHITE));
    }

    private void takeover(Player holder, Set<UUID> near) {
        Component t = Component.text("▣ " + holder.getName(), colourOf(holder.getUniqueId()));
        Title title = Title.title(t, Component.text("is controlling the point!", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400)));
        for (UUID id : near) { Player p = Bukkit.getPlayer(id); if (p != null) pl.sched.entity(p, () -> p.showTitle(title)); }
    }

    private void setGlow(UUID id, boolean on) {
        Player p = Bukkit.getPlayer(id);
        if (on ? !glowing.add(id) : !glowing.remove(id)) return;
        if (p != null) pl.sched.entity(p, () -> p.setGlowing(on));
    }

    /** Particles around the controlling player, four times a second. */
    private void fx() {
        UUID id = controller; Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p == null || !glowing.contains(id)) return;
        TextColor c = colourOf(id); Color col = Color.fromRGB(c.red(), c.green(), c.blue());
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.4f);
        pl.sched.entity(p, () -> {
            Location l = p.getLocation(); World w = l.getWorld(); double t = (System.currentTimeMillis() % 2000) / 2000.0 * Math.PI * 2;
            for (int i = 0; i < 3; i++) {
                double ang = t + i * Math.PI * 2 / 3;
                w.spawnParticle(Particle.DUST, l.getX() + Math.cos(ang) * 0.9, l.getY() + 0.1 + (t / (Math.PI * 2)) * 1.8, l.getZ() + Math.sin(ang) * 0.9, 1, 0, 0, 0, 0, dust);
            }
            w.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 1, l.getZ(), 1, 0.25, 0.5, 0.25, 0.01);
        });
    }

    // ------------------------------------------------------------------ winning + rewards (same format as the other events: reward.items / reward.commands)
    private void finish(UUID id) {
        if (!won.compareAndSet(false, true)) return;
        pl.sched.global(() -> { announceWinner(id); reward(id); pl.events.stop(id(), false); });
    }

    private void announceWinner(UUID id) {
        Map<String, Object> ph = Text.ph("player", name(id), "points", points.getOrDefault(id, 0), "event", pl.text.sc(displayName()), "gradient_raw", gradientRaw());
        pl.fx.title(Bukkit.getServer(), sec().getConfigurationSection("titles.winner"), ph);
        pl.fx.broadcast(sec(), "messages.winner", ph, prefix());
    }

    private void reward(UUID id) {
        ConfigurationSection r = settings().getConfigurationSection("reward");
        Player p = Bukkit.getPlayer(id);
        if (r == null) return;
        for (String s : r.getStringList("items")) {
            String[] parts = s.split(":"); Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || !m.isItem() || p == null) continue;
            int amount = 1; if (parts.length > 1) try { amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1].trim()))); } catch (NumberFormatException ignored) { }
            ItemStack stack = new ItemStack(m, amount);
            pl.sched.entity(p, () -> Util.give(p, stack));
        }
        for (String cmd : r.getStringList("commands")) pl.sched.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", name(id))));
    }

    // ------------------------------------------------------------------ admin operations (commands and the settings dialog share these)
    public void placeBuild(Player p, String buildId, String arenaName) {
        if (running()) { say(p, "<#EE2424>stop the KoTH before placing a build."); return; }
        if (KothBuilds.engine() == null) { say(p, "<#EE2424>install FastAsyncWorldEdit or WorldEdit first."); return; }
        if (builds.file(buildId) == null) { say(p, "<#EE2424>unknown build. Available: " + String.join(", ", builds.ids())); return; }
        String n = arenaName == null || arenaName.isBlank() ? buildId : arenaName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (data.arenas.containsKey(n)) { say(p, "<#EE2424>an arena called " + n + " exists. /eventcore koth remove " + n + " first."); return; }
        Arena a = new Arena(); a.name = n; a.build = buildId; a.world = p.getWorld().getName();
        say(p, "pasting <#FFD54A>" + builds.display(buildId) + "</#FFD54A> with " + KothBuilds.engine() + " (centred on you)...");
        builds.place(data, a, p.getLocation().getBlock().getLocation()).whenComplete((v, err) -> pl.sched.entity(p, () -> {
            if (err != null) { say(p, "<#EE2424>paste failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())); return; }
            data.arenas.put(n, a); if (data.active == null) data.active = n; data.save();
            say(p, "<#00FF55>" + n + " is placed and protected.</#00FF55> now select the capture area: /eventcore koth pos1 and pos2 (or select with WorldEdit and /eventcore koth area).");
        }));
    }

    public void removeArena(CommandSender s, String name) {
        Arena a = data.arenas.get(name);
        if (a == null) { say(s, "<#EE2424>unknown arena. /eventcore koth list"); return; }
        if (running() && a == data.active()) { say(s, "<#EE2424>stop the KoTH first."); return; }
        say(s, "restoring the terrain...");
        builds.restore(data, a).whenComplete((v, err) -> pl.sched.global(() -> {
            if (err != null) say(s, "<#EE2424>restore failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()) + " (the arena entry was kept)");
            else { data.arenas.remove(name); if (name.equals(data.active)) data.active = data.arenas.isEmpty() ? null : data.arenas.keySet().iterator().next(); data.save(); say(s, "<#00FF55>" + name + " removed, the terrain is back."); }
        }));
    }

    private Arena activeOrSay(CommandSender s) {
        Arena a = data.active();
        if (a == null) say(s, "<#EE2424>no arena yet. Place a build first (/eventcore koth menu).");
        return a;
    }

    public void setPos(Player p, int which) {
        Arena a = activeOrSay(p); if (a == null) return;
        if (!p.getWorld().getName().equals(a.world)) { say(p, "<#EE2424>the arena is in world " + a.world + "."); return; }
        int[] pos = {p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ()};
        if (which == 1) a.pos1 = pos; else a.pos2 = pos;
        data.save(); say(p, "pos" + which + " set to <#FFD54A>" + pos[0] + " " + pos[1] + " " + pos[2] + "</#FFD54A>" + (a.hasArea() ? ". The capture area is ready." : "."));
    }

    public void useSelection(Player p) {
        Arena a = activeOrSay(p); if (a == null) return;
        int[] s = builds.selection(p);
        if (s == null) { say(p, "<#EE2424>make a WorldEdit selection first (//wand, left and right click)."); return; }
        a.pos1 = new int[]{s[0], s[1], s[2]}; a.pos2 = new int[]{s[3], s[4], s[5]}; data.save();
        say(p, "capture area set from your WorldEdit selection: <#FFD54A>" + s[0] + " " + s[1] + " " + s[2] + " to " + s[3] + " " + s[4] + " " + s[5]);
    }

    public void setSpawn(Player p, boolean exit) {
        Arena a = activeOrSay(p); if (a == null) return;
        Location l = p.getLocation(); double[] v = {l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()};
        if (exit) { a.exit = v; a.exitWorld = l.getWorld().getName(); } else a.spawn = v;
        data.save(); say(p, (exit ? "exit" : "spawn") + " point saved.");
    }
}
