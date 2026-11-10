package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.TileState;
import org.bukkit.Particle;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * The visible disasters of the Natural Disaster event. Everything is vanilla and needs no resource pack:
 * giant item/block display entities (wind charge, magma block, glass) plus particles and sounds.
 * Meteors, the tornado, earthquakes and the laser really destroy terrain, but only in small, shallow chunks (never deeper than
 * hazards.max-depth) so the arena erodes slowly over the whole event instead of one hole reaching bedrock.
 * Geysers place real water/lava and put the ground back afterwards. Display entities are non-persistent and removed on stop.
 * Scheduling: a few slots (hazards.max-active, default 2) cycle through every enabled disaster; each disaster runs continuously for
 * hazards.run-seconds (default 120 = 2m) and its slot shows the time left as "name: 2m" in the action bar, then the next disaster takes over.
 * Every hazard damage (and fall damage) is capped by hazards.max-damage (default 20 = 10 hearts); fall damage has its own cap, hazards.max-fall-damage.
 * Types: wind_charge (giant spinning wind charge that bursts on impact), meteor (falling magma block + crater),
 * lava_geyser, water_geyser (warning ring, then an eruption column that launches players) and wind_storm.
 */
final class DisasterHazards {
    private static final String[] TYPES = {"wind_charge", "meteor", "lava_geyser", "water_geyser", "wind_storm", "tornado", "hurricane", "meteor_shower", "earthquake", "laser_sinkhole", "thunderstorm", "tnt_rain"};

    private final EventCorePlugin pl;
    private final NaturalDisasterEvent ev;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private final Set<Entity> spawned = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sky = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean stopped;
    private final AtomicInteger tornadoes = new AtomicInteger();
    private volatile Component bar;

