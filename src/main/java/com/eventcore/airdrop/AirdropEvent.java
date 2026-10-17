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
    private double phase = -1;
    private int scrollPos;
    private ScheduledTask barTask;

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
        return pl.text.gradient(pl.text.sc(n), g.isEmpty() ? pl.text.palette() : g, 0);
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
        return Text.ph("code", code, "status", status(), "crates", crates.size());
    }

    private String status() {
        ConfigurationSection st = settings().getConfigurationSection("status");
        boolean falling = false, ready = false;
        for (AirdropCrate c : crates.values()) {
            if (c.state == State.FALLING) falling = true;
            else if (c.state != State.OPENED) ready = true;
        }
        String key = falling ? "falling" : ready ? "landed" : "waiting";
        int max = settings().getInt("spawn.max-crates", 5);
        if (!falling && !ready && spawned >= max && max > 0) key = "done";
        String tpl = st == null ? key : st.getString(key, key);
        int secs = (int) Math.max(0, (nextDropAt - System.currentTimeMillis()) / 1000);
        return pl.text.expand(tpl, Text.ph("seconds", secs));
    }

    @Override public void onStart(ActiveEvent ae) {
        code = generateCode();
        spawned = 0;
        startedAt = System.currentTimeMillis();
        nextDropAt = startedAt + settings().getInt("spawn.first-delay-seconds", 20) * 1000L;
        ConfigurationSection c = settings().getConfigurationSection("code");
        Map<String, Object> ph = Text.ph("code", code);
        if (c != null) {
            if (c.getBoolean("show-title", true)) pl.fx.title(Bukkit.getServer(), c.getConfigurationSection("title"), ph);
            if (c.contains("chat")) pl.fx.broadcast(c, "chat", ph, pl.cfg.messages().getString("prefix", ""));
        }
        int period = Math.max(1, settings().getInt("bossbar-scroll.speed-ticks", 3));
        barTask = pl.sched.globalTimer(t -> animateBar(ae, t), 1, period);
    }

    @Override public void onStop(ActiveEvent ae) {
        Sched.cancel(barTask);
        destroyAll();
    }

    @Override public void onSecond(ActiveEvent ae, int left) {
        long now = System.currentTimeMillis();
        ConfigurationSection c = settings().getConfigurationSection("code");
        if (c != null && c.getBoolean("show-actionbar", true) && now - startedAt < c.getInt("actionbar-seconds", 30) * 1000L) {
            var comp = pl.text.render(c.getString("actionbar", "<g>airdrop code: {code}</g>"), Text.ph("code", code));
            for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(comp);
        }
        int max = settings().getInt("spawn.max-crates", 5);
        int stopBefore = settings().getInt("spawn.stop-spawning-seconds-before-end", 60);
        if (now >= nextDropAt && (max <= 0 || spawned < max) && left > stopBefore) {
            nextDropAt = now + settings().getInt("spawn.interval-seconds", 120) * 1000L;
            String tier = pickTier();
            if (tier != null) spawnRandom(tier);
        }
    }

    /** Scrolling / gradient-shifting bossbar text. Runs on the global scheduler. */
    private void animateBar(ActiveEvent ae, ScheduledTask t) {
        if (ae.bar == null || !isActiveAe(ae)) { if (!isActiveAe(ae)) t.cancel(); return; }
        ConfigurationSection sc = settings().getConfigurationSection("bossbar-scroll");
        String plain = pl.text.expand(settings().getString("bossbar-text", "airdrop code {code} | {status}"),
                Text.ph("code", code, "status", status(), "crates", crates.size()));
        if (sc != null && sc.getBoolean("enabled", true)) {
            int width = Math.max(8, sc.getInt("width", 34));
            String loop = plain + "     ";
            if (plain.length() > width || sc.getBoolean("always-scroll", true)) {
                scrollPos = (scrollPos + 1) % loop.length();
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < width; i++) b.append(loop.charAt((scrollPos + i) % loop.length()));
                plain = b.toString();
            }
            phase += sc.getDouble("gradient-speed", 0.04);
            if (phase > 1) phase = -1;
        }
        ae.bar.name(pl.text.paletteGradient(plain, phase));
    }

    private boolean isActiveAe(ActiveEvent ae) { return pl.events.active(id()) == ae; }

    // ---------- spawning ----------
    private void spawnRandom(String tier) {
        ConfigurationSection sp = settings().getConfigurationSection("spawn");
        if (sp == null) return;
        List<String> worlds = sp.getStringList("worlds");
        if (sp.getString("mode", "AROUND_PLAYER").equalsIgnoreCase("AREA")) {
            ConfigurationSection a = sp.getConfigurationSection("area");
            World w = a == null ? null : Bukkit.getWorld(a.getString("world", "world"));
            if (w == null || a == null) return;
            attempt(w, a.getDouble("center-x"), a.getDouble("center-z"), 0, a.getDouble("radius", 500), tier, 0);
            return;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getWorld().getEnvironment() == World.Environment.NORMAL && (worlds.isEmpty() || worlds.contains(p.getWorld().getName())))
                candidates.add(p);
        if (candidates.isEmpty()) return;
        Player p = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        pl.sched.entity(p, () -> {
            Location l = p.getLocation();
            attempt(l.getWorld(), l.getX(), l.getZ(), sp.getDouble("min-distance", 30), sp.getDouble("max-distance", 120), tier, 0);
        });
    }

    private void attempt(World w, double cx, double cz, double minD, double maxD, String tier, int tries) {
        if (tries >= settings().getInt("spawn.max-attempts", 8)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble(Math.PI * 2);
        double dist = maxD <= minD ? minD : r.nextDouble(minD, maxD);
        final int x = (int) Math.floor(cx + Math.cos(ang) * dist), z = (int) Math.floor(cz + Math.sin(ang) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> pl.sched.region(new Location(w, x, 64, z), () -> {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block b = w.getBlockAt(x, y, z);
            if (settings().getBoolean("spawn.avoid-liquids", true) && b.isLiquid()) {
                attempt(w, cx, cz, minD, maxD, tier, tries + 1);
                return;
            }
            spawnCrate(tier, new Location(w, x + 0.5, y + 1, z + 0.5));
        }));
    }

    /** Must run on the region thread of ground. */
    public void spawnCrate(String tier, Location ground) {
        if (tierSec(tier) == null) return;
        spawned++;
        String c = code.isEmpty() ? generateCode() : code;
        new AirdropCrate(pl, this, tier, ground, c).spawn();
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
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        AirdropCrate c = crates.get(e.getAttacked().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        c.hit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        AirdropCrate c = crates.get(e.getRightClicked().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        c.interact(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (crates.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }
}
