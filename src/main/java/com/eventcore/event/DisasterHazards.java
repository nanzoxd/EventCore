package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * Meteors, the tornado, earthquakes and the laser really destroy terrain (down to bedrock, with lava and magma at the bottom);
 * geysers place real water/lava and put the ground back afterwards. Display entities are non-persistent and removed on stop.
 * Every hazard damage (and fall damage) is capped by hazards.max-damage (default 12 = 6 hearts).
 * Types: wind_charge (giant spinning wind charge that bursts on impact), meteor (falling magma block + crater),
 * lava_geyser, water_geyser (warning ring, then an eruption column that launches players) and wind_storm.
 */
final class DisasterHazards {
    private static final String[] TYPES = {"wind_charge", "meteor", "lava_geyser", "water_geyser", "wind_storm", "tornado", "hurricane", "meteor_shower", "earthquake", "laser_sinkhole", "thunderstorm"};

    private final EventCorePlugin pl;
    private final NaturalDisasterEvent ev;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private final Set<Entity> spawned = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sky = ConcurrentHashMap.newKeySet();
    private final Map<String, Double> next = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean stopped;
    private final AtomicInteger tornadoes = new AtomicInteger();
    private volatile String current;
    private volatile double currentUntil;
    private volatile Component bar;

    private static String label(String type) {
        return switch (type) {
            case "wind_charge" -> "Wind Charge"; case "meteor" -> "Meteor"; case "lava_geyser" -> "Lava Geyser";
            case "water_geyser" -> "Water Geyser"; case "wind_storm" -> "Wind Storm"; case "tornado" -> "Tornado";
            case "hurricane" -> "Hurricane"; case "meteor_shower" -> "Meteor Shower"; case "earthquake" -> "Earthquake";
            case "laser_sinkhole" -> "Laser"; case "thunderstorm" -> "Thunderstorm"; default -> type;
        };
    }

    private static String fmt(double secs) { int i = (int) Math.ceil(secs); return i >= 60 ? ((i + 59) / 60) + "m" : i + "s"; }

    /** The action bar line with every disaster's countdown: "Tornado: 2m | Meteor: 3m | ...", or null when hazards are off. */
    Component incomingBar(double t) { return bar; }

    DisasterHazards(EventCorePlugin pl, NaturalDisasterEvent ev) { this.pl = pl; this.ev = ev; }

    void reset() { stopped = false; next.clear(); active.set(0); tornadoes.set(0); bar = null; current = null; flung.clear(); pending.clear(); }

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
    double maxDamage() { return Math.max(1, ev.settings().getDouble("hazards.max-damage", 12)); }
    private double dmg(double v) { return Math.min(v, maxDamage()); }
    private int layersPerTick() { return Math.max(1, ev.settings().getInt("hazards.dig-layers-per-tick", 8)); }

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