    /** One of the (default two) lanes of the cycle: it shows one disaster's countdown, launches it, holds while it runs, then draws the next one. */
    private static final class Slot {
        String type;            // disaster this slot is running / waiting to run (null = nothing to show)
        double until;           // while running: when it ends
        double run;             // how long it runs in total
        boolean running;
    }
    private volatile List<Player> roster = List.of();
    private double pauseUntil;                             // while t < this nobody is attacked: a breather so players can fight
    private boolean wasRunning;      // players in the arena right now (refreshed every second)
    private final List<Slot> slots = new ArrayList<>();
    private final List<String> bag = new ArrayList<>();      // shuffled cycle: every enabled disaster shows up once before any repeats

    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("wind_charge", "#7fffd4"), Map.entry("meteor", "#ff8c1a"), Map.entry("lava_geyser", "#ff3030"),
            Map.entry("water_geyser", "#3aa0ff"), Map.entry("wind_storm", "#d8e2dc"), Map.entry("tornado", "#c2a878"),
            Map.entry("hurricane", "#7b6cff"), Map.entry("meteor_shower", "#ffd23f"), Map.entry("earthquake", "#b5651d"),
            Map.entry("laser_sinkhole", "#ff2bd6"), Map.entry("thunderstorm", "#9dff4a"), Map.entry("tnt_rain", "#ff5555"));

    private static String label(String type) {
        return switch (type) {
            case "wind_charge" -> "Wind Charge"; case "meteor" -> "Meteor"; case "lava_geyser" -> "Lava Geyser";
            case "water_geyser" -> "Water Geyser"; case "wind_storm" -> "Wind Storm"; case "tornado" -> "Tornado";
            case "hurricane" -> "Hurricane"; case "meteor_shower" -> "Meteor Shower"; case "earthquake" -> "Earthquake";
            case "laser_sinkhole" -> "Laser"; case "thunderstorm" -> "Thunderstorm"; case "tnt_rain" -> "TNT Rain"; default -> type;
        };
    }

    private static String fmt(double secs) { int i = (int) Math.ceil(secs); return i > 60 ? ((i + 59) / 60) + "m" : Math.max(0, i) + "s"; }

    /** Each disaster has its own colour (hazards.<type>.color overrides the default). */
    private TextColor colorOf(String type, ConfigurationSection c) {
        TextColor col = TextColor.fromHexString(c == null ? "" : c.getString("color", ""));
        if (col == null) col = TextColor.fromHexString(COLORS.getOrDefault(type, "#ffffff"));
        return col == null ? NamedTextColor.WHITE : col;
    }

    /** The action bar line: "hurricane: 2m | meteor: 2m", one coloured small-caps entry per slot, or null when hazards are off. */
    Component incomingBar(double t) { return bar; }

    DisasterHazards(EventCorePlugin pl, NaturalDisasterEvent ev) { this.pl = pl; this.ev = ev; }

    void reset() { meteorHint.set(false); roundLanes = 1; nextGlow = -1; underSince.clear(); stopped = false; slots.clear(); bag.clear(); pauseUntil = 0; wasRunning = false; active.set(0); tornadoes.set(0); bar = null; flung.clear(); pending.clear(); }

    /** Cancels every running effect and removes every display entity. Called when the event stops. */
    void stop() {
        stopped = true;
        for (ScheduledTask t : tasks) { try { t.cancel(); } catch (Throwable ignored) { } }
        tasks.clear();
        boolean direct = !pl.isEnabled();
        for (Entity e : spawned) kill(e, direct);
        spawned.clear();
        for (UUID id : sky) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (direct) { try { skyOff(p); } catch (Throwable ignored) { } }
            else { try { pl.sched.entity(p, () -> skyOff(p)); } catch (Throwable ignored) { } }
        }
        sky.clear();
        active.set(0);
        flung.clear();
        for (Restore r : new ArrayList<>(pending)) {          // geyser fluids: clear the temporary columns (the holes they dug stay)
            pending.remove(r);
            Runnable run = () -> { try { Block b = r.w().getBlockAt(r.x(), r.y(), r.z()); if (b.getType() == Material.WATER || b.getType() == Material.LAVA) b.setType(Material.AIR, false); } catch (Throwable ignored) { } };
            if (direct) run.run(); else { try { pl.sched.region(new Location(r.w(), r.x(), r.y(), r.z()), run); } catch (Throwable ignored) { } }
        }
    }

    private void skyOn(Player p, boolean storm) {
        sky.add(p.getUniqueId());
        p.setPlayerTime(18000L, false);                       // client-side night, only for this player
        if (storm) p.setPlayerWeather(WeatherType.DOWNFALL);
    }

    private void skyOff(Player p) {
        sky.remove(p.getUniqueId());
        p.resetPlayerTime();
        p.resetPlayerWeather();
    }

    private void kill(Entity e, boolean direct) {
        spawned.remove(e);
        if (direct) { try { e.remove(); } catch (Throwable ignored) { } return; }
        try { pl.sched.entity(e, e::remove); } catch (Throwable t) { try { e.remove(); } catch (Throwable ignored) { } }
    }


    // =====================================================================================================
    // shared helpers: damage cap, launching, terrain destruction
    // =====================================================================================================
    private static final class Col {
        final int x, z, stop, delay; int y; boolean done; Material floorFluid;
        Col(int x, int y, int z, int stop, int delay) { this.x = x; this.y = y; this.z = z; this.stop = stop; this.delay = delay; }
    }
    private record Restore(World w, int x, int y, int z, BlockData data) { }

    private final Set<Restore> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> flung = new ConcurrentHashMap<>();

    /** Highest damage one hazard (or one fall) may do, in half-hearts. 12 = 6 hearts. */
    double maxDamage() { return Math.max(1, ev.settings().getDouble("hazards.max-damage", 20)); }

    /** Highest fall damage (tornado / geyser launches), kept lower than direct hits so a launch hurts but does not kill. */
    double maxFallDamage() { double v = ev.settings().getDouble("hazards.max-fall-damage", 0); return v <= 0 ? Double.MAX_VALUE : Math.max(1, v); }   // 0 = no fall damage cap
    private double dmg(double v) { double m = diffD("damage-multiplier", 1.0); return Math.min(v * m, maxDamage() * Math.max(1, m)); }

    /** The chosen difficulty (settings.difficulty: easy | mid | hard) and its preset in settings.difficulties. */
    private ConfigurationSection diff() {
        return ev.settings().getConfigurationSection("difficulties." + ev.settings().getString("difficulty", "mid").toLowerCase(java.util.Locale.ROOT));
    }
    private double diffD(String key, double def) { ConfigurationSection d = diff(); return d == null ? def : d.getDouble(key, def); }

    /** Random size of a tornado / meteor, never shown to players: small, normal or big. */
    private double sizeRoll() {
        ConfigurationSection z = ev.settings().getConfigurationSection("hazards.sizes");
        if (z == null || !z.getBoolean("enabled", true)) return 1.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double big = diffD("big-chance", z.getDouble("big-chance", 0.25)), small = z.getDouble("small-chance", 0.3), x = r.nextDouble();
        if (x < big) return r.nextDouble(z.getDouble("big-min", 1.5), Math.max(z.getDouble("big-min", 1.5) + 0.01, z.getDouble("big-max", 2.0)));
        if (x < big + small) return r.nextDouble(z.getDouble("small-min", 0.5), Math.max(z.getDouble("small-min", 0.5) + 0.01, z.getDouble("small-max", 0.75)));
        return r.nextDouble(0.9, 1.15);
    }
    private int roundLanes = 1;
    private final java.util.concurrent.atomic.AtomicBoolean meteorHint = new java.util.concurrent.atomic.AtomicBoolean();
    private int layersPerTick() { return Math.max(1, ev.settings().getInt("hazards.dig-layers-per-tick", 3)); }

    /** Hard ceiling for how deep ANY disaster may dig below the surface (hazards.max-depth). Keeps the damage in small chunks, never bedrock. */
    private int maxDepth() { return Math.max(2, ev.settings().getInt("hazards.max-depth", 12)); }

    /** The lowest block a column starting at surfaceY may lose: `depth` blocks down, never deeper than hazards.max-depth, never below the world. */
    private int stopY(World w, int surfaceY, int depth) {
        return Math.max(w.getMinHeight() + 1, surfaceY - Math.max(1, Math.min(depth, maxDepth())));
    }

    private static boolean breakable(Material m) {
        return switch (m) {
            case BEDROCK, BARRIER, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, JIGSAW,
                 END_PORTAL, END_PORTAL_FRAME, END_GATEWAY, NETHER_PORTAL, REINFORCED_DEEPSLATE, LIGHT -> false;
            default -> true;
        };
    }

    private long budgetTick = -1;
    private int budgetLeft;

    /** Global block-change budget per server tick (hazards.blocks-per-tick): every destructive effect shares it, so lag can never pile up. */
    private synchronized boolean take() {
        long now = Bukkit.getCurrentTick();
        if (now != budgetTick) { budgetTick = now; budgetLeft = Math.max(20, ev.settings().getInt("hazards.blocks-per-tick", 250)); }
        if (budgetLeft <= 0) return false;
        budgetLeft--;
        return true;
    }

    private boolean displayRoom() { return spawned.size() < Math.max(20, ev.settings().getInt("hazards.max-displays", 150)); }

    private int pc(int n) { return Math.max(1, (int) Math.round(n * Math.max(0.1, Math.min(1.0, ev.settings().getDouble("hazards.particle-density", 0.5))))); }

    private int top(World w, int x, int z) { return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES); }

    /** Columns of a disc, each starting at the surface and digging `depth` blocks down (capped by hazards.max-depth); delay ripples outward. */
    private List<Col> discCols(ActiveEvent ae, World w, double cx, double cz, double radius, double delayPerBlock, int depth) {
        List<Col> out = new ArrayList<>();
        int r = (int) Math.ceil(radius), bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > radius + 0.3) continue;
            int x = bx + dx, z = bz + dz;
            if (!ev.inBounds(ae, x + 0.5, z + 0.5)) continue;
            int sy = top(w, x, z);
            out.add(new Col(x, sy, z, stopY(w, sy, depth), (int) (d * delayPerBlock)));
        }
        return out;
    }

    /** Removes up to `layers` solid blocks from every active column down to its own stop depth, leaving the bare ground as the floor (vents get a small fluid pool). Returns true while work is left. */
    private boolean digStep(ActiveEvent ae, World w, List<Col> cols, int n, int layers, boolean floor) {
        boolean left = false, starved = false;
        int min = w.getMinHeight();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Col c : cols) {
            if (c.done) continue;
            if (n < c.delay) { left = true; continue; }
            if (!ev.inBounds(ae, c.x + 0.5, c.z + 0.5)) { c.done = true; continue; }
            int dug = 0, guard = 0;
            try {
                while (!c.done && dug < layers && guard++ < 400) {
                    if (c.stop >= min && c.y < c.stop) {                     // reached this column's depth: stop, the bare ground stays as the floor
                        if (c.floorFluid != null) {                          // vent pool: two blocks of fluid at the bottom
                            w.getBlockAt(c.x, c.stop, c.z).setType(c.floorFluid, false);
                            w.getBlockAt(c.x, c.stop + 1, c.z).setType(c.floorFluid, false);
                        }
                        c.done = true; break;
                    }
                    Block b = w.getBlockAt(c.x, c.y, c.z);
                    Material m = b.getType();
                    if (m == Material.BEDROCK || c.y < min) { c.done = true; break; }
                    if (!m.isAir() && breakable(m)) { if (!take()) { starved = true; break; } b.setType(Material.AIR, false); dug++; }
                    c.y--;
                }
            } catch (Throwable ex) { c.done = true; }
            if (starved) return true;                                   // out of budget this tick: continue next tick
            if (!c.done) left = true;
        }
        return left;
    }

    /** Carves a bowl/sphere over several ticks through the shared block budget (instead of thousands of blocks in one tick). */
    private void carveSphere(ActiveEvent ae, World w, int cx, int cy, int cz, int r) {
        List<Col> cols = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!ev.inBounds(ae, cx + dx + 0.5, cz + dz + 0.5)) continue;
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r + 1) continue;
            int h = (int) Math.floor(Math.sqrt(r * r + 1 - d2));
            cols.add(new Col(cx + dx, cy + h, cz + dz, cy - h, (int) Math.sqrt(d2)));
        }
        digTask(ae, new Location(w, cx, cy, cz), cols);
    }

    /** Runs digStep on a region timer until the columns are finished. */
    private void digTask(ActiveEvent ae, Location at, List<Col> cols) {
        if (cols.isEmpty()) return;
        int[] n = {0};
        ScheduledTask task = pl.sched.regionTimer(at, t -> {
            boolean go;
            try { go = !stopped && ev.isLive(ae) && digStep(ae, at.getWorld(), cols, n[0]++, layersPerTick(), false); }
            catch (Throwable ex) { go = false; }
            if (!go) { t.cancel(); tasks.remove(t); }
        }, 1, 1);
        tasks.add(task);
    }

    private boolean recentlyFlung(UUID id) { Long u = flung.get(id); return u != null && System.currentTimeMillis() < u; }

    private static double apex(double v0) { double y = 0, v = v0; while (v > 0) { y += v; v = (v - 0.08) * 0.98; } return y; }

    /**
     * Throws a player up to roughly `height` blocks. The client caps a single velocity packet at 3.9 blocks/tick (about 61 blocks
     * of rise), so tall launches keep re-applying the velocity for a few ticks like a jet before letting the player coast.
     */
    private boolean fling(Player p, double height, Vector horizontal, double cooldownSec) {
        UUID id = p.getUniqueId();
        if (recentlyFlung(id) || stopped) return false;
        flung.put(id, System.currentTimeMillis() + (long) (cooldownSec * 1000));
        double vy = 3.9;
        int boost = 1;
        if (height <= 61) { vy = 0.5; while (vy < 3.9 && apex(vy) < height) vy += 0.05; }
        else boost = 1 + (int) Math.ceil((height - 61) / 3.7);
        final double fvy = vy;
        final int fb = boost;
        Vector h = horizontal.clone().setY(0);
        if (h.length() > 3.0) h.normalize().multiply(3.0);
        int[] k = {0};
        ScheduledTask[] self = new ScheduledTask[1];
        self[0] = pl.sched.entityTimer(p, t -> {
            if (stopped || !p.isOnline() || k[0] >= fb || !ev.isMember(id)) { t.cancel(); tasks.remove(t); return; }
            k[0]++;
            p.setVelocity(new Vector(h.getX(), fvy, h.getZ()));
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 6, 0.3, 0.2, 0.3, 0.05);
        }, () -> { }, 1, 1);
        tasks.add(self[0]);
        return true;
    }

    private void spin(BlockDisplay d, float sx, float sy, float sz, float ang) {
        Quaternionf q = new Quaternionf().rotateY(ang);
        Vector3f c = q.transform(new Vector3f(0.5f * sx, 0f, 0.5f * sz));
        d.setInterpolationDuration(3);
        d.setInterpolationDelay(0);
        d.setTransformation(new Transformation(c.negate(), q, new Vector3f(sx, sy, sz), new Quaternionf()));
    }

    private BlockDisplay blockDisplayData(Location l, BlockData data, float s) {
        BlockDisplay d = l.getWorld().spawn(l, BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setPersistent(false);
            e.setViewRange(3f);
            e.setTeleportDuration(2);
            e.setTransformation(new Transformation(new Vector3f(-s / 2f, -s / 2f, -s / 2f), new AxisAngle4f(), new Vector3f(s), new AxisAngle4f()));
        });
        spawned.add(d);
        return d;
    }

    private void restoreAll(List<Restore> list) {
        for (Restore r : list) { pending.remove(r); try { Block b = r.w().getBlockAt(r.x(), r.y(), r.z()); if (b.getType() == Material.WATER || b.getType() == Material.LAVA) b.setType(Material.AIR, false); } catch (Throwable ignored) { } }
    }

    /** Puts a fluid in a block if it is free (air/water/lava/plants) and remembers what was there. */
    private void placeTemp(List<Restore> keep, Block b, Material fluid) {
        Material m = b.getType();
        if (!(m.isAir() || m == Material.WATER || m == Material.LAVA || b.isPassable())) return;
        Restore r = new Restore(b.getWorld(), b.getX(), b.getY(), b.getZ(), b.getBlockData());
        pending.add(r); keep.add(r);
        b.setType(fluid, false);
    }

    // =====================================================================================================
    // scheduling
    // =====================================================================================================
    private double nextGlow = -1;
    private final Map<UUID, Long> underSince = new ConcurrentHashMap<>();
    private long lastUnder;

    /** Anybody who stays underground (cave, tunnel, mine) for more than after-seconds glows until they come up. */
    private void undergroundGlow(ConfigurationSection h, double t, double start, List<Player> alive) {
        ConfigurationSection g = h.getConfigurationSection("underground-glow");
        if (g == null || !g.getBoolean("enabled", true) || t < start) { underSince.clear(); return; }
        long now = System.currentTimeMillis();
        if (now - lastUnder < 1000) return;
        lastUnder = now;
        long after = (long) (Math.max(5, g.getDouble("after-seconds", 60)) * 1000);
        int depth = Math.max(2, g.getInt("depth", 5));
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            pl.sched.entity(p, () -> {
                UUID id = p.getUniqueId();
                if (!underground(p.getWorld(), p.getLocation(), depth)) { underSince.remove(id); return; }
                long since = underSince.computeIfAbsent(id, k -> now);
                if (now - since < after) return;
                boolean first = !p.hasPotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 60, 0, false, false, false));   // refreshed every second
                if (first) p.sendMessage(pl.msg("disaster.underground-glow", "seconds", after / 1000));
            });
        }
    }

    /** Random glow: every few minutes ALL players inside the border glow for a few seconds, so nobody can hide. */
    private void glowTick(ConfigurationSection h, double t, double start, List<Player> alive) {
        ConfigurationSection g = h.getConfigurationSection("glow");
        if (g == null || !g.getBoolean("enabled", true) || t < start || alive.isEmpty()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lo = Math.max(10, g.getDouble("min-interval-seconds", 90)), hi = Math.max(lo, g.getDouble("max-interval-seconds", 180));
        if (nextGlow < 0) { nextGlow = t + r.nextDouble(lo, hi); return; }
        if (t < nextGlow) return;
        nextGlow = t + r.nextDouble(lo, hi);
        int secs = (int) Math.max(1, g.getDouble("duration-seconds", 10));
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            pl.sched.entity(p, () -> {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, secs * 20, 0, false, false, false));
                p.sendMessage(pl.msg("disaster.glow", "seconds", secs));
                p.playSound(p.getLocation(), "block.beacon.power_select", 0.7f, 1.4f);
            });
        }
    }

    void tick(ActiveEvent ae, double t, List<Player> alive) {
        ConfigurationSection h = ev.settings().getConfigurationSection("hazards");
        if (h == null || !h.getBoolean("enabled", true) || stopped) { bar = null; return; }
        double start = h.getDouble("start-after-seconds", 10), announce = h.getDouble("announce-seconds", 10);
        double globalRun = Math.max(5, h.getDouble("run-seconds", 5));        // "2m": how long one disaster keeps going
        int lanes = Math.max(1, Math.min(4, h.getInt("max-active", 2)));         // only this many disasters exist at the same time
        double quiet = Math.max(0, h.getDouble("short-before-end-minutes", 10)) * 60;         // in the last N minutes disasters are only short
        boolean late = quiet > 0 && ae.totalSec - t <= quiet;
        double lateRun = Math.max(0, h.getDouble("late-run-seconds", 5));                      // 0 = no disasters at all in that time
        boolean live = t >= start && !alive.isEmpty() && !(late && lateRun <= 0);
        roster = alive;
        glowTick(h, t, start, alive);
        undergroundGlow(h, t, start, alive);

        while (slots.size() < lanes) slots.add(new Slot());
        while (slots.size() > lanes) slots.remove(slots.size() - 1);

        for (Slot s : slots) if (s.running && t >= s.until) { s.running = false; s.type = null; }   // the disaster is over: this slot draws the next one of the cycle
        boolean any = false;
        for (Slot s : slots) if (s.running) any = true;
        if (wasRunning && !any && ThreadLocalRandom.current().nextDouble() < Math.max(0, Math.min(1, diffD("pause-chance", h.getDouble("pause-chance", 0.4))))) {
            double lo = Math.max(5, h.getDouble("pause-min-seconds", 25)), hi = Math.max(lo, h.getDouble("pause-max-seconds", 45));
            pauseUntil = t + ThreadLocalRandom.current().nextDouble(lo, hi);                  // sometimes a round ends in a pause: no disasters, players can fight
        }
        wasRunning = any;
        boolean paused = t < pauseUntil;
        boolean roundStart = !any && !paused;                                                   // a new round: ALL slots are idle
        if (roundStart) {                                                                       // sometimes one disaster, sometimes two at once
            double two = diffD("two-at-once-chance", h.getDouble("two-at-once-chance", 0.5));
            roundLanes = lanes >= 2 && ThreadLocalRandom.current().nextDouble() < two ? 2 : 1;
        }

        for (int si = 0; si < slots.size(); si++) {
            Slot s = slots.get(si);
            if (si >= roundLanes) { if (!s.running) s.type = null; continue; }
            if (s.type == null) {
                s.type = pick(h, s);
                if (s.type == null) continue;                                                  // nothing enabled
            }
            if (live && !s.running && !paused && roundStart) {
                ConfigurationSection c = h.getConfigurationSection(s.type);
                double run = late ? Math.max(5, lateRun) : Math.max(5, c == null ? globalRun : c.getDouble("run-seconds", globalRun));
                s.running = true;
                s.until = t + run;
                s.run = run;
                begin(ae, s.type, c, alive, run);
            }
        }

        if (late && lateRun <= 0 && !any) { bar = null; return; }                                              // final minutes: calm, nothing in the action bar
        if (paused && !any) {                                                                  // breather: the bar counts down the pause instead
            bar = Component.text(pl.text.sc("pause: " + fmt(pauseUntil - t)), net.kyori.adventure.text.format.TextColor.color(0xFF5555));
            return;
        }
        Component b = Component.empty();
        boolean first = true;
        for (Slot s : slots) {
            if (s.type == null) continue;
            ConfigurationSection c = h.getConfigurationSection(s.type);
            double left = s.running ? s.until - t : Math.max(5, c == null ? globalRun : c.getDouble("run-seconds", globalRun));
            if (!first) b = b.append(Component.text(" | ", net.kyori.adventure.text.format.TextColor.color(0x555555)));
            first = false;
            Component entry = Component.text(pl.text.sc(label(s.type).toLowerCase(java.util.Locale.ROOT) + ": " + fmt(left)), colorOf(s.type, c));
            b = b.append(s.running && left <= announce ? entry.decorate(TextDecoration.BOLD) : entry);   // bold when it is about to end
        }
        bar = first ? null : b;
    }

    /** Draws the next disaster of the cycle: a shuffled bag, so every enabled disaster comes once before any repeats and the two slots never show the same one. */
    private String pick(ConfigurationSection h, Slot self) {
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < bag.size(); i++) {
                String type = bag.get(i);
                boolean taken = false;
                for (Slot o : slots) if (o != self && type.equals(o.type)) taken = true;
                if (taken || (type.equals("tornado") && tornadoes.get() > 0)) continue;
                bag.remove(i);
                return type;
            }
            bag.clear();                                                                       // bag used up: shuffle every enabled disaster back in
            for (String type : TYPES) {
                ConfigurationSection c = h.getConfigurationSection(type);
                if (c != null && c.getBoolean("enabled", true)) bag.add(type);
            }
            java.util.Collections.shuffle(bag);
        }
        return null;
    }

    /** Starts one disaster for `run` seconds. Storms and the tornado run on their own that long; the rest keep striking (meteors, geysers, ...) until the time is up. */
    private void begin(ActiveEvent ae, String type, ConfigurationSection c, List<Player> alive, double run) {
        switch (type) {
            case "wind_storm", "hurricane", "thunderstorm", "meteor_shower", "tornado" ->
                    launch(ae, type, c, alive.get(ThreadLocalRandom.current().nextInt(alive.size())), alive, run);
            default -> repeat(ae, type, c, run);
        }
    }

    /** Launches a single-shot disaster near a random player, then again after a random pause (min-seconds .. max-seconds), until `run` seconds are over. */
    private void repeat(ActiveEvent ae, String type, ConfigurationSection c, double run) {
        double lo = Math.max(2, c == null ? 10 : c.getDouble("min-seconds", 10));
        double hi = Math.max(lo, c == null ? 15 : c.getDouble("max-seconds", 15));
        long total = (long) (run * 20);
        long[] n = {0}, wait = {40};                                                  // the first one comes 2 seconds in
        ScheduledTask task = pl.sched.globalTimer(t -> {
            if (stopped || !ev.isLive(ae) || n[0] >= total) { t.cancel(); tasks.remove(t); return; }
            n[0] += 10;
            wait[0] -= 10;
            if (wait[0] > 0) return;
            wait[0] = (long) (ThreadLocalRandom.current().nextDouble(lo, hi) * 20);
            List<Player> r = roster;
            if (r.isEmpty()) return;
            launch(ae, type, c, r.get(ThreadLocalRandom.current().nextInt(r.size())), r, run);
        }, 1, 10);
        tasks.add(task);
    }

    private void launch(ActiveEvent ae, String type, ConfigurationSection c, Player target, List<Player> alive, double run) {
        pl.sched.entity(target, () -> {
            if (!ev.isLive(ae)) return;
            Location base = target.getLocation();
            double off = type.endsWith("geyser") ? 10 : type.equals("tornado") ? 25 : type.equals("earthquake") ? 15 : type.equals("laser_sinkhole") ? 8 : 5;
            Location spot = type.equals("tornado") ? randomSpot(base.getWorld(), ae) : type.endsWith("geyser") ? geyserSpot(base, c, ae)
                    : type.equals("laser_sinkhole") && ThreadLocalRandom.current().nextDouble() < c.getDouble("aim-chance", 0.7) ? base.clone() : chooseSpot(base, off, ae);   // lasers are often aimed straight at a player (the warning ring and thin beam show first)   // the tornado never starts at a player
            if (type.equals("wind_storm")) { windStorm(ae, c, alive, run); return; }
            if (type.equals("hurricane")) { hurricane(ae, c, alive, run); return; }
            if (type.equals("meteor_shower")) { meteorShower(ae, c, alive, run); return; }
            if (type.equals("thunderstorm")) { thunderstorm(ae, c, alive, run); return; }
            if (type.equals("tnt_rain")) { tntRain(ae, c, alive, run); return; }
            pl.sched.region(spot, () -> {
                if (!ev.isLive(ae) || stopped) return;
                try {
                    switch (type) {
                        case "wind_charge" -> windCharge(ae, c, spot);
                        case "meteor" -> meteor(ae, c, spot, true);
                        case "tornado" -> tornado(ae, c, spot, run);
                        case "earthquake" -> earthquake(ae, c, spot);
                        case "laser_sinkhole" -> laser(ae, c, spot);
                        case "lava_geyser" -> geyser(ae, c, spot, true);
                        default -> geyser(ae, c, spot, false);
                    }
                } catch (Throwable ex) { pl.getLogger().warning("Natural Disaster hazard " + type + " failed: " + ex); }
            });
        });
    }

    /** Chance (hazards.track-chance, default 25%) that a disaster is aimed at a player; otherwise it just drops somewhere in the arena on its own. */
    private double trackChance() { return Math.max(0, Math.min(1, ev.settings().getDouble("hazards.track-chance", 0.25))); }

    /** Geysers do not erupt on the player by default: they open somewhere around (min-distance .. max-distance blocks away),
     *  and only with below-chance directly under the player (the warning ring still shows first). */
    private Location geyserSpot(Location base, ConfigurationSection c, ActiveEvent ae) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lim = Math.max(3, ev.radiusNow(ae) - 3);
        if (c != null && r.nextDouble() < Math.max(0, Math.min(1, c.getDouble("below-chance", 0.15))))
            return new Location(base.getWorld(), Math.floor(base.getX()) + 0.5, base.getY(), Math.floor(base.getZ()) + 0.5);
        double min = c == null ? 10 : c.getDouble("min-distance", 10), max = Math.max(min + 1, c == null ? 35 : c.getDouble("max-distance", 35));
        double x = base.getX(), z = base.getZ();
        for (int i = 0; i < 8; i++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(min, max);
            x = Math.max(ev.cx() - lim, Math.min(ev.cx() + lim, base.getX() + Math.cos(a) * d));
            z = Math.max(ev.cz() - lim, Math.min(ev.cz() + lim, base.getZ() + Math.sin(a) * d));
            if (Math.hypot(x - base.getX(), z - base.getZ()) >= min) break;      // clamped against the border: try another direction
        }
        return new Location(base.getWorld(), Math.floor(x) + 0.5, base.getY(), Math.floor(z) + 0.5);
    }

    /** A random place inside the current border (used by the tornado). */
    private Location randomSpot(World w, ActiveEvent ae) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lim = Math.max(4, ev.radiusNow(ae) - 6);
        return new Location(w, Math.floor(ev.cx() + r.nextDouble(-lim, lim)) + 0.5, 64, Math.floor(ev.cz() + r.nextDouble(-lim, lim)) + 0.5);
    }

    /** Centre of a sweeping wind front: it travels back and forth along one random line through the arena at `bps` blocks/second.
     *  Returns {x, z, direction sign}. Pure function of time, so every player's own scheduler sees the same front (Folia-safe). */
    private double[] sweepCentre(ActiveEvent ae, double ang, double off, double bps, long t0) {
        double lim = Math.max(6, ev.radiusNow(ae) - 4), len = 2 * lim;
        double s = ((System.currentTimeMillis() - t0) / 1000.0 * bps) % (2 * len);
        boolean fwd = s < len;
        double along = Math.max(-lim, Math.min(lim, -lim + (fwd ? s : 2 * len - s)));
        double perp = off * lim * 0.7;
        double x = ev.cx() + Math.cos(ang) * along - Math.sin(ang) * perp, z = ev.cz() + Math.sin(ang) * along + Math.cos(ang) * perp;
        return new double[]{x, z, fwd ? 1 : -1};
    }

    /** Only sometimes near the player; most of the time a random place in the arena (kept within ~45 blocks of the player so the chunk is loaded). */
    private Location chooseSpot(Location base, double off, ActiveEvent ae) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < trackChance()) return spot(base, off, ae);
        double lim = Math.max(3, ev.radiusNow(ae) - 3);
        double x = Math.max(ev.cx() - lim, Math.min(ev.cx() + lim, base.getX() + r.nextDouble(-45, 45)));
        double z = Math.max(ev.cz() - lim, Math.min(ev.cz() + lim, base.getZ() + r.nextDouble(-45, 45)));
        return new Location(base.getWorld(), Math.floor(x) + 0.5, base.getY(), Math.floor(z) + 0.5);
    }

    /** A random spot near the player, kept inside the current border. */
    private Location spot(Location base, double off, ActiveEvent ae) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lim = Math.max(3, ev.radiusNow(ae) - 3);
        double x = Math.max(ev.cx() - lim, Math.min(ev.cx() + lim, base.getX() + r.nextDouble(-off, off)));
        double z = Math.max(ev.cz() - lim, Math.min(ev.cz() + lim, base.getZ() + r.nextDouble(-off, off)));
        return new Location(base.getWorld(), Math.floor(x) + 0.5, base.getY(), Math.floor(z) + 0.5);
    }

    /** Region timer: step(n) runs every period ticks and returns false when the effect is over; done always runs after. */
    private void every(Location at, ActiveEvent ae, long period, IntPredicate step, Runnable done) {
        active.incrementAndGet();
        int[] n = {0};
        ScheduledTask task = pl.sched.regionTimer(at, t -> {
            boolean go;
            try { go = !stopped && ev.isLive(ae) && step.test(n[0]++); }
            catch (Throwable ex) { go = false; pl.getLogger().warning("Natural Disaster effect failed: " + ex); }
            if (!go) {
                t.cancel();
                tasks.remove(t);
                active.decrementAndGet();
                try { done.run(); } catch (Throwable ignored) { }
            }
        }, 1, period);
        tasks.add(task);
    }

    // =====================================================================================================
    // building blocks
    // =====================================================================================================
    private ItemDisplay itemDisplay(Location l, Material m, float scale) {
        ItemDisplay d = l.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(m));
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.FIXED);
            e.setInterpolationDuration(2);
            e.setTeleportDuration(2);
            e.setViewRange(2f);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale), new AxisAngle4f()));
        });
        spawned.add(d);
        return d;
    }

    private BlockDisplay blockDisplay(Location l, Material m, float sx, float sy, float sz) {
        BlockDisplay d = l.getWorld().spawn(l, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setInterpolationDuration(6);
            e.setViewRange(2f);
            e.setTransformation(new Transformation(new Vector3f(-sx / 2f, 0f, -sz / 2f), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f()));
        });
        spawned.add(d);
        return d;
    }

    /** Transformation of a beam of `width` x `height` pointing along `dir` (null = straight up), pivoting on its bottom end. */
    private Transformation beamTf(float width, float height, org.joml.Vector3f dir) {
        org.joml.Quaternionf q = new org.joml.Quaternionf();
        if (dir != null) q.rotationTo(0f, 1f, 0f, dir.x, dir.y, dir.z);
        Vector3f tr = new Vector3f(-width / 2f, 0f, -width / 2f).rotate(q);                // keep the beam centred on its axis after rotating
        return new Transformation(tr, q, new Vector3f(width, height, width), new org.joml.Quaternionf());
    }

    /** One full-bright block display stretched into a beam (the laser: red concrete core + red glass shell). */
    private BlockDisplay beam(Location l, Material m, float width, float height, org.joml.Vector3f dir) {
        BlockDisplay d = l.getWorld().spawn(l, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));        // glows even in the dark / under a storm sky
            e.setViewRange(10f);
            e.setDisplayWidth(Math.max(width * 2f + 8f, height * 2f));                // big culling box so the long beam never pops out of view
            e.setDisplayHeight(Math.max(height + 4f, height * 2f));
            e.setInterpolationDuration(3);
            e.setTransformation(beamTf(width, height, dir));
        });
        spawned.add(d);
        return d;
    }

    /** Smoothly changes the width of a beam over `ticks`. */
    private void beamWidth(BlockDisplay d, float width, float height, int ticks, org.joml.Vector3f dir) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(beamTf(width, height, dir));
    }

    /** One flying block of laser debris (magma / red concrete / red glass), moved client-side with display interpolation. */
    private static final class Deb {
        BlockDisplay d; double x, y, z, vx, vy, vz; float sc, spin, rot; int age, life;
    }

    private static final Material[] DEBRIS = {Material.MAGMA_BLOCK, Material.MAGMA_BLOCK, Material.RED_CONCRETE, Material.RED_CONCRETE, Material.RED_STAINED_GLASS};

    private Transformation debTf(Deb b) {
        org.joml.Quaternionf q = new org.joml.Quaternionf().rotateAxis(b.rot, 0.577f, 0.577f, 0.577f);
        return new Transformation(new Vector3f((float) b.x - b.sc / 2f, (float) b.y, (float) b.z - b.sc / 2f), q, new Vector3f(b.sc), new org.joml.Quaternionf());
    }

    /** Blocks blast out of the impact point and tumble through the air. */
    private void debrisBurst(World w, Location at, List<Deb> list, int count, double power) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count && list.size() < 90; i++) {
            Deb b = new Deb();
            double a = r.nextDouble(Math.PI * 2), h = r.nextDouble(0.15, 0.55) * power;
            b.vx = Math.cos(a) * h; b.vz = Math.sin(a) * h; b.vy = r.nextDouble(0.55, 1.1) * Math.sqrt(power);
            b.sc = (float) r.nextDouble(0.4, 1.0); b.spin = (float) r.nextDouble(-0.5, 0.5); b.life = r.nextInt(30, 55);
            Material m = DEBRIS[r.nextInt(DEBRIS.length)];
            b.d = w.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(m.createBlockData());
                e.setPersistent(false);
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setViewRange(6f);
                e.setInterpolationDuration(1);
                e.setTransformation(debTf(b));
            });
            spawned.add(b.d);
            list.add(b);
        }
    }

    /** Moves every debris block one tick (gravity), removes the ones that landed or timed out. */
    private void debrisTick(World w, Location at, List<Deb> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Deb b = list.get(i);
            b.age++; b.x += b.vx; b.y += b.vy; b.z += b.vz; b.vy -= 0.075; b.rot += b.spin;
            if (b.age > b.life || b.y < -1.0 || !b.d.isValid()) {
                if (b.d.isValid()) { w.spawnParticle(Particle.LAVA, at.getX() + b.x, at.getY() + Math.max(0, b.y), at.getZ() + b.z, 1); b.d.remove(); }
                list.remove(i);
                continue;
            }
            b.d.setInterpolationDelay(0);
            b.d.setInterpolationDuration(1);
            b.d.setTransformation(debTf(b));
        }
    }

    /** Distance from point (px,py,pz) to the segment a-b. */
    private static double segDist(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 <= 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2));
        return Math.sqrt(Math.pow(px - (ax + dx * t), 2) + Math.pow(py - (ay + dy * t), 2) + Math.pow(pz - (az + dz * t), 2));
    }

    private void ring(World w, double x, double y, double z, double r, Color col) {
        Particle.DustOptions o = new Particle.DustOptions(col, 1.4f);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            w.spawnParticle(Particle.DUST, x + Math.cos(a) * r, y + 0.15, z + Math.sin(a) * r, 1, 0, 0, 0, 0, o);
        }
    }

    private void near(Location c, double r, double halfHeight, Consumer<Player> fn) {
        for (Player p : c.getWorld().getPlayers()) {                      // few players: far cheaper than scanning every nearby entity
            if (p.getGameMode() == GameMode.SPECTATOR || !ev.isMember(p.getUniqueId())) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getX() - c.getX()) > r || Math.abs(l.getZ() - c.getZ()) > r || Math.abs(l.getY() - c.getY()) > halfHeight) continue;
            fn.accept(p);
        }
    }

    private int ground(World w, Location l) { return w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1; }

    private void say(String path, Map<String, Object> ph) { ev.tellMembers(path, ph); }

    /** A short hint line for the action bar (no prefix), straight from messages.yml. */
    private Component hint(String path) { return pl.text.render(pl.fx.msg(pl.cfg.messages(), path).tpl(), Map.of()); }

    /** True when the player is well below the surface (a cave or tunnel). Reads outside the region are treated as "not underground". */
    private boolean underground(World w, Location l, int depth) {
        try { return l.getY() < top(w, l.getBlockX(), l.getBlockZ()) - depth; }
        catch (Throwable t) { return false; }
    }

    // =====================================================================================================
    // wind charge: a giant spinning wind charge falls and bursts, throwing players away
    // =====================================================================================================
    private void windCharge(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), fall = c.getDouble("fall-height", 35), radius = c.getDouble("radius", 5);
        float scale = (float) c.getDouble("scale", 6);
        ItemDisplay d = itemDisplay(new Location(w, x, gy + fall, z), Material.WIND_CHARGE, scale);
        say("disaster.hazard-wind-charge", Text.ph());
        w.playSound(new Location(w, x, gy + 10, z), "entity.wind_charge.throw", 3f, 0.6f);
        every(d.getLocation(), ae, 1, n -> {
            double y = gy + fall - n * 1.1;
            if (y <= gy + scale * 0.4) { windBurst(ae, w, x, gy, z, radius, dmg(c.getDouble("damage", 4)), c.getDouble("knockback", 1.6), c.getBoolean("destroy-terrain", true) ? c.getInt("crater-radius", 3) : 0); return false; }
            d.teleportAsync(new Location(w, x, y, z));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(n * 0.45f, 0f, 1f, 0f), new Vector3f(scale), new AxisAngle4f()));
            w.spawnParticle(Particle.CLOUD, x, y, z, 6, scale * 0.15, scale * 0.15, scale * 0.15, 0.02);
            w.spawnParticle(Particle.GUST, x, y, z, 1, scale * 0.2, scale * 0.2, scale * 0.2, 0);
            if (n % 4 == 0) ring(w, x, gy, z, radius, Color.fromRGB(0xe8c670));       // shows where it lands
            return true;
        }, () -> kill(d, false));
    }

    private void windBurst(ActiveEvent ae, World w, double x, int gy, double z, double radius, double dmg, double kb, int crater) {
        Location c = new Location(w, x, gy + 0.5, z);
        w.spawnParticle(Particle.GUST_EMITTER_LARGE, c, 1);
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI / 18;
            w.spawnParticle(Particle.CLOUD, x, gy + 0.3, z, 0, Math.cos(a), 0.15, Math.sin(a), 0.9);
        }
        w.playSound(c, "entity.wind_charge.wind_burst", 3f, 0.8f);
        if (crater > 0) carveSphere(ae, w, (int) Math.floor(x), gy - 1, (int) Math.floor(z), Math.min(crater, 8));
        near(c, radius, radius, p -> {
            Vector dir = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
            p.setVelocity(dir.normalize().multiply(kb).setY(0.8));
            if (dmg > 0) p.damage(dmg);
        });
    }

    // =====================================================================================================
    // meteor: a giant magma block falls and blows a real (shallow) crater into the ground with a small pit in the middle
    // =====================================================================================================

    private void meteor(ActiveEvent ae, ConfigurationSection c, Location spot, boolean announce) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        final double sz = sizeRoll();                                   // small, normal or big: not announced
        double x = spot.getX(), z = spot.getZ(), radius = c.getDouble("radius", 6) * sz, fall = 40;
        float s = (float) (c.getDouble("scale", 4) * sz);
        int craterTicks = (int) (c.getDouble("crater-seconds", 8) * 20), fallTicks = (int) Math.ceil(fall / 1.3);
        if (meteorHint.compareAndSet(false, true)) say("disaster.hint-meteor", Text.ph());      // once per round: "go underground"
        BlockDisplay rock = blockDisplay(new Location(w, x, gy + fall, z), Material.MAGMA_BLOCK, s, s, s);
        rock.setInterpolationDuration(2);
        rock.setTeleportDuration(2);
        List<Col> shaft = new ArrayList<>();
        if (announce) say("disaster.hazard-meteor", Text.ph());
        w.playSound(new Location(w, x, gy + 10, z), "entity.blaze.shoot", 3f, 0.5f);
        every(rock.getLocation(), ae, 1, n -> {
            if (n < fallTicks) {
                double y = gy + fall - n * 1.3;
                rock.teleportAsync(new Location(w, x, y, z));
                float a = n * 0.15f;
                rock.setTransformation(new Transformation(new Vector3f(-s / 2f, 0f, -s / 2f), new AxisAngle4f(a, 1f, 0f, 1f), new Vector3f(s), new AxisAngle4f()));
                w.spawnParticle(Particle.FLAME, x, y + s / 2, z, 8, s * 0.3, s * 0.3, s * 0.3, 0.02);
                w.spawnParticle(Particle.LARGE_SMOKE, x, y + s, z, 4, s * 0.25, s * 0.25, s * 0.25, 0.01);
                w.spawnParticle(Particle.DUST, x, y + s / 2, z, 8, s * 0.3, s * 0.3, s * 0.3, 0, new Particle.DustOptions(Color.fromRGB(0xff4a1a), 4f));
                w.spawnParticle(Particle.END_ROD, x, y + s / 2, z, 2, s * 0.15, s * 0.15, s * 0.15, 0.01);   // white glowing core
                if (n % 4 == 0) ring(w, x, gy, z, radius, Color.fromRGB(0xff5a1f));
                return true;
            }
            if (n == fallTicks) {
                rock.remove();
                Location at = new Location(w, x, gy + 0.5, z);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, at, sz >= 1.4 ? 10 : sz <= 0.8 ? 1 : 3);
                w.spawnParticle(Particle.LAVA, at, 60, radius * 0.5, 0.5, radius * 0.5, 0);
                w.playSound(at, "entity.generic.explode", (float) Math.min(6, 3 + sz), 0.5f / (float) Math.max(0.6, sz));
                near(at, radius + 3, radius + 3, p -> {
                    double shelter = underground(w, p.getLocation(), c.getInt("shelter-depth", 4)) ? c.getDouble("shelter-multiplier", 0.1) : 1.0;   // underground = protected
                    double dist = Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ());
                    double falloff = dist <= radius * 0.5 ? 1.0 : Math.max(0.4, 1.0 - (dist - radius * 0.5) / (radius + 3));     // a direct hit hurts the most
                    p.damage(dmg(c.getDouble("damage", 20)) * falloff * Math.sqrt(sz) * shelter);
                    p.setFireTicks((int) (c.getDouble("fire-seconds", 4) * 20));
                    if (shelter >= 1.0) p.setVelocity(p.getLocation().toVector().subtract(at.toVector()).setY(0.4).normalize().multiply(0.9).setY(0.8));
                });
                if (c.getBoolean("destroy-terrain", true)) {                 // real crater, then a small pit in the middle
                    int cr = (int) Math.max(2, Math.min(16, c.contains("crater-radius") ? c.getDouble("crater-radius") * sz : radius));
                    carveSphere(ae, w, (int) Math.floor(x), gy - 1, (int) Math.floor(z), cr);
                    double sr = c.getDouble("shaft-radius", 2) * sz;
                    if (sr > 0) shaft.addAll(discCols(ae, w, x, z, sr, 1, c.getInt("shaft-depth", 5)));     // a small scorched pit, not a shaft to bedrock
                }
                return true;
            }
            int k = n - fallTicks;
            boolean more = !shaft.isEmpty() && digStep(ae, w, shaft, k, layersPerTick(), false);
            if (k % 5 == 0) {
                w.spawnParticle(Particle.FLAME, x, gy + 0.2, z, 10, radius * 0.5, 0.1, radius * 0.5, 0.01);
                w.spawnParticle(Particle.LAVA, x, gy + 0.3, z, 2, radius * 0.4, 0.1, radius * 0.4, 0);
                near(new Location(w, x, gy, z), radius * 0.8, 1.5, p -> p.setFireTicks(40));
            }
            return k < craterTicks || more;
        }, () -> kill(rock, false));
    }

    // =====================================================================================================
    // geysers: the ground blows open into a shallow vent, a real water / lava column launches players; the hole stays for the rest of the event
    // =====================================================================================================

    private static final class Flyer { BlockDisplay d; double x, y, z, vx, vy, vz; int life; }

    /** Tears random surface blocks out of the ground around a point and turns them into flying debris. */
    private void rip(ActiveEvent ae, Location around, double radius, int count, List<Flyer> mine, int cap, double wx, double wz) {
        World w = around.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int k = 0; k < count && mine.size() < cap; k++) {
            if (!displayRoom() || !take()) break;
            double a = r.nextDouble(Math.PI * 2), rr = Math.sqrt(r.nextDouble()) * radius;
            int bx = (int) Math.floor(around.getX() + Math.cos(a) * rr), bz = (int) Math.floor(around.getZ() + Math.sin(a) * rr);
            if (!ev.inBounds(ae, bx + 0.5, bz + 0.5)) continue;
            int by = top(w, bx, bz);
            Block b = w.getBlockAt(bx, by, bz);
            Material m = b.getType();
            if (m.isAir() || !breakable(m) || b.isLiquid() || b.getState(false) instanceof TileState) continue;
            BlockData data = b.getBlockData();
            b.setType(Material.AIR, false);
            Flyer f = new Flyer();
            f.d = blockDisplayData(new Location(w, bx + 0.5, by + 0.5, bz + 0.5), data, (float) r.nextDouble(0.7, 1.1));
            f.x = bx + 0.5; f.y = by + 0.5; f.z = bz + 0.5;
            f.vx = wx * 0.9 + r.nextDouble(-0.25, 0.25); f.vz = wz * 0.9 + r.nextDouble(-0.25, 0.25); f.vy = r.nextDouble(0.35, 0.8);
            f.life = 40 + r.nextInt(50);
            mine.add(f);
        }
    }

    /** Moves the flying debris (called every 2 ticks): it swirls, drifts with the wind and vanishes when its life is over. */
    private void fly(List<Flyer> mine, World w) {
        for (int i = mine.size() - 1; i >= 0; i--) {
            Flyer f = mine.get(i);
            f.life -= 2;
            if (f.life <= 0) { kill(f.d, false); mine.remove(i); continue; }
            double ang = 0.12, nvx = f.vx * Math.cos(ang) - f.vz * Math.sin(ang), nvz = f.vx * Math.sin(ang) + f.vz * Math.cos(ang);
            f.vx = nvx; f.vz = nvz;
            f.vy = f.vy * 0.97 + Math.sin(f.life * 0.3) * 0.05;
            f.x += f.vx * 2; f.y += f.vy * 2; f.z += f.vz * 2;
            f.d.teleportAsync(new Location(w, f.x, f.y, f.z));
        }
    }

    private void clearFlyers(List<Flyer> mine) { for (Flyer f : mine) kill(f.d, false); mine.clear(); }

    private void geyser(ActiveEvent ae, ConfigurationSection c, Location spot, boolean lava) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), radius = c.getDouble("radius", 1.8), height = c.getDouble("height", lava ? 14 : 16);
        int warn = (int) (c.getDouble("warning-seconds", 4) * 20), dur = (int) (c.getDouble("duration-seconds", 6) * 20);
        double launchH = c.getDouble("launch-height", lava ? 40 : 50), dmg = dmg(c.getDouble("damage", lava ? 8 : 0));
        double ventR = c.getDouble("vent-radius", 2.5);
        int ventDepth = c.getInt("vent-depth", lava ? 9 : 7);
        boolean destroy = c.getBoolean("destroy-terrain", true);
        Color col = lava ? Color.fromRGB(0xff5a1f) : Color.fromRGB(0x4aa8ff);
        Material fluid = lava ? Material.LAVA : Material.WATER;
        Location base = new Location(w, x, gy, z);
        List<Restore> placed = new ArrayList<>();
        List<Col> vent = new ArrayList<>();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), cr = (int) Math.ceil(radius), rise = 3;
        boolean[] cleared = {false};
        say(lava ? "disaster.hazard-lava" : "disaster.hazard-water", Text.ph());
        every(base, ae, 1, n -> {
            if (n < warn) {                                            // rumble + ring so players can run
                if (n == 0) w.playSound(base, lava ? "block.lava.pop" : "block.water.ambient", 3f, 0.6f);
                if (n % 5 == 0) {
                    ring(w, x, gy, z, ventR + 0.5, col);
                    w.spawnParticle(lava ? Particle.SMOKE : Particle.BUBBLE_POP, x, gy + 0.2, z, 12, radius * 0.5, 0.1, radius * 0.5, 0.02);
                    if (lava) w.spawnParticle(Particle.DRIPPING_LAVA, x, gy + 0.5, z, 4, radius * 0.5, 0.2, radius * 0.5, 0);
                }
                return true;
            }
            int k = n - warn;
            if (k == 0) {
                w.playSound(base, lava ? "entity.generic.explode" : "entity.generic.splash", 3f, lava ? 1.2f : 0.7f);
                if (destroy) {                                          // the vent: the ground blows open a few blocks deep, with a small pool at the bottom
                    for (Col cc : discCols(ae, w, x, z, ventR, 2, ventDepth)) {
                        cc.floorFluid = fluid;
                        vent.add(cc);
                    }
                    carveSphere(ae, w, bx, gy - 1, bz, (int) Math.ceil(ventR) + 1);
                }
            }
            boolean more = !vent.isEmpty() && digStep(ae, w, vent, k, layersPerTick(), false);
            if (k < dur) {
                int from = gy + k * rise, to = Math.min(gy + (int) height, from + rise);   // the column shoots upward
                for (int y = from; y < to; y++)
                    for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++)
                        if (Math.hypot(dx, dz) <= radius) placeTemp(placed, w.getBlockAt(bx + dx, y, bz + dz), fluid);
                for (int i = 0, pp = pc(6); i < pp; i++) {
                    double y = gy + ThreadLocalRandom.current().nextDouble(height);
                    if (lava) {
                        w.spawnParticle(Particle.FLAME, x, y, z, 3, radius * 0.4, 0.2, radius * 0.4, 0.05);
                        w.spawnParticle(Particle.LAVA, x, y, z, 1, radius * 0.4, 0.2, radius * 0.4, 0);
                    } else {
                        w.spawnParticle(Particle.SPLASH, x, y, z, 6, radius * 0.4, 0.2, radius * 0.4, 0.1);
                        w.spawnParticle(Particle.FALLING_WATER, x, y, z, 3, radius * 0.5, 0.2, radius * 0.5, 0);
                    }
                }
                w.spawnParticle(lava ? Particle.LARGE_SMOKE : Particle.CLOUD, x, gy + height, z, 4, radius * 0.6, 0.3, radius * 0.6, 0.02);
                if (k % 4 == 0) near(new Location(w, x, gy + height / 2, z), radius + 0.5, height / 2, p -> {
                    fling(p, launchH, new Vector(ThreadLocalRandom.current().nextDouble(-0.4, 0.4), 0, ThreadLocalRandom.current().nextDouble(-0.4, 0.4)), 6);
                    if (lava) { if (dmg > 0) p.damage(dmg); p.setFireTicks(100); } else p.setFireTicks(0);
                });
            } else if (!cleared[0]) { cleared[0] = true; restoreAll(placed); }     // the column ends, the vent hole stays
            return k < dur || more;
        }, () -> restoreAll(placed));
    }

    // =====================================================================================================
    // wind storm: a strong gust in one direction with wind streaks, pushing everybody along
    // =====================================================================================================

    private void windStorm(ActiveEvent ae, ConfigurationSection c, List<Player> alive, double run) {
        ThreadLocalRandom R = ThreadLocalRandom.current();
        double ang = R.nextDouble(Math.PI * 2), off = R.nextDouble(-1, 1), strength = c.getDouble("strength", 0.35);
        double sweepR = c.getDouble("sweep-radius", 14), bps = c.getDouble("sweep-speed", 8.0);   // a gust front that sweeps the arena on its own
        long t0 = System.currentTimeMillis();
        int ticks = (int) (run * 20);
        boolean destroy = c.getBoolean("destroy-terrain", true);
        int rip = c.getInt("rip-per-tick", 3), cap = c.getInt("max-blocks-per-player", 35);
        say("disaster.hazard-windstorm", Text.ph());
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            List<Flyer> mine = new ArrayList<>();
            active.incrementAndGet();
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; active.decrementAndGet(); clearFlyers(mine); } };
            ScheduledTask task = pl.sched.entityTimer(p, t -> {
                boolean go = !stopped && ev.isLive(ae) && n[0] < ticks && p.isOnline() && ev.isMember(p.getUniqueId());
                if (!go) { t.cancel(); tasks.remove(t); end.run(); return; }
                n[0] += 2;
                double[] sc = sweepCentre(ae, ang, off, bps, t0);
                double dx = Math.cos(ang) * sc[2], dz = Math.sin(ang) * sc[2];
                Location l = p.getLocation();
                double d = Math.hypot(l.getX() - sc[0], l.getZ() - sc[1]);
                fly(mine, l.getWorld());
                if (d > sweepR) return;                                   // only players the front passes over are swept along
                if (n[0] <= 2) p.getWorld().playSound(l, "entity.wind_charge.wind_burst", 1.5f, 0.5f);
                double f = 1.0 - 0.5 * d / sweepR;
                Vector v = p.getVelocity();
                if (p.getGameMode() != GameMode.SPECTATOR) p.setVelocity(new Vector(v.getX() + dx * strength * 0.3 * f, v.getY(), v.getZ() + dz * strength * 0.3 * f));
                if (destroy) rip(ae, new Location(l.getWorld(), sc[0], l.getY(), sc[1]), sweepR, rip, mine, cap, dx, dz);
                for (int i = 0; i < 3; i++) {
                    double ox = R.nextDouble(-4, 4), oy = R.nextDouble(0, 3), oz = R.nextDouble(-4, 4);
                    p.getWorld().spawnParticle(Particle.CLOUD, sc[0] + ox - dx * 3, l.getY() + oy, sc[1] + oz - dz * 3, 0, dx, 0, dz, 0.6);
                }
                if (n[0] % 10 == 0) p.getWorld().spawnParticle(Particle.GUST, sc[0] + dx * 2, l.getY() + 1, sc[1] + dz * 2, 1, 1, 1, 1, 0);
            }, end, 1, 2);
            tasks.add(task);
        }
    }

    // =====================================================================================================
    // tornado: a tall spinning funnel that roams the arena for a while, rips blocks out, scours a shallow trench and throws players ~100 blocks
    // =====================================================================================================

    private void tornado(ActiveEvent ae, ConfigurationSection c, Location spot, double run) {
        World w = spot.getWorld();
        final double sz = sizeRoll();                                   // small, normal or big tornado: always just "tornado" to the players
        double height = c.getDouble("height", 30) * sz, pull = c.getDouble("pull", 0.45), speed = c.getDouble("speed", 0.2), reach = c.getDouble("radius", 7) * sz;
        double pickR = c.getDouble("pick-radius", 5) * sz, flingH = c.getDouble("launch-height", 100), dmg = dmg(c.getDouble("damage", 4));
        double dur = run;                                              // it roams for the whole run, then fades so the next disaster can take its slot
        int ticks = dur <= 0 ? Integer.MAX_VALUE : (int) (dur * 20), maxBlocks = Math.max(0, c.getInt("max-blocks", 45));
        int pickEvery = Math.max(1, c.getInt("pick-interval-ticks", 3)), picks = Math.max(1, c.getInt("picks-per-interval", 3));
        int digR = Math.max(0, c.getInt("dig-radius", 1)), digDepth = Math.max(1, c.getInt("dig-depth", 1)), layers = 1;
        double cooldown = c.getDouble("launch-cooldown-seconds", 10), catchR = reach * 0.55;
        boolean destroy = c.getBoolean("destroy-terrain", true);
        final int L = 14;
        double[] pos = {spot.getX(), spot.getZ()};
        double[] dir = {ThreadLocalRandom.current().nextDouble(Math.PI * 2)};
        double[] wp = {spot.getX(), spot.getZ()};
        boolean[] chase = {false};
        double chaseChance = c.getBoolean("chase-players", false) ? trackChance() : 0;   // default: roams on its own, never hunts a player
        int[] gy = {ground(w, spot)};
        BlockData[] floorData = {Material.DIRT.createBlockData()};
        List<BlockDisplay> funnel = new ArrayList<>(), debris = new ArrayList<>();
        List<double[]> dp = new ArrayList<>();
        List<Col> trail = new ArrayList<>();
        Set<Long> dug = new java.util.HashSet<>();
        tornadoes.incrementAndGet();
        say("disaster.hazard-tornado", Text.ph());
        every(spot, ae, 1, n -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (n == 0) {
                w.playSound(spot, "entity.ender_dragon.growl", 3f, 0.5f);
                for (int i = 0; i < L; i++) {
                    BlockDisplay d = blockDisplay(new Location(w, pos[0], gy[0] + i * height / L, pos[1]), i % 2 == 0 ? Material.GRAY_STAINED_GLASS : Material.LIGHT_GRAY_STAINED_GLASS, 1, 1, 1);
                    d.setViewRange(4f);
                    d.setTeleportDuration(3);
                    funnel.add(d);
                }
            }
            if (n >= ticks) return false;                                 // only when a finite duration was configured
            Location centre = new Location(w, pos[0], gy[0], pos[1]);
            if (n % 10 == 0) {                                          // follow the ground, hunt the closest player, otherwise roam the arena
                gy[0] = ground(w, centre);
                try { floorData[0] = w.getBlockAt((int) Math.floor(pos[0]), gy[0] - 1, (int) Math.floor(pos[1])).getBlockData(); } catch (Throwable ignored) { }
                if (n % 300 == 0) chase[0] = r.nextDouble() < chaseChance;      // every 15s it decides: chase the nearest player for a while, or just roam
                Player[] best = {null};
                double[] bd = {Double.MAX_VALUE};
                near(centre, 45, 80, p -> { double d = p.getLocation().distanceSquared(centre); if (d < bd[0]) { bd[0] = d; best[0] = p; } });
                double wantX, wantZ;
                if (best[0] != null && chase[0]) { wantX = best[0].getLocation().getX(); wantZ = best[0].getLocation().getZ(); }
                else {
                    double lim0 = Math.max(6, ev.radiusNow(ae) - 6);
                    if (n % 400 == 0 || Math.hypot(wp[0] - pos[0], wp[1] - pos[1]) < 6) {     // pick a new far-away waypoint inside the arena
                        wp[0] = ev.cx() + r.nextDouble(-lim0, lim0);
                        wp[1] = ev.cz() + r.nextDouble(-lim0, lim0);
                    }
                    wantX = wp[0]; wantZ = wp[1];
                }
                double want = Math.atan2(wantZ - pos[1], wantX - pos[0]);
                double diff = Math.atan2(Math.sin(want - dir[0]), Math.cos(want - dir[0]));
                dir[0] += diff * 0.3 + r.nextDouble(-0.15, 0.15);
            }
            double lim = Math.max(4, ev.radiusNow(ae) - 3);
            pos[0] = Math.max(ev.cx() - lim, Math.min(ev.cx() + lim, pos[0] + Math.cos(dir[0]) * speed));
            pos[1] = Math.max(ev.cz() - lim, Math.min(ev.cz() + lim, pos[1] + Math.sin(dir[0]) * speed));

            // funnel: stacked, counter-rotating translucent layers that widen toward the top
            if (n % 3 == 0) {
                double seg = height / L;
                for (int i = 0; i < funnel.size(); i++) {
                    double fy = i * seg, sway = Math.sin(n * 0.05 + i * 0.45) * 0.06 * i;
                    float size = (float) (1.5 + fy * 0.32);
                    spin(funnel.get(i), size, (float) seg + 0.4f, size, (float) (n * 0.2 * (1 + i * 0.04) * (i % 2 == 0 ? 1 : -1)));
                    funnel.get(i).teleportAsync(new Location(w, pos[0] + sway, gy[0] + fy, pos[1] + Math.cos(n * 0.05 + i * 0.45) * 0.06 * i));
                }
            }
            double spinA = n * 0.35;
            if (n % 2 == 0) for (int i = 0, sw = pc(10); i < sw; i++) {                              // dense cloud swirl around the glass
                double fy = r.nextDouble(height), rad = 1.4 + fy * 0.34, a = spinA + fy * 0.45 + i * 0.63;
                w.spawnParticle(Particle.CLOUD, pos[0] + Math.cos(a) * rad, gy[0] + fy, pos[1] + Math.sin(a) * rad, 0, -Math.sin(a), 0.1, Math.cos(a), 0.5);
                if (i % 3 == 0) w.spawnParticle(Particle.LARGE_SMOKE, pos[0] + Math.cos(a) * (rad + 0.6), gy[0] + fy, pos[1] + Math.sin(a) * (rad + 0.6), 0, -Math.sin(a), 0.05, Math.cos(a), 0.3);
            }
            if (n % 2 == 0) w.spawnParticle(Particle.BLOCK, pos[0], gy[0] + 0.3, pos[1], pc(14), 2.2, 0.3, 2.2, 0.1, floorData[0]);   // dust and dirt kicked up at the base
            if (n % 2 == 0) w.spawnParticle(Particle.LARGE_SMOKE, pos[0], gy[0] + 0.5, pos[1], pc(4), 1.8, 0.3, 1.8, 0.02);
            if (n % 30 == 0) w.playSound(new Location(w, pos[0], gy[0], pos[1]), "entity.ender_dragon.flap", 2f, 0.5f);

            // it rips real blocks out of the ground and carries them up
            if (destroy && maxBlocks > 0 && n % pickEvery == 0) {
                for (int k = 0; k < picks && debris.size() < maxBlocks; k++) {
                    if (!displayRoom() || !take()) break;
                    double a = r.nextDouble(Math.PI * 2), rr = Math.sqrt(r.nextDouble()) * pickR;
                    int bx = (int) Math.floor(pos[0] + Math.cos(a) * rr), bz = (int) Math.floor(pos[1] + Math.sin(a) * rr);
                    if (!ev.inBounds(ae, bx + 0.5, bz + 0.5)) continue;
                    int by = top(w, bx, bz);
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir() || !breakable(m) || b.isLiquid() || b.getState(false) instanceof TileState) continue;
                    BlockData data = b.getBlockData();
                    b.setType(Material.AIR, false);
                    BlockDisplay d = blockDisplayData(new Location(w, bx + 0.5, by + 0.5, bz + 0.5), data, (float) r.nextDouble(0.7, 1.1));
                    debris.add(d);
                    dp.add(new double[]{a, 0, r.nextDouble(0, 2.5)});
                }
            }
            // and scours a shallow trench along its path (only a block or two deep)
            if (destroy && digR > 0) {
                if (n % 10 == 0) {
                    int px = (int) Math.floor(pos[0]), pz = (int) Math.floor(pos[1]);
                    for (int dx = -digR; dx <= digR; dx++) for (int dz = -digR; dz <= digR; dz++) {
                        if (Math.hypot(dx, dz) > digR + 0.3) continue;
                        int x = px + dx, z = pz + dz;
                        if (!ev.inBounds(ae, x + 0.5, z + 0.5) || !dug.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                        int sy = top(w, x, z);
                        trail.add(new Col(x, sy, z, stopY(w, sy, digDepth), 0));
                    }
                }
                if (!trail.isEmpty()) { digStep(ae, w, trail, n, layers, false); trail.removeIf(cc -> cc.done); }
            }
            if (n % 2 == 0) for (int i = debris.size() - 1; i >= 0; i--) {   // debris spirals up and is flung away at the top
                double[] d = dp.get(i);
                d[1] += 0.012;
                if (d[1] >= 1) { kill(debris.remove(i), false); dp.remove(i); continue; }
                double a = d[0] + n * 0.25 * (1 + d[2] * 0.2), fy = d[1] * height, rad = 1.3 + fy * 0.32 + d[2];
                debris.get(i).teleportAsync(new Location(w, pos[0] + Math.cos(a) * rad, gy[0] + fy, pos[1] + Math.sin(a) * rad));
            }

            // players: the core grabs them and throws them ~100 blocks, the outer wind spins them around
            if (n % 4 == 0) {
                Location now = new Location(w, pos[0], gy[0] + height / 2, pos[1]);
                near(now, reach, height / 2 + 3, p -> {
                    if (recentlyFlung(p.getUniqueId())) return;           // already flying: leave the trajectory alone
                    Vector rel = p.getLocation().toVector().subtract(new Vector(pos[0], 0, pos[1])).setY(0);
                    if (rel.lengthSquared() < 0.04) rel = new Vector(1, 0, 0);
                    Vector n0 = rel.clone().normalize();
                    if (rel.length() <= catchR) {
                        if (fling(p, flingH, n0.clone().multiply(0.6), cooldown)) {
                            p.getWorld().playSound(p.getLocation(), "entity.wind_charge.wind_burst", 3f, 0.5f);
                            if (dmg > 0) p.damage(dmg);
                            return;
                        }
                    }
                    Vector tang = new Vector(-n0.getZ(), 0, n0.getX()).multiply(pull);
                    Vector in = n0.clone().multiply(-pull * 0.6);
                    p.setVelocity(new Vector(tang.getX() + in.getX(), 0.4, tang.getZ() + in.getZ()));
                });
            }
            return true;
        }, () -> { tornadoes.decrementAndGet(); for (BlockDisplay d : funnel) kill(d, false); for (BlockDisplay d : debris) kill(d, false); });
    }

    // =====================================================================================================
    // earthquake: the ground shakes, then a long fissure (ravine) tears open a few blocks deep
    // =====================================================================================================
    private void earthquake(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        int shake = (int) (c.getDouble("shake-seconds", 6) * 20), len = Math.max(8, c.getInt("length", 44));
        double width = c.getDouble("width", 4), shakeR = c.getDouble("shake-radius", 45);
        int layers = Math.max(layersPerTick(), c.getInt("dig-layers-per-tick", 3)), colsPerTick = Math.max(1, c.getInt("columns-per-tick", 2));
        int maxDepthCfg = Math.max(3, c.getInt("depth", 8));
        double ang = ThreadLocalRandom.current().nextDouble(Math.PI);
        double dx = Math.cos(ang), dz = Math.sin(ang), px = -dz, pz = dx;
        List<Col> cols = new ArrayList<>();
        Location base = new Location(w, spot.getX(), ground(w, spot), spot.getZ());
        boolean underHint = c.getBoolean("underground-hint", true);
        int underDepth = Math.max(2, c.getInt("underground-depth", 5));
        Set<UUID> told = ConcurrentHashMap.newKeySet();                      // each player gets the underground hint once per earthquake
        boolean cave = c.getBoolean("cave-in", true), eject = c.getBoolean("eject-to-surface", true);
        double caveDmg = dmg(c.getDouble("cave-in-damage", 2.0));
        say("disaster.hazard-earthquake", Text.ph());
        every(base, ae, 1, n -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (n < shake) {
                if (n == 0) w.playSound(base, "entity.ravager.roar", 4f, 0.5f);
                if (n % 8 == 0) w.playSound(base, "block.deepslate.break", 4f, 0.4f);
                if (n % 2 == 0) near(base, shakeR, 40, p -> {                         // camera-shake style jitter
                    if (underHint && n >= 10 && !told.contains(p.getUniqueId()) && underground(w, p.getLocation(), underDepth)) {
                        told.add(p.getUniqueId());
                        p.sendMessage(pl.msg("disaster.hint-earthquake"));
                        p.sendActionBar(hint("disaster.hint-earthquake-bar"));
                        p.playSound(p.getLocation(), "block.note_block.bell", 0.8f, 0.8f);
                    }
                    Vector v = p.getVelocity();
                    p.setVelocity(new Vector(v.getX() + r.nextDouble(-0.1, 0.1), Math.max(v.getY(), p.isOnGround() ? 0.12 : v.getY()), v.getZ() + r.nextDouble(-0.1, 0.1)));
                    if (n % 6 == 0) w.spawnParticle(Particle.BLOCK, p.getLocation(), 12, 0.8, 0.1, 0.8, 0.1, p.getLocation().subtract(0, 1, 0).getBlock().getBlockData());
                });
                if (n % 4 == 0) for (int i = 0; i < len; i += 2) {      // cracks appear along the line before it opens
                    double off = Math.sin(i * 0.25) * 2.0;
                    w.spawnParticle(Particle.SMOKE, base.getX() + dx * (i - len / 2.0) + px * off, base.getY() + 0.3, base.getZ() + dz * (i - len / 2.0) + pz * off, 2, 0.4, 0.1, 0.4, 0.01);
                }
                if (cave && n >= 20 && n % 20 == 0) for (Player p : new ArrayList<>(roster)) {      // EVERY player inside the border, not just near the fissure
                    if (!p.isOnline()) continue;
                    pl.sched.entity(p, () -> {
                        if (!ev.isLive(ae) || p.getWorld() != w || !underground(w, p.getLocation(), underDepth)) return;
                        p.damage(caveDmg);                                                          // the roof comes down on anybody hiding in a cave or tunnel
                        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 100, 0, false, false, false));
                        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 100, 1, false, false, false));
                        p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0, 2, 0), 20, 0.6, 0.3, 0.6, 0.1, Material.STONE.createBlockData());
                        if (told.add(p.getUniqueId())) { p.sendMessage(pl.msg("disaster.hint-earthquake")); p.sendActionBar(hint("disaster.hint-earthquake-bar")); }
                    });
                }
                if (eject && n == shake - 1) for (Player p : new ArrayList<>(roster)) {            // the shaking ends: anybody still underground is thrown up to the surface
                    if (!p.isOnline()) continue;
                    pl.sched.entity(p, () -> {
                        if (!ev.isLive(ae) || p.getWorld() != w || !underground(w, p.getLocation(), underDepth)) return;
                        Location l = p.getLocation();
                        Location up = new Location(w, Math.floor(l.getX()) + 0.5, top(w, l.getBlockX(), l.getBlockZ()) + 1, Math.floor(l.getZ()) + 0.5, l.getYaw(), l.getPitch());
                        if (!ev.inBounds(ae, up.getX(), up.getZ())) return;
                        p.teleportAsync(up);
                        w.playSound(up, "entity.generic.explode", 1.2f, 0.7f);
                        p.sendMessage(pl.msg("disaster.earthquake-ejected"));
                    });
                }
                return true;
            }
            int k = n - shake;
            if (k == 0) {                                               // lay out the fissure: wobbling line, narrower at both ends
                Set<Long> seen = new java.util.HashSet<>();
                for (int i = 0; i < len; i++) {
                    double t = i / (double) len, off = Math.sin(i * 0.25) * 2.0, half = Math.max(0.6, width / 2.0 * (1 - Math.abs(t - 0.5) * 1.2));
                    double cx = base.getX() + dx * (i - len / 2.0) + px * off, cz = base.getZ() + dz * (i - len / 2.0) + pz * off;
                    for (double o = -half; o <= half; o += 0.5) {
                        int x = (int) Math.floor(cx + px * o), z = (int) Math.floor(cz + pz * o);
                        if (!seen.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                        if (!ev.inBounds(ae, x + 0.5, z + 0.5)) continue;
                        int sy = top(w, x, z);
                        int depth = Math.max(3, (int) Math.round(maxDepthCfg * (1 - Math.abs(t - 0.5) * 1.2)));      // deepest in the middle, shallow at both ends
                        cols.add(new Col(x, sy, z, stopY(w, sy, depth), i / colsPerTick));
                    }
                }
                w.playSound(base, "entity.generic.explode", 4f, 0.4f);
            }
            boolean more = digStep(ae, w, cols, k, layers, false);
            if (k % 3 == 0) near(base, shakeR, 40, p -> p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation(), 4, 1, 0.2, 1, 0.02));
            return more;
        }, () -> { });
    }

    // =====================================================================================================
    // laser sinkhole: a red orbital beam locks on, burns for a few seconds and leaves a bowl-shaped sinkhole
    // =====================================================================================================
    private void laser(ActiveEvent ae, ConfigurationSection c, Location spot) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double sz = sizeRoll();                                          // small, normal or big laser (never announced)
        boolean diag = rnd.nextDouble() < c.getDouble("diagonal-chance", 0.4);   // a long diagonal beam instead of a straight one from the sky
        double x = spot.getX(), z = spot.getZ(), R = Math.max(2, Math.min(12, c.getDouble("radius", 6) * sz * (diag ? 0.6 : 1.0)));
        double dmg = dmg(c.getDouble("damage", 6.0));                    // 6 = 3 hearts for anybody the beam touches
        int charge = (int) (c.getDouble("charge-seconds", 4) * 20 * (sz < 0.8 ? 0.7 : 1.0)), burn = (int) (c.getDouble("burn-seconds", 4) * 20);
        int rim = Math.max(3, (int) (c.getInt("rim-depth", 6) * Math.min(1.3, Math.max(0.5, sz)))), layers = layersPerTick();
        int coreDepth = Math.max(rim, (int) (c.getInt("core-depth", 10) * Math.min(1.3, Math.max(0.5, sz))));
        double core = R * 0.55;
        // beam geometry: from the ground point G up along `dir` to the top end T
        double ex, ey, ez;
        if (diag) {
            double len = c.getDouble("diagonal-length", 55) * rnd.nextDouble(0.8, 1.3), elev = Math.toRadians(rnd.nextDouble(25, 55)), ang = rnd.nextDouble(Math.PI * 2);
            ex = x + Math.cos(ang) * len * Math.cos(elev); ez = z + Math.sin(ang) * len * Math.cos(elev); ey = Math.min(w.getMaxHeight() - 1, gy + len * Math.sin(elev));
        } else { ex = x; ez = z; ey = Math.min(w.getMaxHeight() - 1, gy + 60); }
        double bl = Math.sqrt((ex - x) * (ex - x) + (ey - gy) * (ey - gy) + (ez - z) * (ez - z));
        org.joml.Vector3f dir = diag ? new org.joml.Vector3f((float) ((ex - x) / bl), (float) ((ey - gy) / bl), (float) ((ez - z) / bl)) : null;
        final double tx = ex, ty = ey, tz = ez;
        Location base = new Location(w, x, gy, z);
        List<Col> cols = new ArrayList<>();
        float beamH = (float) bl, coreW = (float) Math.max(1.0, R * 0.3), shellW = (float) Math.max(2.0, R * 0.6);
        BlockDisplay[] beamCore = {null}, beamGlass = {null};                      // red concrete core + red stained glass shell = the laser beam
        java.util.Set<UUID> hit = ConcurrentHashMap.newKeySet();
        List<Deb> debris = new ArrayList<>();                                        // magma / red concrete / red glass thrown up where the beam lights the ground
        double power = Math.max(0.6, R / 4.0);
        say("disaster.hazard-laser", Text.ph());
        every(base, ae, 1, n -> {
            if (n < charge) {                                           // lock-on: warning ring, a thin beam that gets denser
                if (n == 0) w.playSound(base, "block.beacon.activate", 4f, 1.6f);
                if (n % 4 == 0) ring(w, x, gy, z, R, Color.fromRGB(0xff1a1a));
                if (n % 10 == 0) w.playSound(base, "block.beacon.ambient", 3f, 0.5f + n * 0.01f);
                if (n == 0) {                                           // lock-on: a thin beam
                    beamCore[0] = beam(base, Material.RED_CONCRETE, 0.1f, beamH, dir);
                    beamGlass[0] = beam(base, Material.RED_STAINED_GLASS, 0.35f, beamH, dir);
                }
                if (n == charge / 2) { beamWidth(beamCore[0], 0.25f, beamH, charge / 2, dir); beamWidth(beamGlass[0], 0.9f, beamH, charge / 2, dir); }   // it thickens while it charges
                return true;
            }
            int k = n - charge;
            if (k == 0) {                                               // FIRE: the beam slams out, plan the sinkhole at the ground point
                w.playSound(base, "block.beacon.power_select", 4f, 0.5f);
                w.playSound(base, "entity.generic.explode", 4f, 0.6f);
                beamWidth(beamCore[0], coreW, beamH, 3, dir);
                beamWidth(beamGlass[0], shellW, beamH, 3, dir);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, base, 2);
                debrisBurst(w, base.clone().add(0, 0.6, 0), debris, 32, power);      // the big blast of flying blocks
                for (int dx = (int) -Math.ceil(R); dx <= R; dx++) for (int dz = (int) -Math.ceil(R); dz <= R; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d > R) continue;
                    int bx = (int) Math.floor(x) + dx, bz = (int) Math.floor(z) + dz;
                    if (!ev.inBounds(ae, bx + 0.5, bz + 0.5)) continue;
                    int sy = top(w, bx, bz);
                    int depth = d <= core ? coreDepth : Math.max(2, (int) (rim * (1 - (d - core) / (R - core + 0.001))));     // bowl: deepest in the middle
                    cols.add(new Col(bx, sy, bz, stopY(w, sy, depth), (int) (d * 2)));
                }
            }
            if (k < burn) {
                if (k % 4 == 0) beamWidth(beamGlass[0], shellW * (k % 8 == 0 ? 1.0f : 1.2f), beamH, 4, dir);   // the glass shell pulses
                if (k % 2 == 0) {                                       // ANY player touching the beam (straight or diagonal) takes 3 hearts, once
                    double reach = shellW / 2.0 + 0.6;
                    for (Player p : w.getPlayers()) {
                        if (p.getGameMode() == GameMode.SPECTATOR || !ev.isMember(p.getUniqueId()) || hit.contains(p.getUniqueId())) continue;
                        Location l = p.getLocation();
                        if (segDist(l.getX(), l.getY() + 0.9, l.getZ(), x, gy, z, tx, ty, tz) > reach && Math.hypot(l.getX() - x, l.getZ() - z) > R * 0.6) continue;
                        if (!hit.add(p.getUniqueId())) continue;
                        pl.sched.entity(p, () -> { p.damage(dmg); p.setFireTicks(100); });
                    }
                }
                if (k % 4 == 0) debrisBurst(w, base.clone().add(0, 0.6, 0), debris, 6, power);   // more keep flying while the beam burns
                if (k % 6 == 0) for (double d = 0; d < bl; d += 8) w.spawnParticle(Particle.END_ROD, x + (tx - x) * d / bl, gy + (ty - gy) * d / bl, z + (tz - z) * d / bl, 1, 0.3, 0.3, 0.3, 0.01);
                w.spawnParticle(Particle.FLAME, x, gy + 0.5, z, 12, R * 0.4, 0.3, R * 0.4, 0.05);
                w.spawnParticle(Particle.LAVA, x, gy + 0.5, z, 3, R * 0.4, 0.2, R * 0.4, 0);
                if (k % 10 == 0) w.playSound(base, "block.beacon.ambient", 4f, 0.5f);
            }
            if (k == burn) { beamWidth(beamCore[0], 0.05f, beamH, 12, dir); beamWidth(beamGlass[0], 0.05f, beamH, 12, dir); }   // the beam fades out
            debrisTick(w, base, debris);
            boolean more = digStep(ae, w, cols, k, layers, false);      // the ground melts away from the centre outward
            return k < burn + 14 || more || !debris.isEmpty();
        }, () -> { for (BlockDisplay b : new BlockDisplay[]{beamCore[0], beamGlass[0]}) if (b != null) b.remove(); for (Deb b : debris) if (b.d != null) b.d.remove(); });
    }

    // =====================================================================================================
    // hurricane: night, rain and a rotating spiral of wind that shoves everybody around
    // =====================================================================================================

    private void hurricane(ActiveEvent ae, ConfigurationSection c, List<Player> alive, double run) {
        ThreadLocalRandom R = ThreadLocalRandom.current();
        int ticks = (int) (run * 20);
        double strength = c.getDouble("strength", 0.45);
        double sweepR = c.getDouble("sweep-radius", 18), bps = c.getDouble("sweep-speed", 6.0);   // the eye roams the arena on its own
        double ang = R.nextDouble(Math.PI * 2), off = R.nextDouble(-1, 1);
        long t0 = System.currentTimeMillis();
        boolean destroy = c.getBoolean("destroy-terrain", true);
        int rip = c.getInt("rip-per-tick", 4), cap = c.getInt("max-blocks-per-player", 45);
        say("disaster.hazard-hurricane", Text.ph());
        say("disaster.hint-hurricane", Text.ph());
        double highY = c.getDouble("high-altitude", 30), highMul = c.getDouble("high-multiplier", 3.0), highDmg = dmg(c.getDouble("high-damage", 2.0));
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            List<Flyer> mine = new ArrayList<>();
            active.incrementAndGet();
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; active.decrementAndGet(); clearFlyers(mine); try { skyOff(p); } catch (Throwable ignored) { } } };
            ScheduledTask task = pl.sched.entityTimer(p, t -> {
                boolean go = !stopped && ev.isLive(ae) && n[0] < ticks && p.isOnline() && ev.isMember(p.getUniqueId());
                if (!go) { t.cancel(); tasks.remove(t); end.run(); return; }
                if (n[0] == 0) { skyOn(p, true); p.getWorld().playSound(p.getLocation(), "entity.lightning_bolt.thunder", 1.5f, 0.6f); }
                n[0] += 2;
                double[] sc = sweepCentre(ae, ang, off, bps, t0);
                Location l = p.getLocation();
                double rx = l.getX() - sc[0], rz = l.getZ() - sc[1], d = Math.max(0.5, Math.hypot(rx, rz));
                fly(mine, l.getWorld());
                boolean high = l.getY() - l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) >= highY;   // up in the sky: hit hardest, wherever the eye is
                if (high) {
                    if (n[0] % 10 == 0) { p.damage(highDmg); p.sendActionBar(hint("disaster.hint-hurricane-bar")); }
                    if (p.getGameMode() != GameMode.SPECTATOR) {
                        Vector v = p.getVelocity();
                        p.setVelocity(new Vector(v.getX() + (-rz / d) * strength * 0.25 * highMul, v.getY() - 0.05, v.getZ() + (rx / d) * strength * 0.25 * highMul));
                    }
                }
                if (d <= sweepR) {                                         // inside the storm: shoved round the eye (spiral), not hunted
                    double f = 1.0 - 0.5 * d / sweepR;
                    double tx = -rz / d * 0.8 + rx / d * 0.4, tz = rx / d * 0.8 + rz / d * 0.4;
                    Vector v = p.getVelocity();
                    if (p.getGameMode() != GameMode.SPECTATOR) p.setVelocity(new Vector(v.getX() + tx * strength * 0.25 * f, v.getY(), v.getZ() + tz * strength * 0.25 * f));
                    if (destroy) rip(ae, new Location(l.getWorld(), sc[0], l.getY(), sc[1]), sweepR, rip, mine, cap, tx, tz);
                }
                for (int i = 0, sp = pc(8); i < sp; i++) {                                           // spiral of wind around the eye
                    double a = n[0] * 0.3 + i * 0.78, rad = 2 + i * 0.7;
                    double px = sc[0] + Math.cos(a) * rad, pz = sc[1] + Math.sin(a) * rad;
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, px, l.getY() + i * 0.4, pz, 0, -Math.sin(a), 0.05, Math.cos(a), 0.5);
                    p.getWorld().spawnParticle(Particle.CLOUD, px, l.getY() + 1 + i * 0.4, pz, 0, -Math.sin(a), 0, Math.cos(a), 0.5);
                }
                p.getWorld().spawnParticle(Particle.RAIN, l.getX() + R.nextDouble(-8, 8), l.getY() + R.nextDouble(0, 6), l.getZ() + R.nextDouble(-8, 8), pc(6), 0, 0, 0, 0);
            }, end, 1, 2);
            tasks.add(task);
        }
    }

    // =====================================================================================================
    // thunderstorm: dark storm sky; lightning locks onto every player (a short warning, then it strikes where they stand)
    // =====================================================================================================
    private void thunderstorm(ActiveEvent ae, ConfigurationSection c, List<Player> alive, double run) {
        int ticks = (int) (run * 20), warn = Math.max(10, (int) (c.getDouble("warning-seconds", 2.0) * 20));
        double every = Math.max(1, c.getDouble("strike-interval-seconds", 4.0)), dmg = dmg(c.getDouble("damage", 8)), hitR = c.getDouble("hit-radius", 2.5);
        double trackChance = Math.max(0, Math.min(1, c.getDouble("track-chance", 0.4)));      // chance a bolt is aimed near the player at all
        double aim = Math.max(0, c.getDouble("aim-offset", 3.0)), wander = Math.max(4, c.getDouble("random-radius", 14));
        int crater = c.getInt("crater-radius", 2), ambient = Math.max(0, c.getInt("ambient-strikes", 2));
        say("disaster.hazard-thunderstorm", Text.ph());
        say("disaster.hint-thunderstorm", Text.ph());
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            int[] cd = {40 + ThreadLocalRandom.current().nextInt(60)};
            active.incrementAndGet();
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; active.decrementAndGet(); try { skyOff(p); } catch (Throwable ignored) { } } };
            ScheduledTask task = pl.sched.entityTimer(p, t -> {
                boolean go = !stopped && ev.isLive(ae) && n[0] < ticks && p.isOnline() && ev.isMember(p.getUniqueId());
                if (!go) { t.cancel(); tasks.remove(t); end.run(); return; }
                ThreadLocalRandom r = ThreadLocalRandom.current();
                if (n[0] == 0) { skyOn(p, true); p.getWorld().playSound(p.getLocation(), "entity.lightning_bolt.thunder", 2f, 0.5f); }
                n[0] += 2; cd[0] -= 2;
                Location l = p.getLocation();
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l.getX(), l.getY() + 1, l.getZ(), 2, 0.6, 0.8, 0.6, 0.1);
                if (cd[0] <= 0) {                                       // mark a spot: the ring stays where it is, the bolt lands there (no following)
                    cd[0] = (int) (every * 20 * r.nextDouble(0.8, 1.4));
                    boolean aimed = r.nextDouble() < trackChance;
                    double reach = aimed ? aim : wander;
                    Location pick = l.clone().add(r.nextDouble(-reach, reach), 0, r.nextDouble(-reach, reach));
                    final Location mark = ev.inBounds(ae, pick.getX(), pick.getZ()) ? pick : l.clone();
                    p.getWorld().playSound(mark, "entity.lightning_bolt.impact", 1.2f, 2f);
                    if (aimed) p.sendActionBar(hint("disaster.hint-lightning-lock"));      // hint: step out of the ring before it lands
                    int[] m = {0};
                    ScheduledTask lock = pl.sched.entityTimer(p, tt -> {
                        if (stopped || !p.isOnline() || !ev.isLive(ae)) { tt.cancel(); tasks.remove(tt); return; }
                        m[0] += 2;
                        ring(p.getWorld(), mark.getX(), mark.getY(), mark.getZ(), hitR, Color.fromRGB(0xfff27a));
                        if (m[0] >= warn) { tt.cancel(); tasks.remove(tt); strike(ae, mark, hitR, dmg, crater); }
                    }, () -> { }, 1, 2);
                    tasks.add(lock);
                }
                if (ambient > 0 && r.nextInt(Math.max(10, ticks / (2 * ambient))) == 0) {      // random bolts that wreck the terrain around
                    Location at = l.clone().add(r.nextDouble(-18, 18), 0, r.nextDouble(-18, 18));
                    if (ev.inBounds(ae, at.getX(), at.getZ())) strike(ae, at, hitR, dmg, crater + 1);
                }
            }, end, 1, 2);
            tasks.add(task);
        }
    }

    /** A real bolt: lightning effect, damage to anyone close, and a scorched crater in the ground. */
    private void strike(ActiveEvent ae, Location at, double hitR, double dmg, int crater) {
        World w = at.getWorld();
        int gx = at.getBlockX(), gz = at.getBlockZ(), gy = top(w, gx, gz) + 1;
        Location g = new Location(w, at.getX(), gy, at.getZ());
        w.strikeLightningEffect(g);
        w.spawnParticle(Particle.ELECTRIC_SPARK, g, 40, 1, 1.5, 1, 0.3);
        w.playSound(g, "entity.lightning_bolt.thunder", 4f, 0.8f);
        near(g, hitR, 3, p -> { p.damage(dmg); p.setFireTicks(60); });
        carveSphere(ae, w, gx, gy - 1, gz, Math.max(1, crater));
    }

    // =====================================================================================================
    // meteor shower: the sky goes dark and many meteors come down around the players
    // =====================================================================================================
    // tnt rain: primed tnt drops at random spots across the arena (around random players, clamped inside the border)
    private org.bukkit.NamespacedKey tntKey;
    private org.bukkit.NamespacedKey tntTag() { if (tntKey == null) tntKey = new org.bukkit.NamespacedKey(pl, "tnt_rain_tnt"); return tntKey; }
    boolean isRainTnt(org.bukkit.entity.TNTPrimed t) { return t.getPersistentDataContainer().has(tntTag(), org.bukkit.persistence.PersistentDataType.BYTE); }

    private void tntRain(ActiveEvent ae, ConfigurationSection c, List<Player> alive, double run) {
        double every = Math.max(0.2, c.getDouble("every-seconds", 0.5));
        int count = Math.max(1, (int) (run / every)), perDrop = Math.min(c.getInt("max-per-drop", 4), 1 + (int) Math.floor(alive.size() * c.getDouble("per-player", 0.25)));
        long gap = Math.max(4, (long) (every * 20));
        float power = (float) (c.getDouble("power", 3.0) * Math.sqrt(diffD("damage-multiplier", 1.0)));
        say("disaster.hazard-tnt-rain", Text.ph());
        int[] n = {0};
        active.incrementAndGet();
        ScheduledTask task = pl.sched.globalTimer(t -> {
            if (stopped || !ev.isLive(ae) || n[0] >= count) { t.cancel(); tasks.remove(t); active.decrementAndGet(); return; }
            n[0]++;
            List<Player> r = roster;
            if (r.isEmpty()) return;
            for (int i = 0; i < perDrop; i++) {
                Player target = r.get(ThreadLocalRandom.current().nextInt(r.size()));
                pl.sched.entity(target, () -> {
                    if (!ev.isLive(ae) || stopped) return;
                    Location sp = chooseSpot(target.getLocation(), 40, ae);                 // random spot around the player, always inside the arena
                    pl.sched.region(sp, () -> {
                        if (!ev.isLive(ae) || stopped) return;
                        World w = sp.getWorld();
                        int gy = ground(w, sp);
                        double y = Math.min(w.getMaxHeight() - 2, gy + Math.max(10, c.getDouble("drop-height", 35)));
                        int fuse = ThreadLocalRandom.current().nextInt(Math.max(20, c.getInt("fuse-min-ticks", 50)), Math.max(21, c.getInt("fuse-max-ticks", 90)) + 1);
                        w.spawn(new Location(w, sp.getX(), y, sp.getZ()), org.bukkit.entity.TNTPrimed.class, tnt -> {
                            tnt.setFuseTicks(fuse);
                            tnt.setYield(power);
                            tnt.getPersistentDataContainer().set(tntTag(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                        });
                    });
                });
            }
        }, 1, gap);
        tasks.add(task);
    }

    private void meteorShower(ActiveEvent ae, ConfigurationSection c, List<Player> alive, double run) {
        double every = Math.max(0.5, c.getDouble("every-seconds", 1.5));        // a meteor this often for the whole run
        int count = Math.max(1, (int) (run / every));
        long gap = Math.max(4, (long) (every * 20));
        say("disaster.hazard-meteor-shower", Text.ph());
        for (Player p : alive) pl.sched.entity(p, () -> skyOn(p, false));
        int[] n = {0};
        active.incrementAndGet();
        ScheduledTask task = pl.sched.globalTimer(t -> {
            if (stopped || !ev.isLive(ae) || n[0] >= count) {
                t.cancel(); tasks.remove(t); active.decrementAndGet();
                if (!stopped) try { pl.sched.globalLater(() -> { for (Player p : alive) pl.sched.entity(p, () -> skyOff(p)); }, 120); } catch (Throwable ignored) { }
                return;
            }
            n[0]++;
            Player target = alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
            pl.sched.entity(target, () -> {
                if (!ev.isLive(ae)) return;
                Location sp = chooseSpot(target.getLocation(), 12, ae);
                pl.sched.region(sp, () -> { if (ev.isLive(ae) && !stopped) meteor(ae, c, sp, false); });
            });
        }, 1, gap);
        tasks.add(task);
    }
}
