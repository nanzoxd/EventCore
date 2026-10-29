package com.eventcore.hunt;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.BossBars;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player Hunt: any number of hunted players at once. Every target gets a bossbar with the name and live
 * coordinates (refreshed on the target's own scheduler, so it is Folia-safe), and one combined chat
 * broadcast with everybody's coordinates goes out every playerhunt.broadcast-seconds.
 */
public final class HuntManager implements Listener {

    /** Public read-only view for the GUI. */
    public record View(UUID id, String name, boolean online, String world, int x, int y, int z) { }

    private static final class Target {
        final UUID id;
        volatile String name;
        final BossBar bar;
        volatile ScheduledTask task;
        volatile boolean online;
        volatile String world = "-";
        volatile int x, y, z;

        Target(UUID id, String name, BossBar bar) { this.id = id; this.name = name; this.bar = bar; }
    }

    private final EventCorePlugin pl;
    private final Map<UUID, Target> targets = new ConcurrentHashMap<>();
    private volatile ScheduledTask broadcastTask;

    public HuntManager(EventCorePlugin pl) { this.pl = pl; }

    // ---------- config ----------
    private ConfigurationSection cs() { return pl.cfg.config().getConfigurationSection("playerhunt"); }
    public boolean enabled() { return pl.cfg.config().getBoolean("playerhunt.enabled", true); }
    public int max() { return Math.max(1, pl.cfg.config().getInt("playerhunt.max-targets", 10)); }
    private long updateTicks() { return Math.max(5, pl.cfg.config().getInt("playerhunt.update-ticks", 20)); }
    private long broadcastTicks() { return Math.max(5, pl.cfg.config().getInt("playerhunt.broadcast-seconds", 30)) * 20L; }

    private String tpl(String path, String def) { return pl.fx.msg(pl.cfg.messages(), path).tpl().isEmpty() ? def : pl.fx.msg(pl.cfg.messages(), path).tpl(); }

    // ---------- queries ----------
    public int count() { return targets.size(); }

    public List<String> names() {
        List<String> l = new ArrayList<>();
        for (Target t : targets.values()) l.add(t.name);
        l.sort(String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    public List<View> views() {
        List<View> l = new ArrayList<>();
        for (Target t : targets.values()) l.add(new View(t.id, t.name, t.online, t.world, t.x, t.y, t.z));
        l.sort(Comparator.comparing(View::name, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    public boolean isHunted(UUID id) { return targets.containsKey(id); }

    private Map<String, Object> ph(Target t) {
        return Text.ph("player", t.name, "world", t.world, "x", t.x, "y", t.y, "z", t.z, "count", targets.size());
    }

    // ---------- start / stop ----------
    /** Starts hunting the given online players. Returns the names that were added (already hunted ones are skipped). */
    public List<String> start(Collection<Player> players) {
        List<String> added = new ArrayList<>();
        for (Player p : players) {
            if (targets.containsKey(p.getUniqueId()) || targets.size() >= max()) continue;
            ConfigurationSection bs = cs() == null ? null : cs().getConfigurationSection("bossbar");
            BossBar.Color color = BossBars.enumOf(BossBar.Color.class, bs == null ? "YELLOW" : bs.getString("color", "YELLOW"), BossBar.Color.YELLOW);
            BossBar.Overlay style = BossBars.enumOf(BossBar.Overlay.class, bs == null ? "PROGRESS" : bs.getString("style", "PROGRESS"), BossBar.Overlay.PROGRESS);
            Target t = new Target(p.getUniqueId(), p.getName(), BossBar.bossBar(Component.text(" "), 1f, color, style));
            if (targets.putIfAbsent(t.id, t) != null) continue;
            t.bar.name(pl.text.render(tpl("hunt.bossbar-locating", "<bb>⌖ hunt | {player}</bb>"), ph(t)));
            pl.bars.show(t.bar);
            pl.sched.entity(p, () -> attach(t, p));
            added.add(p.getName());
            pl.fx.title(p, pl.cfg.messages().getConfigurationSection("hunt.target-title"), ph(t));
        }
        if (!added.isEmpty()) {
            ensureBroadcast();
            pl.sched.globalLater(this::broadcastNow, 10);   // first update once the coordinates are read
            pl.discord.huntStarted(added);
        }
        return added;
    }

    /** Runs on the target's own thread. */
    private void attach(Target t, Player p) {
        Sched.cancel(t.task);
        t.online = true;
        t.name = p.getName();
        refresh(t, p);
        t.task = pl.sched.entityTimer(p, task -> refresh(t, p), () -> { }, updateTicks(), updateTicks());
    }

    private void refresh(Target t, Player p) {
        if (!targets.containsKey(t.id)) return;
        Location l = p.getLocation();
        t.world = l.getWorld().getName();
        t.x = l.getBlockX(); t.y = l.getBlockY(); t.z = l.getBlockZ();
        t.bar.name(pl.text.render(tpl("hunt.bossbar", "<bb>⌖ hunt | {player} | {world} [{x}, {y}, {z}]</bb>"), ph(t)));
    }

    public boolean stop(UUID id) {
        Target t = targets.remove(id);
        if (t == null) return false;
        Sched.cancel(t.task);
        pl.bars.hide(t.bar);
        if (targets.isEmpty()) cancelBroadcast();
        return true;
    }

    /** Stops by (case-insensitive) name. Returns the stopped name or null. */
    public String stop(String name) {
        for (Target t : targets.values())
            if (t.name.equalsIgnoreCase(name)) { String n = t.name; stop(t.id); return n; }
        return null;
    }

    public int stopAll() {
        int n = 0;
        for (UUID id : new ArrayList<>(targets.keySet())) if (stop(id)) n++;
        return n;
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(targets.keySet())) stop(id);
        cancelBroadcast();
    }

    // ---------- chat broadcast ----------
    private void ensureBroadcast() {
        if (broadcastTask != null) return;
        long t = broadcastTicks();
        broadcastTask = pl.sched.globalTimer(task -> broadcastNow(), t, t);
    }

    private void cancelBroadcast() { Sched.cancel(broadcastTask); broadcastTask = null; }

    /** Re-reads playerhunt.broadcast-seconds (called after config edits and reloads). */
    public void rebuild() {
        if (broadcastTask == null) return;
        cancelBroadcast();
        if (!targets.isEmpty()) ensureBroadcast();
    }

    public void broadcastNow() {
        if (targets.isEmpty()) return;
        List<Component> lines = new ArrayList<>();
        lines.add(pl.msg("hunt.broadcast-header", "count", targets.size()));
        List<Target> sorted = new ArrayList<>(targets.values());
        sorted.sort(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
        for (Target t : sorted)   // target lines carry no prefix: one prefix on the header is enough
            lines.add(pl.text.render(tpl(t.online ? "hunt.broadcast-line" : "hunt.broadcast-offline", ""), ph(t)));
        Bukkit.getServer().sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        pl.fx.playAll(pl.cfg.config().getConfigurationSection("playerhunt.sound"));
    }

    // ---------- events ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Target t = targets.get(e.getPlayer().getUniqueId());
        if (t != null) attach(t, e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Target t = targets.get(e.getPlayer().getUniqueId());
        if (t == null) return;
        t.online = false;
        Sched.cancel(t.task);
        t.task = null;
        t.bar.name(pl.text.render(tpl("hunt.bossbar-offline", "<bb>⌖ hunt | {player} | offline</bb>"), ph(t)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!pl.cfg.config().getBoolean("playerhunt.stop-on-death", false)) return;
        UUID id = e.getPlayer().getUniqueId();
        Target t = targets.get(id);
        if (t == null) return;
        Map<String, Object> ph = ph(t);
        if (stop(id)) Bukkit.getServer().sendMessage(pl.msgMap("hunt.target-died", ph));
    }
}
