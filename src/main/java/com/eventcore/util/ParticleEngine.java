package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Configurable particle engine used by the ritual, event start animations, auras and crate landings.
 * Shapes: ring, circle, polygon, rain, scatter, burst, spiral, helix. Point offsets are plain maths, so they can be computed off-thread
 * (config.yml particles.compute-async) while the spawn itself always happens on the owning region thread.
 */
public final class ParticleEngine {
    /** One configurable particle layer. Fields are public so callers (shockwave) can tweak the radius per tick. */
    public static final class Layer {
        public String name, shape;
        public Particle particle;
        public Color color, toColor;
        public float size;
        public int count, min, max;
        public int sides;
        public double rotation;
        public boolean skript;   // reproduce the original Skript: its cos/sin take degrees, but the script passed radians
        public double radius, radiusJitter, rMin, rMax, yJitter, height, yMin, yMax, step, turns, rise, spin;
    }

    private record Pt(Layer l, double x, double y, double z, int n) { }

    private final EventCorePlugin pl;

    public ParticleEngine(EventCorePlugin pl) { this.pl = pl; }

    public List<Layer> read(ConfigurationSection layers) {
        List<Layer> out = new ArrayList<>();
        if (layers == null) return out;
        for (String k : layers.getKeys(false)) {
            ConfigurationSection s = layers.getConfigurationSection(k);
            if (s != null && s.getBoolean("enabled", true)) out.add(readLayer(k, s));
        }
        return out;
    }

    public Layer readLayer(String name, ConfigurationSection s) {
        Layer l = new Layer();
        l.name = name;
        l.shape = s.getString("shape", "ring").toLowerCase(Locale.ROOT);
        l.particle = particle(s.getString("particle", "DUST"));
        l.color = color(s.getString("color", "#ff5a00"));
        l.toColor = color(s.getString("to-color", s.getString("color", "#ff5a00")));
        l.size = (float) s.getDouble("size", 1.0);
        l.count = Math.max(1, s.getInt("count", 20));
        l.min = Math.max(1, s.getInt("particles-min", 1));
        l.max = Math.max(l.min, s.getInt("particles-max", l.min));
        l.radius = s.getDouble("radius", 1.0);
        l.radiusJitter = s.getDouble("radius-jitter", 0.0);
        l.rMin = s.getDouble("radius-min", 0.0);
        l.rMax = s.getDouble("radius-max", 1.0);
        l.yJitter = s.getDouble("y-jitter", 0.0);
        l.height = s.getDouble("height", 0.0);
        l.yMin = s.getDouble("y-min", 0.0);
        l.yMax = s.getDouble("y-max", 0.0);
        l.step = s.getDouble("step-degrees", 0.0);
        l.turns = s.getDouble("turns", 2.0);
        l.rise = s.getDouble("rise", 2.0);
        l.spin = s.getDouble("spin", 0.0);
        l.sides = Math.max(3, s.getInt("sides", 3));
        l.rotation = s.getDouble("rotation", 0.0);
        l.skript = s.getBoolean("skript-trig", false);
        return l;
    }

    private static Particle particle(String n) {
        try {
            Particle p = Particle.valueOf(n.toUpperCase(Locale.ROOT));
            if (p.getDataType() != Void.class && p != Particle.DUST && p != Particle.DUST_COLOR_TRANSITION) return Particle.DUST;
            return p;
        } catch (Exception e) { return Particle.DUST; }
    }

