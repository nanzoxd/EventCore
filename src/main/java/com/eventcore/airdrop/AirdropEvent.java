package com.eventcore.airdrop;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.event.GameEvent;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Airdrop event: generates a random code, shows it (bossbar / actionbar / title / chat, each toggleable), and drops
 * tiered shulker crates from the sky at a configurable rhythm. Crate behaviour lives in {@link AirdropCrate}.
 * Crates are invulnerable at all times (explosion-proof); hits are counted as virtual HP via PrePlayerAttackEntityEvent.
 */
public final class AirdropEvent extends GameEvent {
    public enum State { FALLING, LOCKED, READY, OPENED }

    public final Map<UUID, AirdropCrate> crates = new ConcurrentHashMap<>();
    private volatile String code = "";
    private volatile int spawned;
    private volatile long nextDropAt;
    private long startedAt;
    private ScheduledTask barTask;
    private volatile boolean spawning;
    private volatile long spawningSince;
    private volatile int failStreak;

    public AirdropEvent(EventCorePlugin pl) { super(pl, "airdrop"); }

    // ---------- tiers ----------
    public ConfigurationSection tierSec(String id) { return settings().getConfigurationSection("tiers." + id); }

    public List<String> tierIds() {
        ConfigurationSection t = settings().getConfigurationSection("tiers");
        return t == null ? List.of() : new ArrayList<>(t.getKeys(false));
    }

    public String pickTier() {
        double total = 0;
        for (String id : tierIds()) total += Math.max(0, tierSec(id).getDouble("weight", 1));
        if (total <= 0) return tierIds().isEmpty() ? null : tierIds().get(0);
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (String id : tierIds()) {
            r -= Math.max(0, tierSec(id).getDouble("weight", 1));
            if (r <= 0) return id;
        }
        return tierIds().get(0);
    }

    public Component tierName(String id) {
        ConfigurationSection t = tierSec(id);
        String n = t == null ? id : t.getString("display-name", id);
        List<String> g = t == null ? List.of() : t.getStringList("gradient");
        return pl.text.gradient(com.eventcore.util.Util.title(n), g.isEmpty() ? pl.text.palette() : g, 0);
    }

    /** Tier value, falling back to the event-wide default at the same relative path. */
    public ConfigurationSection resolve(String tier, String tierPath, String globalPath) {
        ConfigurationSection t = tierSec(tier);
        if (t != null && t.isConfigurationSection(tierPath)) return t.getConfigurationSection(tierPath);
        return settings().getConfigurationSection(globalPath);
    }

    public String code() { return code; }

    private String generateCode() {
        ConfigurationSection c = settings().getConfigurationSection("code");
        int len = Math.max(1, c == null ? 6 : c.getInt("length", 6));
        String cs = c == null ? "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" : c.getString("charset", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
        if (cs.isEmpty()) cs = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < len; i++) b.append(cs.charAt(ThreadLocalRandom.current().nextInt(cs.length())));
        return b.toString();
    }

    // ---------- lifecycle ----------
    @Override public boolean ownsBarTitle() { return true; }

    @Override public Map<String, Object> extra(ActiveEvent ae) {
        return Text.ph("code", code, "status", status(), "coords", coords(), "crates", crates.size());
    }

    /** The crate the bossbar talks about: a falling one first, otherwise any unopened one. */
    public AirdropCrate focus() {
        AirdropCrate other = null;
        for (AirdropCrate c : crates.values()) {
            if (c.state == State.FALLING) return c;
            if (c.state != State.OPENED) other = c;
        }
        return other;
    }

    public int incomingSeconds() { return Math.max(1, settings().getInt("spawn.incoming-seconds", 30)); }
    public int fallSeconds() { return Math.max(1, (int) Math.round(settings().getDouble("fall.duration-minutes", 25.0) * 60)); }
    /** Always at least 100 blocks above the surface. */
    public double fallHeight() { return Math.max(100.0, settings().getDouble("fall.min-height", 100.0)); }
    /** Shortest event length that lets the first crate land and stay open for a while. */
    @Override public int minMinutes() { return (int) Math.ceil((incomingSeconds() + fallSeconds() + 300) / 60.0); }

    public static String fmtTime(int s) {
        s = Math.max(0, s);
        if (s >= 60) { int m = (int) Math.ceil(s / 60.0); return m + (m == 1 ? " Minute" : " Minutes"); }
        return s + (s == 1 ? " Second" : " Seconds");
    }

    private boolean allDone() {
        int max = settings().getInt("spawn.max-crates", 5);
        return max > 0 && spawned >= max;
    }

