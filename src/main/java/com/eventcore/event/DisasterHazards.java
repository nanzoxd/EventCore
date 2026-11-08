package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * No block is ever changed, so there is nothing to restore; display entities are non-persistent (never saved to
 * disk), tracked, and removed when the event stops.
 * Types: wind_charge (giant spinning wind charge that bursts on impact), meteor (falling magma block + crater),
 * lava_geyser, water_geyser (warning ring, then an eruption column that launches players) and wind_storm.
 */
final class DisasterHazards {
    private static final String[] TYPES = {"wind_charge", "meteor", "lava_geyser", "water_geyser", "wind_storm", "tornado", "hurricane", "meteor_shower"};

    private final EventCorePlugin pl;
    private final NaturalDisasterEvent ev;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private final Set<Entity> spawned = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sky = ConcurrentHashMap.newKeySet();
    private final Map<String, Double> next = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean stopped;

    DisasterHazards(EventCorePlugin pl, NaturalDisasterEvent ev) { this.pl = pl; this.ev = ev; }

    void reset() { stopped = false; next.clear(); active.set(0); }

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
    // scheduling
    // =====================================================================================================
    void tick(ActiveEvent ae, double t, List<Player> alive) {
        ConfigurationSection h = ev.settings().getConfigurationSection("hazards");
        if (h == null || !h.getBoolean("enabled", true) || alive.isEmpty() || stopped) return;
        if (t < h.getDouble("start-after-seconds", 60)) return;
        double esc = h.getBoolean("escalate", true) ? 1.0 + ev.phaseIndex(t) * 0.35 : 1.0;
        for (String type : TYPES) {
            ConfigurationSection c = h.getConfigurationSection(type);
            if (c == null || !c.getBoolean("enabled", true)) continue;
            double every = Math.max(5, c.getDouble("interval-seconds", 40));
            double due = next.computeIfAbsent(type, k -> t + every * ThreadLocalRandom.current().nextDouble(0.4, 1.0));
            if (t < due || active.get() >= Math.max(1, h.getInt("max-active", 3))) continue;
            next.put(type, t + every / esc);
            Player target = alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
            launch(ae, type, c, target, alive);
        }
    }

    private void launch(ActiveEvent ae, String type, ConfigurationSection c, Player target, List<Player> alive) {
        pl.sched.entity(target, () -> {
            if (!ev.isLive(ae)) return;
            Location base = target.getLocation();
            double off = type.endsWith("geyser") ? 10 : type.equals("tornado") ? 25 : 5;
            Location spot = spot(base, off, ae);
            if (type.equals("wind_storm")) { windStorm(ae, c, alive); return; }
            if (type.equals("hurricane")) { hurricane(ae, c, alive); return; }
            if (type.equals("meteor_shower")) { meteorShower(ae, c, alive); return; }
            pl.sched.region(spot, () -> {
                if (!ev.isLive(ae) || stopped) return;
                try {
                    switch (type) {
                        case "wind_charge" -> windCharge(ae, c, spot);
                        case "meteor" -> meteor(ae, c, spot, true);
                        case "tornado" -> tornado(ae, c, spot);
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
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            w.spawnParticle(Particle.DUST, x + Math.cos(a) * r, y + 0.15, z + Math.sin(a) * r, 1, 0, 0, 0, 0, o);
        }
    }

    private void near(Location c, double r, double halfHeight, Consumer<Player> fn) {
        for (Entity e : c.getWorld().getNearbyEntities(c, r, halfHeight, r))
            if (e instanceof Player p && ev.isMember(p.getUniqueId()) && p.getGameMode() != GameMode.SPECTATOR) fn.accept(p);
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
            if (y <= gy + scale * 0.4) { windBurst(w, x, gy, z, radius, c.getDouble("damage", 4), c.getDouble("knockback", 1.6)); return false; }
            d.teleportAsync(new Location(w, x, y, z));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(n * 0.45f, 0f, 1f, 0f), new Vector3f(scale), new AxisAngle4f()));
            w.spawnParticle(Particle.CLOUD, x, y, z, 6, scale * 0.15, scale * 0.15, scale * 0.15, 0.02);
            w.spawnParticle(Particle.GUST, x, y, z, 1, scale * 0.2, scale * 0.2, scale * 0.2, 0);
            if (n % 4 == 0) ring(w, x, gy, z, radius, Color.fromRGB(0xe8c670));       // shows where it lands
            return true;
        }, () -> kill(d, false));
    }

    private void windBurst(World w, double x, int gy, double z, double radius, double dmg, double kb) {
        Location c = new Location(w, x, gy + 0.5, z);
        w.spawnParticle(Particle.GUST_EMITTER_LARGE, c, 1);
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI / 18;
            w.spawnParticle(Particle.CLOUD, x, gy + 0.3, z, 0, Math.cos(a), 0.15, Math.sin(a), 0.9);
        }
        w.playSound(c, "entity.wind_charge.wind_burst", 3f, 0.8f);
        near(c, radius, radius, p -> {
            Vector dir = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
            p.setVelocity(dir.normalize().multiply(kb).setY(0.8));
            if (dmg > 0) p.damage(dmg);
        });
    }