    private static Color color(String hex) {
        try { return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)); }
        catch (Exception e) { return Color.ORANGE; }
    }

    /** Angle in degrees -> radians. In skript-trig mode the value is first multiplied by 0.0174533 and then used as DEGREES, like the original script. */
    private static double ang(Layer l, double deg) {
        return l.skript ? Math.toRadians(deg * 0.0174533) : Math.toRadians(deg);
    }

    private static double rnd(double a, double b) { return b <= a ? a : ThreadLocalRandom.current().nextDouble(a, b); }
    private static double jit(double j) { return j <= 0 ? 0 : ThreadLocalRandom.current().nextDouble(-j, j); }
    private static int per(Layer l) { return ThreadLocalRandom.current().nextInt(l.min, l.max + 1); }

    private List<Pt> compute(List<Layer> layers, long cycle) {
        List<Pt> out = new ArrayList<>();
        for (Layer l : layers) {
            double phase = Math.toRadians(l.spin * cycle);
            switch (l.shape) {
                case "scatter" -> {
                    for (int i = 0; i < l.count; i++) {
                        double a = ang(l, rnd(0, 360)), r = rnd(l.rMin, l.rMax);
                        out.add(new Pt(l, r * Math.cos(a), l.height + rnd(l.yMin, l.yMax), r * Math.sin(a), per(l)));
                    }
                }
                case "circle" -> {
                    // true static circle: count points evenly on radius
                    for (int i = 0; i < l.count; i++) {
                        double a = Math.toRadians(i * 360.0 / l.count);
                        out.add(new Pt(l, l.radius * Math.cos(a), l.height, l.radius * Math.sin(a), per(l)));
                    }
                }
                case "polygon" -> {
                    // regular polygon (radius = distance centre -> corner), count = points per edge, rotated by rotation degrees
                    int n = l.sides;
                    for (int k = 0; k < n; k++) {
                        double a1 = Math.toRadians(l.rotation + k * 360.0 / n), a2 = Math.toRadians(l.rotation + (k + 1) * 360.0 / n);
                        double x1 = l.radius * Math.cos(a1), z1 = l.radius * Math.sin(a1), x2 = l.radius * Math.cos(a2), z2 = l.radius * Math.sin(a2);
                        for (int i = 0; i < l.count; i++) {
                            double t = i / (double) l.count;
                            out.add(new Pt(l, x1 + (x2 - x1) * t, l.height, z1 + (z2 - z1) * t, per(l)));
                        }
                    }
                }
                case "rain" -> {
                    // ambient particles scattered in a square of +-radius, yMin..yMax above the centre
                    for (int i = 0; i < l.count; i++)
                        out.add(new Pt(l, rnd(-l.radius, l.radius), l.height + rnd(l.yMin, l.yMax), rnd(-l.radius, l.radius), per(l)));
                }
                case "burst" -> {
                    for (int i = 0; i < l.count; i++) {
                        double u = rnd(-1, 1), th = rnd(0, Math.PI * 2), s = Math.sqrt(1 - u * u), r = rnd(l.rMin, l.rMax);
                        out.add(new Pt(l, r * s * Math.cos(th), l.height + r * u, r * s * Math.sin(th), per(l)));
                    }
                }
                case "spiral", "helix" -> {
                    int strands = l.shape.equals("helix") ? 2 : 1;
                    for (int st = 0; st < strands; st++)
                        for (int i = 0; i < l.count; i++) {
                            double t = l.count <= 1 ? 0 : i / (double) (l.count - 1);
                            double a = phase + t * l.turns * Math.PI * 2 + st * Math.PI;
                            double r = l.radius + jit(l.radiusJitter);
                            out.add(new Pt(l, r * Math.cos(a), l.height + t * l.rise + jit(l.yJitter), r * Math.sin(a), per(l)));
                        }
                }
                default -> {
                    double step = l.step > 0 ? l.step : 360.0 / l.count;
                    for (int i = 0; i < l.count; i++) {
                        double a = phase + ang(l, i * step);
                        double r = l.radius + jit(l.radiusJitter);
                        out.add(new Pt(l, r * Math.cos(a), l.height + jit(l.yJitter), r * Math.sin(a), per(l)));
                    }
                }
            }
        }
        return out;
    }

    private void spawnFrame(Location c, List<Pt> pts) {
        World w = c.getWorld();
        if (w == null) return;
        boolean force = pl.cfg.config().getBoolean("particles.force", false);
        for (Pt p : pts) {
            Layer l = p.l();
            Object data = null;
            if (l.particle == Particle.DUST) data = new Particle.DustOptions(l.color, l.size);
            else if (l.particle == Particle.DUST_COLOR_TRANSITION) data = new Particle.DustTransition(l.color, l.toColor, l.size);
            w.spawnParticle(l.particle, c.getX() + p.x(), c.getY() + p.y(), c.getZ() + p.z(), p.n(), 0, 0, 0, 0, data, force);
        }
    }

    /** Plays one cycle on the calling (owning) thread. */
    public void play(Location c, List<Layer> layers, long cycle) { spawnFrame(c, compute(layers, cycle)); }

    /** Same, but computes off-thread when particles.compute-async is on. Must be called from the region thread of c. */
    public void playMaybeAsync(Location c, List<Layer> layers, long cycle) {
        if (!pl.cfg.config().getBoolean("particles.compute-async", false)) { play(c, layers, cycle); return; }
        Location loc = c.clone();
        pl.sched.async(() -> {
            List<Pt> f = compute(layers, cycle);
            pl.sched.region(loc, () -> spawnFrame(loc, f));
        });
    }

    /** Repeating aura on every online player until alive returns false. */
    public ScheduledTask aura(List<Layer> layers, int interval, BooleanSupplier alive) {
        AtomicLong cyc = new AtomicLong();
        return pl.sched.globalTimer(t -> {
            if (!alive.getAsBoolean()) { t.cancel(); return; }
            long c = cyc.getAndIncrement();
            for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> playMaybeAsync(p.getLocation(), layers, c));
        }, 1, interval);
    }

    /** Plays layers around every player for a limited number of ticks (event start animation). */
    public void animate(List<Layer> layers, int durationTicks, int interval) {
        if (layers.isEmpty()) return;
        AtomicLong cyc = new AtomicLong();
        pl.sched.globalTimer(t -> {
            long c = cyc.getAndIncrement();
            if (c * interval > durationTicks) { t.cancel(); return; }
            for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> playMaybeAsync(p.getLocation(), layers, c));
        }, 1, interval);
    }
}