    private String status() {
        ConfigurationSection st = settings().getConfigurationSection("status");
        AirdropCrate f = focus();
        String key = f == null ? (allDone() ? "done" : "waiting") : f.state == State.FALLING ? "falling" : "landed";
        return st == null ? key : st.getString(key, key);
    }

    /** Incoming: seconds until the drop. Falling: time left to land. Landed: coordinates. */
    private String coords() {
        AirdropCrate f = focus();
        if (f != null) return f.state == State.FALLING ? fmtTime(f.secondsLeft()) : f.coords();
        if (allDone()) return "-";
        return fmtTime((int) Math.max(0, (nextDropAt - System.currentTimeMillis() + 999) / 1000));
    }

    @Override public void onStart(ActiveEvent ae) {
        code = generateCode();
        spawned = 0;
        startedAt = System.currentTimeMillis();
        nextDropAt = startedAt + incomingSeconds() * 1000L;
        spawning = false;
        failStreak = 0;
        int period = Math.max(1, settings().getInt("bossbar-update-ticks", 5));
        barTask = pl.sched.globalTimer(t -> animateBar(ae, t), 1, period);
    }

    @Override public void onStop(ActiveEvent ae) {
        Sched.cancel(barTask);
        destroyAll();
    }

    @Override public void onSecond(ActiveEvent ae, int left) {
        long now = System.currentTimeMillis();
        if (spawning && now - spawningSince > 20_000L) {   // a spawn callback got lost: never block forever
            pl.getLogger().warning("Airdrop: spawn attempt timed out, retrying.");
            spawning = false;
        }
        int stopBefore = settings().getInt("spawn.stop-spawning-seconds-before-end", 60);
        // a crate must be able to land before the event ends
        if (now < nextDropAt || spawning || allDone() || left < fallSeconds() + stopBefore) return;
        String tier = pickTier();
        if (tier == null) { nextDropAt = now + 30_000L; pl.getLogger().warning("Airdrop: no crate tiers configured."); return; }
        spawning = true;
        spawningSince = now;
        try {
            spawnRandom(tier, ok -> {
                long t = System.currentTimeMillis();
                spawning = false;
                if (ok) { failStreak = 0; nextDropAt = t + settings().getInt("spawn.interval-seconds", 120) * 1000L; }
                else {
                    nextDropAt = t + 5_000L;   // retry soon instead of waiting a whole interval
                    if (failStreak++ % 12 == 0) pl.getLogger().warning("Airdrop: could not place a crate, retrying in 5s (attempt " + failStreak + ").");
                }
            });
        } catch (Throwable ex) {
            spawning = false;
            nextDropAt = now + 5_000L;
            pl.getLogger().warning("Airdrop: spawn failed: " + ex);
        }
    }

    @Override public Component displayText(ActiveEvent ae) {
        return pl.text.bossbar(pl.text.expandPlain(settings().getString("bossbar-text", "Lootdrop {status} | {coords}"),
                Text.ph("code", code, "status", status(), "coords", coords(), "crates", crates.size())));
    }

    /** Simple bossbar: "Lootdrop Falling | 10 20 30" in the bossbar gradient; the bar fills as the crate falls. */
    private void animateBar(ActiveEvent ae, ScheduledTask t) {
        if (!isActiveAe(ae)) { t.cancel(); return; }
        if (ae.bar == null) return;
        ae.bar.name(displayText(ae));
        AirdropCrate f = focus();
        ae.bar.progress(f != null && f.state == State.FALLING ? com.eventcore.util.BossBars.clamp(f.fallProgress())
                : com.eventcore.util.BossBars.clamp(ae.secondsLeft() / (double) Math.max(1, ae.totalSec)));
    }

    private boolean isActiveAe(ActiveEvent ae) { return pl.events.active(id()) == ae; }

    // ---------- spawning ----------
    private World areaWorld(ConfigurationSection a) {
        World w = Bukkit.getWorld(a.getString("world", "world"));
        if (w != null) return w;
        for (World x : Bukkit.getWorlds()) if (x.getEnvironment() == World.Environment.NORMAL) {
            pl.getLogger().warning("Airdrop: world '" + a.getString("world", "world") + "' not found, using '" + x.getName() + "'.");
            return x;
        }
        return null;
    }