    // =====================================================================================================
    // meteor: a giant magma block falls, explodes and leaves a burning magma crater for a few seconds
    // =====================================================================================================
    private void meteor(ActiveEvent ae, ConfigurationSection c, Location spot, boolean announce) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), radius = c.getDouble("radius", 5), fall = 40;
        float s = (float) c.getDouble("scale", 4);
        int craterTicks = (int) (c.getDouble("crater-seconds", 8) * 20), fallTicks = (int) Math.ceil(fall / 1.3);
        BlockDisplay rock = blockDisplay(new Location(w, x, gy + fall, z), Material.MAGMA_BLOCK, s, s, s);
        rock.setInterpolationDuration(2);
        rock.setTeleportDuration(2);
        BlockDisplay[] crater = new BlockDisplay[1];
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
                w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 2);
                w.spawnParticle(Particle.LAVA, at, 40, radius * 0.4, 0.3, radius * 0.4, 0);
                w.playSound(at, "entity.generic.explode", 4f, 0.6f);
                near(at, radius, radius, p -> {
                    p.damage(c.getDouble("damage", 6));
                    p.setFireTicks((int) (c.getDouble("fire-seconds", 4) * 20));
                    p.setVelocity(p.getLocation().toVector().subtract(at.toVector()).setY(0.4).normalize().multiply(0.9).setY(0.6));
                });
                crater[0] = blockDisplay(new Location(w, x, gy, z), Material.MAGMA_BLOCK, (float) radius * 1.6f, 0.15f, (float) radius * 1.6f);
                return true;
            }
            int k = n - fallTicks;
            if (k % 5 == 0) {
                w.spawnParticle(Particle.FLAME, x, gy + 0.2, z, 10, radius * 0.5, 0.1, radius * 0.5, 0.01);
                w.spawnParticle(Particle.LAVA, x, gy + 0.3, z, 2, radius * 0.4, 0.1, radius * 0.4, 0);
                near(new Location(w, x, gy, z), radius * 0.8, 1.5, p -> p.setFireTicks(40));
            }
            return k < craterTicks;
        }, () -> { kill(rock, false); if (crater[0] != null) kill(crater[0], false); });
    }

    // =====================================================================================================
    // geysers: warning ring and rumble, then an eruption column that launches players
    // =====================================================================================================
    private void geyser(ActiveEvent ae, ConfigurationSection c, Location spot, boolean lava) {
        World w = spot.getWorld();
        int gy = ground(w, spot);
        double x = spot.getX(), z = spot.getZ(), radius = c.getDouble("radius", 2.5), height = c.getDouble("height", lava ? 14 : 16);
        int warn = (int) (c.getDouble("warning-seconds", 4) * 20), dur = (int) (c.getDouble("duration-seconds", 5) * 20);
        double launch = c.getDouble("launch", lava ? 1.0 : 1.4), dmg = c.getDouble("damage", lava ? 4 : 0);
        Color col = lava ? Color.fromRGB(0xff5a1f) : Color.fromRGB(0x4aa8ff);
        Location base = new Location(w, x, gy, z);
        BlockDisplay[] core = new BlockDisplay[1];
        say(lava ? "disaster.hazard-lava" : "disaster.hazard-water", Text.ph());
        every(base, ae, 1, n -> {
            if (n < warn) {                                            // rumble + ring so players can run
                if (n == 0) w.playSound(base, lava ? "block.lava.pop" : "block.water.ambient", 3f, 0.6f);
                if (n % 5 == 0) {
                    ring(w, x, gy, z, radius + 0.5, col);
                    w.spawnParticle(lava ? Particle.SMOKE : Particle.BUBBLE_POP, x, gy + 0.2, z, 12, radius * 0.5, 0.1, radius * 0.5, 0.02);
                    if (lava) w.spawnParticle(Particle.DRIPPING_LAVA, x, gy + 0.5, z, 4, radius * 0.5, 0.2, radius * 0.5, 0);
                }
                return true;
            }
            int k = n - warn;
            if (k == 0) {
                w.playSound(base, lava ? "entity.generic.explode" : "entity.generic.splash", 3f, lava ? 1.2f : 0.7f);
                core[0] = blockDisplay(base, lava ? Material.MAGMA_BLOCK : Material.LIGHT_BLUE_STAINED_GLASS, (float) (radius * 0.8), 0.1f, (float) (radius * 0.8));
                core[0].setTransformation(new Transformation(new Vector3f((float) (-radius * 0.4), 0f, (float) (-radius * 0.4)), new AxisAngle4f(),
                        new Vector3f((float) (radius * 0.8), (float) height, (float) (radius * 0.8)), new AxisAngle4f()));   // animates the column upward
            }
            if (k < dur) {
                for (int i = 0; i < 6; i++) {
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
                if (k % 4 == 0) near(new Location(w, x, gy + height / 2, z), radius, height / 2, p -> {
                    p.setVelocity(new Vector(p.getVelocity().getX() * 0.5, launch, p.getVelocity().getZ() * 0.5));
                    if (lava) { if (dmg > 0) p.damage(dmg); p.setFireTicks(100); } else p.setFireTicks(0);
                });
                return true;
            }
            if (k == dur && core[0] != null) {                       // collapse the column
                core[0].setTransformation(new Transformation(new Vector3f((float) (-radius * 0.4), 0f, (float) (-radius * 0.4)), new AxisAngle4f(),
                        new Vector3f((float) (radius * 0.8), 0.05f, (float) (radius * 0.8)), new AxisAngle4f()));
            }
            return k < dur + 8;
        }, () -> { if (core[0] != null) kill(core[0], false); });
    }

    // =====================================================================================================
    // wind storm: a strong gust in one direction with wind streaks, pushing everybody along
    // =====================================================================================================
    private void windStorm(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2), strength = c.getDouble("strength", 0.35);
        double dx = Math.cos(ang), dz = Math.sin(ang);
        int ticks = (int) (c.getDouble("duration-seconds", 8) * 20);
        say("disaster.hazard-windstorm", Text.ph());
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            active.incrementAndGet();
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; active.decrementAndGet(); } };
            ScheduledTask task = pl.sched.entityTimer(p, t -> {
                boolean go = !stopped && ev.isLive(ae) && n[0] < ticks && p.isOnline() && ev.isMember(p.getUniqueId());
                if (!go) { t.cancel(); tasks.remove(t); end.run(); return; }
                if (n[0] == 0) p.getWorld().playSound(p.getLocation(), "entity.wind_charge.wind_burst", 1.5f, 0.5f);
                n[0] += 2;
                Vector v = p.getVelocity();
                if (p.getGameMode() != GameMode.SPECTATOR) p.setVelocity(new Vector(v.getX() + dx * strength * 0.25, v.getY(), v.getZ() + dz * strength * 0.25));
                Location l = p.getLocation();
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
    // tornado: a funnel of wind with orbiting debris that drifts toward players, lifting and flinging them
    // =====================================================================================================
    private static final Material[] DEBRIS = {Material.DIRT, Material.GRAVEL, Material.OAK_LOG, Material.COBBLESTONE, Material.COARSE_DIRT, Material.STONE, Material.OAK_PLANKS};

    private void tornado(ActiveEvent ae, ConfigurationSection c, Location spot) {
        World w = spot.getWorld();
        double height = c.getDouble("height", 22), pull = c.getDouble("pull", 0.5), speed = c.getDouble("speed", 0.12), reach = c.getDouble("radius", 6);
        int ticks = (int) (c.getDouble("duration-seconds", 25) * 20), debrisN = Math.max(0, c.getInt("debris", 14));
        double dmg = c.getDouble("damage", 2.0);
        double[] pos = {spot.getX(), spot.getZ()};
        double[] dir = {ThreadLocalRandom.current().nextDouble(Math.PI * 2)};
        int[] gy = {ground(w, spot)};
        List<BlockDisplay> debris = new ArrayList<>();
        double[][] dp = new double[debrisN][3];
        say("disaster.hazard-tornado", Text.ph());
        every(spot, ae, 1, n -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (n == 0) {
                w.playSound(spot, "entity.ender_dragon.growl", 3f, 0.5f);
                for (int i = 0; i < debrisN; i++) {
                    dp[i][0] = r.nextDouble(Math.PI * 2); dp[i][1] = r.nextDouble(); dp[i][2] = r.nextDouble(0, 2.5);
                    float sc = (float) r.nextDouble(0.4, 0.9);
                    debris.add(blockDisplay(new Location(w, pos[0], gy[0], pos[1]), DEBRIS[r.nextInt(DEBRIS.length)], sc, sc, sc));
                    debris.get(i).setTeleportDuration(2);
                }
            }
            Location centre = new Location(w, pos[0], gy[0], pos[1]);
            if (n % 10 == 0) {                                          // follow the ground and steer toward the closest player
                gy[0] = ground(w, centre);
                Player[] best = {null};
                double[] bd = {Double.MAX_VALUE};
                near(centre, 60, 80, p -> { double d = p.getLocation().distanceSquared(centre); if (d < bd[0]) { bd[0] = d; best[0] = p; } });
                if (best[0] != null) {
                    double want = Math.atan2(best[0].getLocation().getZ() - pos[1], best[0].getLocation().getX() - pos[0]);
                    double diff = Math.atan2(Math.sin(want - dir[0]), Math.cos(want - dir[0]));
                    dir[0] += diff * 0.3 + r.nextDouble(-0.15, 0.15);
                }
            }
            double lim = Math.max(4, ev.radiusNow(ae) - 3);
            pos[0] = Math.max(ev.cx() - lim, Math.min(ev.cx() + lim, pos[0] + Math.cos(dir[0]) * speed));
            pos[1] = Math.max(ev.cz() - lim, Math.min(ev.cz() + lim, pos[1] + Math.sin(dir[0]) * speed));
            double spin = n * 0.35;
            for (int i = 0; i < 10; i++) {                              // spiralling funnel, wider toward the top
                double fy = r.nextDouble(height), rad = 1.0 + fy * 0.28, a = spin + fy * 0.45 + i * 0.63;
                w.spawnParticle(Particle.CLOUD, pos[0] + Math.cos(a) * rad, gy[0] + fy, pos[1] + Math.sin(a) * rad, 0, -Math.sin(a), 0.1, Math.cos(a), 0.4);
            }
            w.spawnParticle(Particle.LARGE_SMOKE, pos[0], gy[0] + 0.5, pos[1], 3, 1.2, 0.3, 1.2, 0.02);
            if (n % 2 == 0) for (int i = 0; i < debris.size(); i++) {   // debris orbits and rises
                double a = dp[i][0] + n * 0.25 * (1 + dp[i][2] * 0.2), fy = (dp[i][1] * height + n * 0.08) % height, rad = 1.0 + fy * 0.28 + dp[i][2];
                debris.get(i).teleportAsync(new Location(w, pos[0] + Math.cos(a) * rad, gy[0] + fy, pos[1] + Math.sin(a) * rad));
            }
            if (n % 4 == 0) {
                Location now = new Location(w, pos[0], gy[0] + height / 2, pos[1]);
                near(now, reach, height / 2, p -> {
                    Vector rel = p.getLocation().toVector().subtract(new Vector(pos[0], 0, pos[1])).setY(0);
                    if (rel.lengthSquared() < 0.04) rel = new Vector(1, 0, 0);
                    Vector n0 = rel.clone().normalize();
                    Vector tang = new Vector(-n0.getZ(), 0, n0.getX()).multiply(pull);
                    Vector in = n0.clone().multiply(-pull * 0.6);
                    p.setVelocity(new Vector(tang.getX() + in.getX(), 0.55, tang.getZ() + in.getZ()));
                    if (n % 20 == 0 && dmg > 0) p.damage(dmg);
                });
            }
            return n < ticks;
        }, () -> { for (BlockDisplay d : debris) kill(d, false); });
    }

    // =====================================================================================================
    // hurricane: night, rain and a rotating spiral of wind that shoves everybody around
    // =====================================================================================================
    private void hurricane(ActiveEvent ae, ConfigurationSection c, List<Player> alive) {
        int ticks = (int) (c.getDouble("duration-seconds", 20) * 20);
        double strength = c.getDouble("strength", 0.45);
        say("disaster.hazard-hurricane", Text.ph());
        for (Player p : alive) {
            if (!p.isOnline()) continue;
            int[] n = {0};
            active.incrementAndGet();
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; active.decrementAndGet(); try { skyOff(p); } catch (Throwable ignored) { } } };
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
                for (int i = 0; i < 8; i++) {                                           // spiral of wind and snowflake-like spray
                    double a = n[0] * 0.3 + i * 0.78, rad = 2 + i * 0.7;
                    double px = l.getX() + Math.cos(a) * rad, pz = l.getZ() + Math.sin(a) * rad;
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, px, l.getY() + i * 0.4, pz, 0, -Math.sin(a), 0.05, Math.cos(a), 0.5);
                    p.getWorld().spawnParticle(Particle.CLOUD, px, l.getY() + 1 + i * 0.4, pz, 0, -Math.sin(a), 0, Math.cos(a), 0.5);
                }
                p.getWorld().spawnParticle(Particle.RAIN, l.getX() + r.nextDouble(-8, 8), l.getY() + r.nextDouble(0, 6), l.getZ() + r.nextDouble(-8, 8), 6, 0, 0, 0, 0);
            }, end, 1, 2);
            tasks.add(task);
        }
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