    /** Columns of a disc, each starting at the surface; delay ripples outward. stopY below the world = dig to bedrock. */
    private List<Col> discCols(ActiveEvent ae, World w, double cx, double cz, double radius, double delayPerBlock) {
        List<Col> out = new ArrayList<>();
        int r = (int) Math.ceil(radius), bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > radius + 0.3) continue;
            int x = bx + dx, z = bz + dz;
            if (!ev.inBounds(ae, x + 0.5, z + 0.5)) continue;
            out.add(new Col(x, top(w, x, z), z, w.getMinHeight() - 1, (int) (d * delayPerBlock)));
        }
        return out;
    }

    /** Removes up to `layers` solid blocks from every active column. At bedrock: lava + magma floor. Returns true while work is left. */
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
                    if (c.stop >= min && c.y < c.stop) {                     // shallow column (rim): magma floor
                        if (c.floorFluid != null) {                          // vent pool: two blocks of fluid at the bottom
                            w.getBlockAt(c.x, c.stop, c.z).setType(c.floorFluid, false);
                            w.getBlockAt(c.x, c.stop + 1, c.z).setType(c.floorFluid, false);
                        } else {
                            Block f = w.getBlockAt(c.x, c.stop, c.z);
                            if (r.nextBoolean() && f.getType().isAir()) f.setType(Material.MAGMA_BLOCK, false);
                        }
                        c.done = true; break;
                    }
                    Block b = w.getBlockAt(c.x, c.y, c.z);
                    Material m = b.getType();
                    if (m == Material.BEDROCK || c.y < min) {
                        if (floor) {
                            int y1 = Math.max(c.y + 1, min);
                            Block b1 = w.getBlockAt(c.x, y1, c.z), b2 = w.getBlockAt(c.x, y1 + 1, c.z);
                            if (breakable(b1.getType())) b1.setType(r.nextInt(100) < 40 ? Material.MAGMA_BLOCK : Material.LAVA, false);
                            if (breakable(b2.getType())) b2.setType(Material.LAVA, false);
                        }
                        c.done = true; break;
                    }
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

    private void rimMagma(World w, int cx, int cz, int r) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int dx = -(r + 2); dx <= r + 2; dx++) for (int dz = -(r + 2); dz <= r + 2; dz++) {
            double d = Math.hypot(dx, dz);
            if (d <= r || d > r + 2 || rnd.nextInt(100) >= 35) continue;
            Block b = w.getBlockAt(cx + dx, top(w, cx + dx, cz + dz), cz + dz);
            if (b.getType().isSolid() && breakable(b.getType())) b.setType(Material.MAGMA_BLOCK, false);
        }
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
    void tick(ActiveEvent ae, double t, List<Player> alive) {
        ConfigurationSection h = ev.settings().getConfigurationSection("hazards");
        if (h == null || !h.getBoolean("enabled", true) || stopped) { bar = null; return; }
        double start = h.getDouble("start-after-seconds", 60), announce = h.getDouble("announce-seconds", 12);
        double esc = h.getBoolean("escalate", true) ? 1.0 + ev.phaseIndex(t) * 0.35 : 1.0;
        boolean live = t >= start && !alive.isEmpty();
        List<Object[]> rows = new ArrayList<>();                      // {type, seconds left | -1 active | -2 happening now}
        for (String type : TYPES) {
            ConfigurationSection c = h.getConfigurationSection(type);
            if (c == null || !c.getBoolean("enabled", true)) continue;
            double every = Math.max(5, c.getDouble("interval-seconds", 40));
            double due = next.computeIfAbsent(type, k -> Math.max(t, start) + every * ThreadLocalRandom.current().nextDouble(0.4, 1.0));
            if (type.equals("tornado") && tornadoes.get() >= Math.max(1, c.getInt("max-count", 1))) { rows.add(new Object[]{type, -1.0}); continue; }  // it stays until the end
            boolean busy = active.get() - tornadoes.get() >= Math.max(1, h.getInt("max-active", 4));
            if (live && t >= due && !busy) {
                next.put(type, t + every / esc);
                Player target = alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
                current = type; currentUntil = t + 8;
                launch(ae, type, c, target, alive);
                due = t + every / esc;
            }
            boolean now = type.equals(current) && t < currentUntil;
            rows.add(new Object[]{type, now ? -2.0 : Math.max(0, due - t)});
        }
        rows.sort(java.util.Comparator.comparingDouble(r -> (double) r[1]));
        Component b = Component.empty();
        boolean first = true;
        for (Object[] r : rows) {
            double secs = (double) r[1];
            if (!first) b = b.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
            first = false;
            String time = secs == -1.0 ? "ACTIVE" : secs == -2.0 ? "NOW" : fmt(secs);
            NamedTextColor col = secs < 0 ? NamedTextColor.RED : secs <= announce ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            b = b.append(Component.text(label((String) r[0]) + ": ", NamedTextColor.WHITE)).append(Component.text(time, col, secs < 0 || secs <= announce ? new TextDecoration[]{TextDecoration.BOLD} : new TextDecoration[0]));
        }
        bar = rows.isEmpty() ? null : b;
    }

    private void launch(ActiveEvent ae, String type, ConfigurationSection c, Player target, List<Player> alive) {
        pl.sched.entity(target, () -> {
            if (!ev.isLive(ae)) return;
            Location base = target.getLocation();
            double off = type.endsWith("geyser") ? 10 : type.equals("tornado") ? 25 : type.equals("earthquake") ? 15 : type.equals("laser_sinkhole") ? 8 : 5;
            Location spot = spot(base, off, ae);
            if (type.equals("wind_storm")) { windStorm(ae, c, alive); return; }
            if (type.equals("hurricane")) { hurricane(ae, c, alive); return; }
            if (type.equals("meteor_shower")) { meteorShower(ae, c, alive); return; }
            if (type.equals("thunderstorm")) { thunderstorm(ae, c, alive); return; }
            pl.sched.region(spot, () -> {
                if (!ev.isLive(ae) || stopped) return;
                try {
                    switch (type) {
                        case "wind_charge" -> windCharge(ae, c, spot);
                        case "meteor" -> meteor(ae, c, spot, true);
                        case "tornado" -> tornado(ae, c, spot);
                        case "earthquake" -> earthquake(ae, c, spot);
                        case "laser_sinkhole" -> laser(ae, c, spot);
                        case "lava_geyser" -> geyser(ae, c, spot, true);
                        default -> geyser(ae, c, spot, false);
                    }
                } catch (Throwable ex) { pl.getLogger().warning("Natural Disaster hazard " + type + " failed: " + ex); }
            });
        });
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
    // meteor: a giant magma block falls, blows a real crater into the ground and a shaft down to bedrock (lava + magma at the bottom)
    // =====================================================================================================

    private void meteor(ActiveEvent ae, ConfigurationSection c, Location spot, boolean announce) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), radius = c.getDouble("radius", 6), fall = 40;
        float s = (float) c.getDouble("scale", 4);
        int craterTicks = (int) (c.getDouble("crater-seconds", 8) * 20), fallTicks = (int) Math.ceil(fall / 1.3);
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
                w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3);
                w.spawnParticle(Particle.LAVA, at, 60, radius * 0.5, 0.5, radius * 0.5, 0);
                w.playSound(at, "entity.generic.explode", 4f, 0.5f);
                near(at, radius + 3, radius + 3, p -> {
                    p.damage(dmg(c.getDouble("damage", 12)));
                    p.setFireTicks((int) (c.getDouble("fire-seconds", 4) * 20));
                    p.setVelocity(p.getLocation().toVector().subtract(at.toVector()).setY(0.4).normalize().multiply(0.9).setY(0.8));
                });
                if (c.getBoolean("destroy-terrain", true)) {                 // real crater, then a shaft down to bedrock
                    int cr = (int) Math.max(2, Math.min(12, c.getDouble("crater-radius", radius)));
                    carveSphere(ae, w, (int) Math.floor(x), gy - 1, (int) Math.floor(z), cr);
                    rimMagma(w, (int) Math.floor(x), (int) Math.floor(z), cr);
                    double sr = c.getDouble("shaft-radius", 2);
                    if (sr > 0) shaft.addAll(discCols(ae, w, x, z, sr, 1));
                }
                return true;
            }
            int k = n - fallTicks;
            boolean more = !shaft.isEmpty() && digStep(ae, w, shaft, k, layersPerTick(), true);
            if (k % 5 == 0) {
                w.spawnParticle(Particle.FLAME, x, gy + 0.2, z, 10, radius * 0.5, 0.1, radius * 0.5, 0.01);
                w.spawnParticle(Particle.LAVA, x, gy + 0.3, z, 2, radius * 0.4, 0.1, radius * 0.4, 0);
                near(new Location(w, x, gy, z), radius * 0.8, 1.5, p -> p.setFireTicks(40));
            }
            return k < craterTicks || more;
        }, () -> kill(rock, false));
    }

    // =====================================================================================================
    // geysers: the ground blows open into a vent, a real water / lava column launches players; the hole stays for the rest of the event
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
        int ventDepth = c.getInt("vent-depth", lava ? 40 : 24);
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
                if (destroy) {                                          // the vent: the ground blows open (lava: down to bedrock)
                    for (Col cc : discCols(ae, w, x, z, ventR, 2)) {
                        Col v = new Col(cc.x, cc.y, cc.z, lava ? w.getMinHeight() - 1 : cc.y - ventDepth, cc.delay);
                        if (!lava) v.floorFluid = Material.WATER;
                        vent.add(v);
                    }
                    carveSphere(ae, w, bx, gy - 1, bz, (int) Math.ceil(ventR) + 1);
                }
            }
            boolean more = !vent.isEmpty() && digStep(ae, w, vent, k, layersPerTick(), lava);
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

    private void windStorm(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2), strength = c.getDouble("strength", 0.35);
        double dx = Math.cos(ang), dz = Math.sin(ang);
        int ticks = (int) (c.getDouble("duration-seconds", 8) * 20);
        boolean destroy = c.getBoolean("destroy-terrain", true);
        int rip = c.getInt("rip-per-tick", 3), cap = c.getInt("max-blocks-per-player", 35);
        double ripR = c.getDouble("rip-radius", 22);
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
                if (n[0] == 0) p.getWorld().playSound(p.getLocation(), "entity.wind_charge.wind_burst", 1.5f, 0.5f);
                n[0] += 2;
                Vector v = p.getVelocity();
                if (p.getGameMode() != GameMode.SPECTATOR) p.setVelocity(new Vector(v.getX() + dx * strength * 0.25, v.getY(), v.getZ() + dz * strength * 0.25));
                Location l = p.getLocation();
                if (destroy) { rip(ae, l, ripR, rip, mine, cap, dx, dz); }
                fly(mine, l.getWorld());
                for (int i = 0; i < 3; i++) {
                    double ox = ThreadLocalRandom.current().nextDouble(-4, 4), oy = ThreadLocalRandom.current().nextDouble(0, 3), oz = ThreadLocalRandom.current().nextDouble(-4, 4);
                    p.getWorld().spawnParticle(Particle.CLOUD, l.getX() + ox - dx * 3, l.getY() + oy, l.getZ() + oz - dz * 3, 0, dx, 0, dz, 0.6);
                }
                if (n[0] % 10 == 0) p.getWorld().spawnParticle(Particle.GUST, l.getX() + dx * 2, l.getY() + 1, l.getZ() + dz * 2, 1, 1, 1, 1, 0);
            }, end, 1, 2);
            tasks.add(task);
        }
    }

    // =====================================================================================================
    // tornado: a tall spinning funnel that never leaves: it roams the whole arena, rips blocks out, digs a trench to bedrock and throws players ~100 blocks
    // =====================================================================================================

    private void tornado(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        double height = c.getDouble("height", 30), pull = c.getDouble("pull", 0.45), speed = c.getDouble("speed", 0.2), reach = c.getDouble("radius", 7);
        double pickR = c.getDouble("pick-radius", 5), flingH = c.getDouble("launch-height", 100), dmg = dmg(c.getDouble("damage", 4));
        double dur = c.getDouble("duration-seconds", 0);               // 0 = it never disappears, it roams until the event ends
        int ticks = dur <= 0 ? Integer.MAX_VALUE : (int) (dur * 20), maxBlocks = Math.max(0, c.getInt("max-blocks", 45));
        int pickEvery = Math.max(1, c.getInt("pick-interval-ticks", 3)), picks = Math.max(1, c.getInt("picks-per-interval", 3));
        int digR = Math.max(0, c.getInt("dig-radius", 3)), layers = layersPerTick();
        double cooldown = c.getDouble("launch-cooldown-seconds", 10), catchR = reach * 0.55;
        boolean destroy = c.getBoolean("destroy-terrain", true);
        final int L = 14;
        double[] pos = {spot.getX(), spot.getZ()};
        double[] dir = {ThreadLocalRandom.current().nextDouble(Math.PI * 2)};
        double[] wp = {spot.getX(), spot.getZ()};
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
                Player[] best = {null};
                double[] bd = {Double.MAX_VALUE};
                near(centre, 45, 80, p -> { double d = p.getLocation().distanceSquared(centre); if (d < bd[0]) { bd[0] = d; best[0] = p; } });
                double wantX, wantZ;
                if (best[0] != null) { wantX = best[0].getLocation().getX(); wantZ = best[0].getLocation().getZ(); }
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
            // and digs a trench to bedrock along its whole path (lava + magma at the bottom)
            if (destroy && digR > 0) {
                if (n % 6 == 0) {
                    int px = (int) Math.floor(pos[0]), pz = (int) Math.floor(pos[1]);
                    for (int dx = -digR; dx <= digR; dx++) for (int dz = -digR; dz <= digR; dz++) {
                        if (Math.hypot(dx, dz) > digR + 0.3) continue;
                        int x = px + dx, z = pz + dz;
                        if (!ev.inBounds(ae, x + 0.5, z + 0.5) || !dug.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                        trail.add(new Col(x, top(w, x, z), z, w.getMinHeight() - 1, 0));
                    }
                }
                if (!trail.isEmpty()) { digStep(ae, w, trail, n, layers, true); trail.removeIf(cc -> cc.done); }
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
    // earthquake: the ground shakes, then a long fissure (ravine) tears open down to bedrock with lava at the bottom
    // =====================================================================================================
    private void earthquake(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        int shake = (int) (c.getDouble("shake-seconds", 6) * 20), len = Math.max(8, c.getInt("length", 44));
        double width = c.getDouble("width", 4), shakeR = c.getDouble("shake-radius", 45);
        int layers = Math.max(layersPerTick(), c.getInt("dig-layers-per-tick", 14)), colsPerTick = Math.max(1, c.getInt("columns-per-tick", 2));
        double ang = ThreadLocalRandom.current().nextDouble(Math.PI);
        double dx = Math.cos(ang), dz = Math.sin(ang), px = -dz, pz = dx;
        List<Col> cols = new ArrayList<>();
        Location base = new Location(w, spot.getX(), ground(w, spot), spot.getZ());
        say("disaster.hazard-earthquake", Text.ph());
        every(base, ae, 1, n -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (n < shake) {
                if (n == 0) w.playSound(base, "entity.ravager.roar", 4f, 0.5f);
                if (n % 8 == 0) w.playSound(base, "block.deepslate.break", 4f, 0.4f);
                if (n % 2 == 0) near(base, shakeR, 40, p -> {                         // camera-shake style jitter
                    Vector v = p.getVelocity();
                    p.setVelocity(new Vector(v.getX() + r.nextDouble(-0.1, 0.1), Math.max(v.getY(), p.isOnGround() ? 0.12 : v.getY()), v.getZ() + r.nextDouble(-0.1, 0.1)));
                    if (n % 6 == 0) w.spawnParticle(Particle.BLOCK, p.getLocation(), 12, 0.8, 0.1, 0.8, 0.1, p.getLocation().subtract(0, 1, 0).getBlock().getBlockData());
                });
                if (n % 4 == 0) for (int i = 0; i < len; i += 2) {      // cracks appear along the line before it opens
                    double off = Math.sin(i * 0.25) * 2.0;
                    w.spawnParticle(Particle.SMOKE, base.getX() + dx * (i - len / 2.0) + px * off, base.getY() + 0.3, base.getZ() + dz * (i - len / 2.0) + pz * off, 2, 0.4, 0.1, 0.4, 0.01);
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
                        cols.add(new Col(x, top(w, x, z), z, w.getMinHeight() - 1, i / colsPerTick));
                    }
                }
                w.playSound(base, "entity.generic.explode", 4f, 0.4f);
            }
            boolean more = digStep(ae, w, cols, k, layers, true);
            if (k % 3 == 0) near(base, shakeR, 40, p -> p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation(), 4, 1, 0.2, 1, 0.02));
            return more;
        }, () -> { });
    }

    // =====================================================================================================
    // laser sinkhole: a red orbital beam locks on, burns for a few seconds and leaves a sinkhole of lava and magma down to bedrock
    // =====================================================================================================
    private void laser(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), R = c.getDouble("radius", 6), dmg = dmg(c.getDouble("damage", 12));
        int charge = (int) (c.getDouble("charge-seconds", 4) * 20), burn = (int) (c.getDouble("burn-seconds", 4) * 20);
        int rim = Math.max(3, c.getInt("rim-depth", 16)), layers = layersPerTick();
        double core = R * 0.55, beamTop = Math.min(w.getMaxHeight() - 1, gy + 60);
        Location base = new Location(w, x, gy, z);
        List<Col> cols = new ArrayList<>();
        Particle.DustOptions thin = new Particle.DustOptions(Color.fromRGB(0xff1a1a), 1.6f), thick = new Particle.DustOptions(Color.fromRGB(0xff0000), 3.5f);
        say("disaster.hazard-laser", Text.ph());
        every(base, ae, 1, n -> {
            if (n < charge) {                                           // lock-on: warning ring, a thin beam that gets denser
                if (n == 0) w.playSound(base, "block.beacon.activate", 4f, 1.6f);
                if (n % 4 == 0) ring(w, x, gy, z, R, Color.fromRGB(0xff1a1a));
                if (n % 10 == 0) w.playSound(base, "block.beacon.ambient", 3f, 0.5f + n * 0.01f);
                int step = n < charge / 2 ? 5 : 3;
                if (n % 2 == 0) for (double y = gy; y < beamTop; y += step) w.spawnParticle(Particle.DUST, x, y, z, 1, 0.05, 0, 0.05, 0, thin);
                return true;
            }
            int k = n - charge;
            if (k == 0) {                                               // FIRE: damage the target zone, plan the sinkhole
                w.playSound(base, "block.beacon.power_select", 4f, 0.5f);
                w.playSound(base, "entity.generic.explode", 4f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, base, 2);
                near(base, R * 0.6, 4, p -> { p.damage(dmg); p.setFireTicks(120); });
                for (int dx = (int) -Math.ceil(R); dx <= R; dx++) for (int dz = (int) -Math.ceil(R); dz <= R; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d > R) continue;
                    int bx = (int) Math.floor(x) + dx, bz = (int) Math.floor(z) + dz;
                    if (!ev.inBounds(ae, bx + 0.5, bz + 0.5)) continue;
                    int sy = top(w, bx, bz);
                    int stop = d <= core ? w.getMinHeight() - 1 : sy - Math.max(2, (int) (rim * (1 - (d - core) / (R - core + 0.001))));
                    cols.add(new Col(bx, sy, bz, stop, (int) (d * 2)));
                }
            }
            if (k < burn) {
                if (k % 2 == 0) for (double y = gy; y < beamTop; y += 2) w.spawnParticle(Particle.DUST, x, y, z, 1, 0.25, 0, 0.25, 0, thick);
                if (k % 4 == 0) for (double y = gy; y < beamTop; y += 6) w.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.1, 0, 0.1, 0.01);
                w.spawnParticle(Particle.FLAME, x, gy + 0.5, z, 12, R * 0.4, 0.3, R * 0.4, 0.05);
                w.spawnParticle(Particle.LAVA, x, gy + 0.5, z, 3, R * 0.4, 0.2, R * 0.4, 0);
                if (k % 10 == 0) w.playSound(base, "block.beacon.ambient", 4f, 0.5f);
            }
            boolean more = digStep(ae, w, cols, k, layers, true);       // the ground melts away from the centre outward
            return k < burn || more;
        }, () -> { });
    }

    // =====================================================================================================
    // hurricane: night, rain and a rotating spiral of wind that shoves everybody around
    // =====================================================================================================

    private void hurricane(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        int ticks = (int) (c.getDouble("duration-seconds", 20) * 20);
        double strength = c.getDouble("strength", 0.45), ripR = c.getDouble("rip-radius", 24);
        boolean destroy = c.getBoolean("destroy-terrain", true);
        int rip = c.getInt("rip-per-tick", 4), cap = c.getInt("max-blocks-per-player", 45);
        say("disaster.hazard-hurricane", Text.ph());
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
                double ang = n[0] * 0.04, tx = Math.cos(ang), tz = Math.sin(ang);       // the wind direction keeps turning
                Vector v = p.getVelocity();
                p.setVelocity(new Vector(v.getX() + tx * strength * 0.2, v.getY(), v.getZ() + tz * strength * 0.2));
                Location l = p.getLocation();
                ThreadLocalRandom r = ThreadLocalRandom.current();
                if (destroy) rip(ae, l, ripR, rip, mine, cap, tx, tz);                  // blocks are torn up at random and whirled away
                fly(mine, l.getWorld());
                for (int i = 0, sp = pc(8); i < sp; i++) {                                           // spiral of wind and snowflake-like spray
                    double a = n[0] * 0.3 + i * 0.78, rad = 2 + i * 0.7;
                    double px = l.getX() + Math.cos(a) * rad, pz = l.getZ() + Math.sin(a) * rad;
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, px, l.getY() + i * 0.4, pz, 0, -Math.sin(a), 0.05, Math.cos(a), 0.5);
                    p.getWorld().spawnParticle(Particle.CLOUD, px, l.getY() + 1 + i * 0.4, pz, 0, -Math.sin(a), 0, Math.cos(a), 0.5);
                }
                p.getWorld().spawnParticle(Particle.RAIN, l.getX() + r.nextDouble(-8, 8), l.getY() + r.nextDouble(0, 6), l.getZ() + r.nextDouble(-8, 8), pc(6), 0, 0, 0, 0);
            }, end, 1, 2);
            tasks.add(task);
        }
    }

    // =====================================================================================================
    // thunderstorm: dark storm sky; lightning locks onto every player (a short warning, then it strikes where they stand)
    // =====================================================================================================
    private void thunderstorm(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        int ticks = (int) (c.getDouble("duration-seconds", 30) * 20), warn = Math.max(4, (int) (c.getDouble("warning-seconds", 1.2) * 20));
        double every = Math.max(1, c.getDouble("strike-interval-seconds", 2.5)), dmg = dmg(c.getDouble("damage", 8)), hitR = c.getDouble("hit-radius", 2.5);
        int crater = c.getInt("crater-radius", 2), ambient = Math.max(0, c.getInt("ambient-strikes", 3));
        say("disaster.hazard-thunderstorm", Text.ph());
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            int[] cd = {20 + ThreadLocalRandom.current().nextInt(40)};
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
                if (cd[0] <= 0) {                                       // lock on: ring + crackle, then strike where the player is NOW
                    cd[0] = (int) (every * 20 * r.nextDouble(0.7, 1.3));
                    Location mark = l.clone();
                    p.getWorld().playSound(mark, "entity.lightning_bolt.impact", 1.2f, 2f);
                    p.sendActionBar(Component.text("⚡ LIGHTNING IS LOCKING ON — MOVE!", NamedTextColor.YELLOW, TextDecoration.BOLD));
                    int[] m = {0};
                    ScheduledTask lock = pl.sched.entityTimer(p, tt -> {
                        if (stopped || !p.isOnline() || !ev.isLive(ae)) { tt.cancel(); tasks.remove(tt); return; }
                        m[0] += 2;
                        Location pl2 = p.getLocation();
                        ring(p.getWorld(), pl2.getX(), pl2.getY(), pl2.getZ(), hitR, Color.fromRGB(0xfff27a));
                        if (m[0] >= warn) { tt.cancel(); tasks.remove(tt); strike(ae, pl2, hitR, dmg, crater); }
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
        rimMagma(w, gx, gz, Math.max(1, crater));
    }

    // =====================================================================================================
    // meteor shower: the sky goes dark and many meteors come down around the players
    // =====================================================================================================
    private void meteorShower(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        int count = Math.max(1, c.getInt("meteors", 14));
        long gap = Math.max(4, (long) (c.getDouble("duration-seconds", 16) * 20 / count));
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
                Location sp = spot(target.getLocation(), 12, ae);
                pl.sched.region(sp, () -> { if (ev.isLive(ae) && !stopped) meteor(ae, c, sp, false); });
            });
        }, 1, gap);
        tasks.add(task);
    }
}