    private void spawnRandom(String tier, Consumer<Boolean> done) {
        ConfigurationSection sp = settings().getConfigurationSection("spawn");
        if (sp == null) { done.accept(false); return; }
        List<String> worlds = sp.getStringList("worlds");
        if (sp.getString("mode", "AROUND_PLAYER").equalsIgnoreCase("AREA")) {
            ConfigurationSection a = sp.getConfigurationSection("area");
            World w = a == null ? null : areaWorld(a);
            if (w == null) { done.accept(false); return; }
            double cx = a.getDouble("center-x"), cz = a.getDouble("center-z");
            if (a.getBoolean("use-world-spawn", true)) { Location sp0 = w.getSpawnLocation(); cx = sp0.getX(); cz = sp0.getZ(); }
            attempt(w, cx, cz, 0, a.getDouble("half-size", 4000), tier, 0, true, done);
            return;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getWorld().getEnvironment() == World.Environment.NORMAL && (worlds.isEmpty() || worlds.contains(p.getWorld().getName())))
                candidates.add(p);
        if (candidates.isEmpty()) { done.accept(false); return; }
        Player p = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        pl.sched.entity(p, () -> {
            Location l = p.getLocation();
            attempt(l.getWorld(), l.getX(), l.getZ(), sp.getDouble("min-distance", 30), sp.getDouble("max-distance", 120), tier, 0, false, done);
        });
    }

    private void attempt(World w, double cx, double cz, double minD, double maxD, String tier, int tries, boolean square, Consumer<Boolean> done) {
        int maxTries = Math.max(1, settings().getInt("spawn.max-attempts", 8));
        if (tries >= maxTries) { done.accept(false); return; }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble(Math.PI * 2);
        double dist = maxD <= minD ? minD : r.nextDouble(minD, maxD);
        final int x = square ? (int) Math.floor(cx + r.nextDouble(-maxD, maxD)) : (int) Math.floor(cx + Math.cos(ang) * dist);
        final int z = square ? (int) Math.floor(cz + r.nextDouble(-maxD, maxD)) : (int) Math.floor(cz + Math.sin(ang) * dist);
        final boolean lastTry = tries >= maxTries - 1;
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> pl.sched.region(new Location(w, x, 64, z), () -> {
            try {
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block b = w.getBlockAt(x, y, z);
                boolean bad = y <= w.getMinHeight()
                        || !w.getWorldBorder().isInside(new Location(w, x, y, z))
                        || (settings().getBoolean("spawn.avoid-liquids", true) && b.isLiquid() && !lastTry);   // last try accepts water
                if (bad) { attempt(w, cx, cz, minD, maxD, tier, tries + 1, square, done); return; }
                done.accept(spawnCrate(tier, new Location(w, x + 0.5, y + 1, z + 0.5)));
            } catch (Throwable ex) {
                pl.getLogger().warning("Airdrop: spawn error: " + ex);
                done.accept(false);
            }
        })).exceptionally(ex -> {
            pl.getLogger().warning("Airdrop: chunk load failed: " + ex);
            done.accept(false);
            return null;
        });
    }

    /** Must run on the region thread of ground. Returns false (and cleans up) when the crate could not be created. */
    public boolean spawnCrate(String tier, Location ground) {
        if (tierSec(tier) == null) return false;
        String c = code.isEmpty() ? generateCode() : code;
        try {
            new AirdropCrate(pl, this, tier, ground, c).spawn();
            spawned++;
            return true;
        } catch (Throwable ex) {
            pl.getLogger().warning("Airdrop: could not spawn crate: " + ex);
            return false;
        }
    }

    /** /eventcore airdrop spawn: drops a crate on the sender's position. */
    public boolean spawnAt(Player p, String tier) {
        if (tierSec(tier) == null) return false;
        Location l = p.getLocation();
        pl.sched.region(l, () -> {
            World w = l.getWorld();
            int y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
            spawnCrate(tier, new Location(w, l.getBlockX() + 0.5, y + 1, l.getBlockZ() + 0.5));
        });
        return true;
    }

    public void destroyAll() {
        boolean direct = !pl.isEnabled();
        for (AirdropCrate c : new ArrayList<>(crates.values())) c.destroy(direct);
        crates.clear();
    }

    // ---------- listeners ----------
    /** Admins (eventcore.gui) who sneak and click a crate, falling or landed, open that tier's loot editor. */
    private boolean openEditor(Player p, AirdropCrate c) {
        if (!p.isSneaking() || !p.hasPermission("eventcore.gui")) return false;
        pl.sched.entity(p, () -> pl.lootGui.openEntries(p, c.tier(), 0));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        AirdropCrate c = crates.get(e.getAttacked().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        if (openEditor(e.getPlayer(), c)) return;
        c.hit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(AirdropCrate.CHUTE, org.bukkit.persistence.PersistentDataType.BYTE)) {
            e.setCancelled(true);
            return;
        }
        AirdropCrate c = crates.get(e.getRightClicked().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        if (openEditor(e.getPlayer(), c)) return;
        c.interact(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (crates.containsKey(e.getEntity().getUniqueId())
                || e.getEntity().getPersistentDataContainer().has(AirdropCrate.CHUTE, org.bukkit.persistence.PersistentDataType.BYTE)) e.setCancelled(true);
    }
}
