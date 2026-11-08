package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Rewards;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * Natural Disaster: last player standing inside a shrinking border.
 * <p>
 * The border is a virtual WorldBorder sent only to the event's players (Player#setWorldBorder), so it is drawn by the
 * client exactly like the vanilla border and animates smoothly, while the real world border is never read or changed.
 * Containment is enforced by this class (the virtual border is visual only).
 * <p>
 * Everything a player is given during the event (border, spectator mode, flight lock, "inside the arena" mark) is
 * also written to the player's persistent data, so a crash or restart can never leave anyone stuck: the next join
 * (or the end of the event) sweeps it. Command restrictions live in memory only and are tied to the event being active.
 */
public final class NaturalDisasterEvent extends GameEvent {
    private enum Reason { DEATH, LEFT, BORDER, DISCONNECT }
    private enum Result { WINNER, NONE, CANCELLED, ABORTED }

    private static final class P {
        final UUID id;
        volatile String name;
        volatile boolean alive = true, entered, left, settled;
        volatile int kills, outside, eliminatedAt;
        volatile double health = 20, dist;
        volatile Reason reason;
        volatile String killer = "";
        P(UUID id, String name) { this.id = id; this.name = name; }
    }

    private final Map<UUID, P> players = new ConcurrentHashMap<>();
    private final Set<UUID> signups = ConcurrentHashMap.newKeySet();
    private final Set<UUID> internalTp = ConcurrentHashMap.newKeySet();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final NamespacedKey keyExit, keyGm, keyBorder, keyFly;

    private volatile ActiveEvent run;
    private volatile World world;
    private volatile double cx, cz, initialR = 50;
    private volatile List<double[]> windows = List.of();     // {startSec, endSec, fromRadius, toRadius}
    private volatile WorldBorder border;
    private volatile int lastShrink = -1;
    private volatile Result result;
    private volatile List<UUID> winners = List.of();
    private volatile int lastStrike = -1;
    private final DisasterHazards fx;

    public NaturalDisasterEvent(EventCorePlugin pl) {
        super(pl, "natural_disaster");
        keyExit = new NamespacedKey(pl, "nd_exit");
        keyGm = new NamespacedKey(pl, "nd_gm");
        keyBorder = new NamespacedKey(pl, "nd_border");
        keyFly = new NamespacedKey(pl, "nd_fly");
        fx = new DisasterHazards(pl, this);
    }

    @Override public int minMinutes() { return 1; }

    // =====================================================================================================
    // public API (commands, other events)
    // =====================================================================================================
    public boolean isMember(UUID id) {
        if (run == null) return false;
        P p = players.get(id);
        return p != null && !p.left;
    }

    public boolean running() { return run != null; }
    public boolean signUp(Player p) { return signups.add(p.getUniqueId()); }
    public boolean unSignUp(UUID id) { return signups.remove(id); }
    public int signupCount() { return signups.size(); }
    public boolean optIn() { return "OPTIN".equalsIgnoreCase(settings().getString("participants.mode", "ALL")); }
    public int maxMinutes() { return Math.max(1, settings().getInt("max-minutes", 60)); }

    /** Null when the event can start, otherwise a key under messages.yml. */
    public String preflight() {
        if (Bukkit.getWorld(settings().getString("center.world", "")) == null) return "disaster.not-configured";
        return null;
    }

    public Map<String, Object> statusPlaceholders() {
        ActiveEvent ae = run;
        if (ae == null) return Text.ph("state", "idle", "time", "-", "alive", 0, "total", 0, "radius", (int) initialR, "next", "-", "signups", signups.size());
        double r = radiusAt(elapsed(ae));
        return Text.ph("state", "running", "time", Util.mmss(ae.secondsLeft()), "alive", aliveCount(), "total", players.size(),
                "radius", (int) Math.round(r), "next", nextText(elapsed(ae)), "signups", signups.size());
    }

    /** Safety net used by /disaster reset: strips border, spectator mode and marks from every online player. */
    public void sweepAll() {
        signups.clear();
        for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> cleanup(p, true));
    }

    @Override
    public Map<String, Object> extra(ActiveEvent ae) {
        double t = elapsed(ae);
        return Text.ph("alive", aliveCount(), "total", players.size(), "radius", (int) Math.round(radiusAt(t)),
                "size", (int) Math.round(radiusAt(t) * 2), "next", nextText(t));
    }

    // ---- used by DisasterHazards ----
    boolean isLive(ActiveEvent ae) { return run == ae; }
    double cx() { return cx; }
    double cz() { return cz; }
    double radiusNow(ActiveEvent ae) { return radiusAt(elapsed(ae)); }
    int phaseIndex(double t) { int i = 0; for (double[] w : windows) if (t >= w[1]) i++; return i; }

    // =====================================================================================================
    // geometry + timeline
    // =====================================================================================================
    private double elapsed(ActiveEvent ae) { return Math.max(0, (System.currentTimeMillis() - ae.start) / 1000.0); }

    private double outside(double x, double z, double r) { return Math.max(Math.abs(x - cx), Math.abs(z - cz)) - r; }

    private boolean inArena(Location l) {
        World w = world;
        return w != null && l.getWorld() != null && l.getWorld().equals(w) && outside(l.getX(), l.getZ(), initialR + 2) <= 0;
    }

    private List<double[]> buildWindows(ConfigurationSection s) {
        List<double[]> phases = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("border.phases")) {
            if (m.get("minutes") instanceof Number mi && m.get("radius") instanceof Number ra) phases.add(new double[]{mi.doubleValue() * 60.0, ra.doubleValue()});
        }
        phases.sort(Comparator.comparingDouble(a -> a[0]));
        double shrink = Math.max(1, s.getDouble("border.shrink-seconds", 120));
        List<double[]> out = new ArrayList<>();
        double r = initialR, prev = 0;
        for (double[] ph : phases) {
            double start = Math.max(prev, ph[0] - shrink);      // every reduction ends exactly at its table time
            out.add(new double[]{start, ph[0], r, ph[1]});
            r = ph[1]; prev = ph[0];
        }
        return out;
    }

    private double radiusAt(double t) {
        double r = initialR;
        for (double[] w : windows) {
            if (t <= w[0]) return w[2];
            if (t < w[1]) return w[2] + (w[3] - w[2]) * ((t - w[0]) / (w[1] - w[0]));
            r = w[3];
        }
        return r;
    }

    private String nextText(double t) {
        for (double[] w : windows) {
            if (t >= w[0] && t < w[1]) return pl.text.sc("shrinking");
            if (w[0] > t) return Util.mmss((int) Math.ceil(w[0] - t));
        }
        return "-";
    }

    private int aliveCount() {
        int n = 0;
        for (P p : players.values()) if (p.alive && !p.left) n++;
        return n;
    }

    // =====================================================================================================
    // messaging
    // =====================================================================================================
    void tellMembers(String path, Map<String, Object> ph) {
        if (pl.fx.msg(pl.cfg.messages(), path).tpl().isBlank()) return;
        var c = pl.msgMap(path, ph);
        for (P p : players.values()) {
            if (p.left) continue;
            Player o = Bukkit.getPlayer(p.id);
            if (o != null) o.sendMessage(c);
        }
    }

    private void soundMembers(ConfigurationSection sec) {
        for (P p : players.values()) {
            if (p.left) continue;
            Player o = Bukkit.getPlayer(p.id);
            if (o != null) pl.fx.play(o, sec);
        }
    }

    private void broadcast(String path, Map<String, Object> ph) {
        pl.fx.broadcast(pl.cfg.messages(), path, ph, pl.cfg.messages().getString("prefix", ""));
    }

    // =====================================================================================================
    // start
    // =====================================================================================================
    private void abort(String path, Object... kv) {
        result = Result.ABORTED;
        Bukkit.getServer().sendMessage(pl.msg(path, kv));
        pl.sched.global(() -> pl.events.stop(id(), false));
    }

    @Override
    public void onStart(ActiveEvent ae) {
        players.clear(); warned.clear();
        fx.reset();
        result = null; winners = List.of(); lastShrink = -1; lastStrike = -1;
        run = ae;
        ConfigurationSection s = settings();
        World w = Bukkit.getWorld(s.getString("center.world", ""));
        if (w == null) { pl.getLogger().warning("Natural Disaster: set the centre first (/disaster setcenter)."); abort("disaster.not-configured"); return; }
        world = w;
        cx = s.getDouble("center.x", 0); cz = s.getDouble("center.z", 0);
        initialR = Math.max(5, s.getDouble("border.initial-radius", 50));
        windows = buildWindows(s);

        boolean optin = optIn();
        List<Player> chosen = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (eligible(p, s, optin)) chosen.add(p);
        int min = Math.max(1, s.getInt("participants.min-players", 2));
        if (chosen.size() < min) { signups.clear(); abort("disaster.not-enough-players", "min", min, "count", chosen.size()); return; }

        WorldBorder b = Bukkit.createWorldBorder();       // virtual: never touches world.getWorldBorder()
        b.setCenter(cx, cz);
        b.setSize(initialR * 2.0);
        b.setWarningDistance(Math.max(0, s.getInt("border.warning-distance", 5)));
        b.setWarningTime(15);
        border = b;
        for (Player p : chosen) players.put(p.getUniqueId(), new P(p.getUniqueId(), p.getName()));
        signups.clear();
    }

    private boolean eligible(Player p, ConfigurationSection s, boolean optin) {
        if (p.hasPermission("eventcore.disaster.exempt")) return false;
        for (String g : s.getStringList("participants.exclude-gamemodes")) if (p.getGameMode().name().equalsIgnoreCase(g.trim())) return false;
        return !optin || signups.contains(p.getUniqueId());
    }

    /** Runs on each player's own thread right after the event starts, on respawn and on rejoin. */
    @Override
    protected void doApply(Player p) {
        if (run == null) return;
        P pp = players.get(p.getUniqueId());
        if (pp == null || pp.left) return;
        if (!pp.entered) { pp.entered = true; enter(p); }
        showBorder(p);
        if (!pp.alive) afterEliminated(p, pp);
    }

    private void enter(Player p) {
        PersistentDataContainer d = p.getPersistentDataContainer();
        d.set(keyExit, PersistentDataType.BYTE, (byte) 1);
        ConfigurationSection s = settings();
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            if (p.getAllowFlight()) d.set(keyFly, PersistentDataType.BYTE, (byte) 1);
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        if (s.getBoolean("participants.heal-on-start", true)) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(a == null ? 20 : a.getValue());
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
        }
        teleportIn(p);
        p.sendMessage(pl.msg("disaster.briefing", "radius", (int) initialR, "size", (int) initialR * 2));
    }

    private void teleportIn(Player p) {
        World w = world;
        ConfigurationSection s = settings();
        boolean spawnSet = s.getBoolean("arena.spawn.enabled", false);
        double bx = spawnSet ? s.getDouble("arena.spawn.x", cx) : cx, bz = spawnSet ? s.getDouble("arena.spawn.z", cz) : cz;
        double spread = Math.max(0, Math.min(s.getDouble("participants.spread-radius", 35), initialR - 3));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble(Math.PI * 2), dist = Math.sqrt(rnd.nextDouble()) * spread;
        int x = (int) Math.floor(bx + Math.cos(ang) * dist), z = (int) Math.floor(bz + Math.sin(ang) * dist);
        double lim = initialR - 2;
        final int fx = (int) Math.max(cx - lim, Math.min(cx + lim, x)), fz = (int) Math.max(cz - lim, Math.min(cz + lim, z));
        final boolean fixedY = spawnSet && "FIXED".equalsIgnoreCase(s.getString("arena.spawn.mode", "SURFACE"));
        final double fy = s.getDouble("arena.spawn.y", 64);
        final float yaw = rnd.nextFloat() * 360f;
        w.getChunkAtAsync(fx >> 4, fz >> 4, true).thenAccept(ch -> pl.sched.region(new Location(w, fx, 64, fz), () -> {
            double y = fixedY ? fy : w.getHighestBlockYAt(fx, fz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            tp(p, new Location(w, fx + 0.5, y, fz + 0.5, yaw, 0f));
        })).exceptionally(t -> {
            pl.getLogger().warning("Natural Disaster: could not place " + p.getName() + ": " + t);
            return null;
        });
    }

    private void tp(Player p, Location l) {
        UUID id = p.getUniqueId();
        internalTp.add(id);
        p.teleportAsync(l).whenComplete((ok, err) -> {
            try { pl.sched.globalLater(() -> internalTp.remove(id), 3); } catch (Throwable ignored) { internalTp.remove(id); }
        });
    }

    private Location exitLocation() {
        ConfigurationSection s = settings().getConfigurationSection("exit");
        World w = s == null ? null : Bukkit.getWorld(s.getString("world", ""));
        if (w != null && s.contains("x"))
            return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private void sendToExit(Player p, Runnable onOk) {
        Location l = exitLocation();
        if (l == null) { onOk.run(); return; }
        UUID id = p.getUniqueId();
        internalTp.add(id);
        p.teleportAsync(l).whenComplete((ok, err) -> {
            try {
                pl.sched.globalLater(() -> internalTp.remove(id), 3);
                if (Boolean.TRUE.equals(ok)) pl.sched.entity(p, onOk);       // the mark stays if this fails: the next join retries
            } catch (Throwable ignored) { internalTp.remove(id); }
        });
    }

    private void showBorder(Player p) {
        WorldBorder b = border;
        if (b == null) return;
        try {
            p.setWorldBorder(b);
            p.getPersistentDataContainer().set(keyBorder, PersistentDataType.BYTE, (byte) 1);
        } catch (Throwable t) {
            pl.getLogger().warning("Natural Disaster: could not show the border to " + p.getName() + ": " + t);
        }
    }

    private void resendBorders() {
        for (P pp : players.values()) {
            if (pp.left) continue;
            Player o = Bukkit.getPlayer(pp.id);
            if (o != null) pl.sched.entity(o, () -> showBorder(o));
        }
    }

    // =====================================================================================================
    // every second
    // =====================================================================================================
    @Override
    public void onSecond(ActiveEvent ae, int left) {
        if (run != ae) return;
        ConfigurationSection s = settings();
        double t = elapsed(ae);

        // border: start each reduction once, warn before it
        for (int i = 0; i < windows.size(); i++) {
            double[] w = windows.get(i);
            if (t >= w[0] && t < w[1] && i > lastShrink) {
                lastShrink = i;
                double remaining = Math.max(1, w[1] - t);
                WorldBorder b = border;
                if (b != null) { b.setSize(w[3] * 2.0, (long) Math.ceil(remaining)); resendBorders(); }
                tellMembers("disaster.shrinking", Text.ph("radius", (int) w[3], "size", (int) (w[3] * 2), "seconds", (int) Math.ceil(remaining)));
                soundMembers(s.getConfigurationSection("border.shrink-sound"));
            }
            if (w[0] > t) {
                int until = (int) Math.ceil(w[0] - t);
                for (int warn : s.getIntegerList("border.warnings")) {
                    if (until <= warn && until > warn - 3 && warned.add(i + ":" + warn)) {
                        tellMembers("disaster.warning", Text.ph("time", Util.mmss(until), "radius", (int) w[3], "size", (int) (w[3] * 2)));
                        soundMembers(s.getConfigurationSection("border.warning-sound"));
                    }
                }
                break;
            }
        }

        // players: containment, damage outside, display
        double radius = radiusAt(t);
        double entryGrace = s.getDouble("participants.entry-grace-seconds", 8);
        Map<String, Object> ph = Text.ph("time", Util.mmss(left), "radius", (int) Math.round(radius), "size", (int) Math.round(radius * 2),
                "next", nextText(t), "alive", aliveCount(), "total", players.size());
        String bar = pl.fx.msg(pl.cfg.messages(), "disaster.actionbar").tpl();
        for (P pp : players.values()) {
            if (pp.left) continue;
            Player p = Bukkit.getPlayer(pp.id);
            if (p == null) continue;
            if (!bar.isBlank()) p.sendActionBar(pl.text.render(bar, ph));
            if (pp.alive && pp.entered && t >= entryGrace) pl.sched.entity(p, () -> tickPlayer(ae, p, pp, radius));
        }
        hazards(s, t);
        List<Player> targets = new ArrayList<>();
        for (P pp : players.values()) {
            Player o = pp.alive && !pp.left && pp.entered ? Bukkit.getPlayer(pp.id) : null;
            if (o != null) targets.add(o);
        }
        fx.tick(ae, t, targets);
    }

    /**
     * Pushes a player away from the border: on every axis where they are closer than push.distance to the wall
     * (or already past it) they get a velocity pointing back toward the centre. Runs on the player's own thread.
     */
    private void pushFromBorder(Player p, Location at, double radius) {
        ConfigurationSection ps = settings().getConfigurationSection("border.push");
        if (ps != null && !ps.getBoolean("enabled", true)) return;
        double dist = ps == null ? 2.5 : Math.max(0.5, ps.getDouble("distance", 2.5));
        double str = ps == null ? 0.45 : Math.max(0.05, Math.min(2.0, ps.getDouble("strength", 0.45)));
        double dx = at.getX() - cx, dz = at.getZ() - cz;
        double innerX = radius - Math.abs(dx), innerZ = radius - Math.abs(dz);
        if (innerX >= dist && innerZ >= dist) return;
        org.bukkit.util.Vector v = p.getVelocity();
        double vx = v.getX(), vz = v.getZ();
        if (innerX < dist) vx = -Math.signum(dx) * str * Math.min(2.0, 1.0 + (dist - innerX) / dist);
        if (innerZ < dist) vz = -Math.signum(dz) * str * Math.min(2.0, 1.0 + (dist - innerZ) / dist);
        p.setVelocity(new org.bukkit.util.Vector(vx, v.getY(), vz));
    }

    private void tickPlayer(ActiveEvent ae, Player p, P pp, double radius) {
        if (run != ae || !pp.alive || pp.left) return;
        ConfigurationSection s = settings();
        Location l = p.getLocation();
        pp.health = p.getHealth();
        pp.dist = Math.max(Math.abs(l.getX() - cx), Math.abs(l.getZ() - cz));
        boolean outsideWorld = !l.getWorld().equals(world);
        if (!outsideWorld) pushFromBorder(p, l, radius);        // keeps even a standing player away from the shrinking wall
        if (outsideWorld || outside(l.getX(), l.getZ(), radius + 1.0) > 0) {
            pp.outside++;
            int limit = s.getInt("elimination.out-of-border-seconds", 15);
            if (limit > 0 && pp.outside >= limit) {
                eliminate(pp, Reason.BORDER, null, null);
                afterEliminated(p, pp);
                return;
            }
            p.sendActionBar(pl.msg("disaster.outside", "seconds", Math.max(0, limit - pp.outside)));
            double dmg = s.getDouble("elimination.out-of-border-damage", 2.0);
            if (dmg > 0) p.damage(dmg);
        } else pp.outside = 0;
    }

    private void hazards(ConfigurationSection s, double t) {
        ConfigurationSection h = s.getConfigurationSection("hazards.lightning");
        if (h == null || !h.getBoolean("enabled", false)) return;
        int sec = (int) t;
        if (sec == lastStrike || sec % Math.max(3, h.getInt("interval-seconds", 20)) != 0) return;
        lastStrike = sec;
        List<P> alive = new ArrayList<>();
        for (P pp : players.values()) if (pp.alive && !pp.left && pp.entered) alive.add(pp);
        if (alive.isEmpty()) return;
        Player target = Bukkit.getPlayer(alive.get(ThreadLocalRandom.current().nextInt(alive.size())).id);
        if (target == null) return;
        double off = Math.max(0, h.getDouble("offset", 6)), dmg = h.getDouble("damage", 6), rad = Math.max(1, h.getDouble("radius", 3));
        pl.sched.entity(target, () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location at = target.getLocation().add(r.nextDouble(-off, off), 0, r.nextDouble(-off, off));
            pl.sched.region(at, () -> {
                at.setY(at.getWorld().getHighestBlockYAt(at));
                at.getWorld().strikeLightningEffect(at);          // effect only: no fire, no block damage
                for (Entity en : at.getWorld().getNearbyEntities(at, rad, rad, rad))
                    if (en instanceof Player hit && isMember(hit.getUniqueId()) && hit.getGameMode() != GameMode.SPECTATOR) hit.damage(dmg);
            });
        });
    }

    // =====================================================================================================
    // elimination + winning
    // =====================================================================================================
    private void eliminate(P pp, Reason why, UUID killerId, String killerName) {
        ActiveEvent ae = run;
        if (ae == null) return;
        synchronized (this) {
            if (!pp.alive) return;
            pp.alive = false;
            pp.reason = why;
            pp.eliminatedAt = (int) elapsed(ae);
            pp.killer = killerName == null ? "" : killerName;
            if (killerId != null) { P k = players.get(killerId); if (k != null && k != pp) k.kills++; }
        }
        Map<String, Object> ph = Text.ph("player", pp.name, "killer", pp.killer, "alive", aliveCount());
        if (why != Reason.DISCONNECT && !pp.left) {
            Player self = Bukkit.getPlayer(pp.id);
            if (self != null) self.sendMessage(pl.msgMap("disaster.you-eliminated", ph));
        }
        String path = switch (why) {
            case DEATH -> pp.killer.isEmpty() ? "disaster.eliminated-death" : "disaster.eliminated-killed";
            case BORDER -> "disaster.eliminated-border";
            case DISCONNECT -> "disaster.eliminated-disconnect";
            case LEFT -> "disaster.eliminated-left";
        };
        for (P o : players.values()) {
            if (o.left && o != pp) continue;
            Player m = Bukkit.getPlayer(o.id);
            if (m != null && o != pp) m.sendMessage(pl.msgMap(path, ph));
        }
        checkWin(ae);
    }

    /** Runs on the eliminated player's thread once they are alive again (after respawn) or at once for border kills. */
    private void afterEliminated(Player p, P pp) {
        if (pp.settled) return;
        pp.settled = true;
        if (pp.reason == Reason.LEFT || pp.reason == Reason.DISCONNECT) return;
        pl.fx.title(p, pl.cfg.messages().getConfigurationSection("disaster.eliminated-title"), Text.ph("alive", aliveCount()));
        if ("TELEPORT".equalsIgnoreCase(settings().getString("elimination.on-eliminated", "SPECTATE"))) {
            pp.left = true;
            cleanup(p, true);
        } else makeSpectator(p);
    }

    private void makeSpectator(Player p) {
        PersistentDataContainer d = p.getPersistentDataContainer();
        if (!d.has(keyGm, PersistentDataType.STRING) && p.getGameMode() != GameMode.SPECTATOR)
            d.set(keyGm, PersistentDataType.STRING, p.getGameMode().name());
        p.setGameMode(GameMode.SPECTATOR);
        showBorder(p);
    }

    private synchronized void checkWin(ActiveEvent ae) {
        if (run != ae || result != null) return;
        List<P> alive = new ArrayList<>();
        for (P p : players.values()) if (p.alive && !p.left) alive.add(p);
        if (alive.size() > 1) return;
        if (alive.size() == 1) { winners = List.of(alive.get(0).id); result = Result.WINNER; }
        else result = Result.NONE;
        pl.sched.global(() -> pl.events.stop(id(), true));
    }

    private void resolveByTimer() {
        ConfigurationSection s = settings();
        List<P> c = new ArrayList<>();
        for (P p : players.values()) if (p.alive && !p.left) c.add(p);
        if (c.isEmpty()) { result = Result.NONE; return; }
        for (String rule : s.getStringList("winner.tie-breaker")) {
            if (c.size() <= 1) break;
            switch (rule.trim().toUpperCase(Locale.ROOT)) {
                case "KILLS" -> c = best(c, p -> p.kills, true);
                case "HEALTH" -> c = best(c, p -> p.health, true);
                case "CENTER" -> c = best(c, p -> p.dist, false);
                default -> { }
            }
        }
        if (c.size() > 1 && !"DRAW".equalsIgnoreCase(s.getString("winner.fallback", "RANDOM")))
            c = List.of(c.get(ThreadLocalRandom.current().nextInt(c.size())));
        List<UUID> ids = new ArrayList<>();
        for (P p : c) ids.add(p.id);
        winners = ids;
        result = Result.WINNER;
    }

    private static List<P> best(List<P> in, ToDoubleFunction<P> f, boolean max) {
        double b = max ? -Double.MAX_VALUE : Double.MAX_VALUE;
        for (P p : in) b = max ? Math.max(b, f.applyAsDouble(p)) : Math.min(b, f.applyAsDouble(p));
        List<P> out = new ArrayList<>();
        for (P p : in) if (Math.abs(f.applyAsDouble(p) - b) < 1e-9) out.add(p);
        return out;
    }

    // =====================================================================================================
    // stop
    // =====================================================================================================
    @Override
    public void onStop(ActiveEvent ae) {
        if (run != ae) return;
        fx.stop();
        boolean shutdown = !pl.isEnabled();
        boolean timeUp = !shutdown && ae.secondsLeft() <= 0;
        synchronized (this) {
            if (result == null) {
                if (timeUp) resolveByTimer(); else result = Result.CANCELLED;
            }
        }
        run = null;                                  // from here on every restriction and listener is off
        Result res = result;
        List<UUID> won = winners;
        Map<UUID, P> snapshot = new ConcurrentHashMap<>(players);
        border = null;
        players.clear();
        signups.clear();
        warned.clear();

        if (res == Result.WINNER) {
            List<String> names = new ArrayList<>();
            int kills = 0;
            for (UUID id : won) { P p = snapshot.get(id); if (p != null) { names.add(p.name); kills = Math.max(kills, p.kills); } }
            Map<String, Object> ph = Text.ph("player", String.join(", ", names), "kills", kills, "count", names.size());
            broadcast(won.size() > 1 ? "disaster.draw" : timeUp ? "disaster.winner-timeup" : "disaster.winner", ph);
            pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("disaster.winner-title"), ph);
            Rewards.Entry reward = Rewards.section(settings().getConfigurationSection("rewards.winner"));
            for (UUID id : won) {
                P p = snapshot.get(id);
                if (p == null) continue;
                Player online = Bukkit.getPlayer(id);
                Rewards.give(pl, p.name, online, reward, Text.ph("uuid", id, "kills", p.kills));
                if (online != null && !reward.message().isBlank())
                    online.sendMessage(pl.text.render(pl.cfg.messages().getString("prefix", "") + reward.message(), ph));
            }
        } else if (res == Result.NONE) broadcast("disaster.no-winner", Text.ph());
        else if (res == Result.CANCELLED && !shutdown) broadcast("disaster.cancelled", Text.ph());
        // per-player clean-up (border, gamemode, flight, teleport to the destination) happens in doStrip, which the manager runs next
    }

    @Override protected void doStrip(Player p) { cleanup(p, true); }

    /** Stale marks (crash, restart, failed teleport) are swept on the next join. */
    @Override public void sweep(Player p) { cleanup(p, true); }

    private void cleanup(Player p, boolean toExit) {
        PersistentDataContainer d = p.getPersistentDataContainer();
        boolean any = d.has(keyBorder, PersistentDataType.BYTE) || d.has(keyGm, PersistentDataType.STRING)
                || d.has(keyFly, PersistentDataType.BYTE) || d.has(keyExit, PersistentDataType.BYTE);
        if (!any || p.isDead()) return;                    // a dead player is finished by the respawn handler
        if (d.has(keyBorder, PersistentDataType.BYTE)) {
            try { p.setWorldBorder(null); } catch (Throwable ignored) { }
            d.remove(keyBorder);
        }
        String gm = d.get(keyGm, PersistentDataType.STRING);
        if (gm != null) {
            try { p.setGameMode(GameMode.valueOf(gm)); } catch (IllegalArgumentException ignored) { }
            d.remove(keyGm);
        }
        if (d.has(keyFly, PersistentDataType.BYTE)) { p.setAllowFlight(true); d.remove(keyFly); }
        if (d.has(keyExit, PersistentDataType.BYTE)) {
            if (toExit) sendToExit(p, () -> d.remove(keyExit));
            else d.remove(keyExit);
        }
    }

    // =====================================================================================================
    // listeners
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (run == null) return;
        P pp = players.get(e.getEntity().getUniqueId());
        if (pp == null || pp.left || !pp.alive) return;
        Player k = e.getEntity().getKiller();
        eliminate(pp, Reason.DEATH, k == null ? null : k.getUniqueId(), k == null ? null : k.getName());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!p.getPersistentDataContainer().has(keyExit, PersistentDataType.BYTE)) return;
        P pp = players.get(p.getUniqueId());
        boolean spectate = run != null && pp != null && !pp.left && !pp.alive
                && !"TELEPORT".equalsIgnoreCase(settings().getString("elimination.on-eliminated", "SPECTATE"));
        World w = world;
        if (spectate && w != null) {
            e.setRespawnLocation(new Location(w, cx + 0.5, w.getHighestBlockYAt((int) Math.floor(cx), (int) Math.floor(cz)) + 20, cz + 0.5));
            return;
        }
        Location exit = exitLocation();
        if (exit != null) e.setRespawnLocation(exit);
        if (pp != null) pp.left = true;
        pl.sched.entityLater(p, () -> cleanup(p, false), 3);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        signups.remove(id);
        P pp = players.get(id);
        if (pp == null || pp.left || run == null) return;
        boolean wasAlive = pp.alive;
        pp.left = true;
        if (wasAlive) eliminate(pp, Reason.DISCONNECT, null, null);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (run == null || isMember(p.getUniqueId()) || p.hasPermission("eventcore.bypass")) return;
        pl.sched.entityLater(p, () -> {
            if (run != null && inArena(p.getLocation())) sendToExit(p, () -> { });      // logged out inside the arena
        }, 10);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        ActiveEvent ae = run;
        if (ae == null || !e.hasChangedPosition()) return;
        P pp = players.get(e.getPlayer().getUniqueId());
        if (pp == null || pp.left || !pp.alive || !pp.entered) return;
        Location to = e.getTo();
        if (to == null || !to.getWorld().equals(world)) return;
        double r = radiusAt(elapsed(ae)) + 0.4;
        pushFromBorder(e.getPlayer(), to, r - 0.4);         // nobody gets to stay close to the border
        double dTo = outside(to.getX(), to.getZ(), r);
        if (dTo <= 0) return;
        Location from = e.getFrom();
        double dFrom = outside(from.getX(), from.getZ(), r);
        if (dFrom > 0 && dTo < dFrom) return;             // walking back inside is always allowed
        e.setCancelled(true);                              // the wall: nobody walks out of the border
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (run == null) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location to = e.getTo();
        if (to == null || internalTp.contains(id)) return;
        P pp = players.get(id);
        boolean intoArena = inArena(to);
        if (pp != null && !pp.left) {
            if (intoArena || !pp.entered) return;
            switch (e.getCause()) {
                case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> {
                    e.setCancelled(true);
                    p.sendActionBar(pl.msg("disaster.cannot-leave"));
                }
                default -> {
                    if (settings().getBoolean("elimination.leave-arena-eliminates", true)) {
                        pp.left = true;                    // warp / command: they walked out of the event
                        eliminate(pp, Reason.LEFT, null, null);
                        pl.sched.entityLater(p, () -> cleanup(p, false), 5);
                    } else {
                        e.setCancelled(true);
                        p.sendActionBar(pl.msg("disaster.cannot-leave"));
                    }
                }
            }
            return;
        }
        if (intoArena && settings().getBoolean("protect-arena-entry", true) && !p.hasPermission("eventcore.bypass")) {
            e.setCancelled(true);
            p.sendMessage(pl.msg("disaster.arena-closed"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        ActiveEvent ae = run;
        if (ae == null || !(e.getEntity() instanceof Player victim) || !isMember(victim.getUniqueId())) return;
        Player attacker = e.getDamager() instanceof Player a ? a
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player a2 ? a2 : null;
        if (attacker == null || !isMember(attacker.getUniqueId())) return;
        if (elapsed(ae) < settings().getInt("participants.grace-seconds", 30)) {
            e.setCancelled(true);
            attacker.sendActionBar(pl.msg("disaster.grace"));
        }
    }

    // ---- command restrictions: whitelist, only while the event is active, only for event players ----
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (run == null) return;
        ConfigurationSection s = settings();
        if (!s.getBoolean("commands.restrict", true)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("eventcore.bypass")) return;
        boolean everyone = "EVERYONE".equalsIgnoreCase(s.getString("commands.scope", "EVENT_PLAYERS"));
        if (!everyone && !isMember(p.getUniqueId())) return;
        String msg = e.getMessage().trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String[] tok = msg.toLowerCase(Locale.ROOT).split("\\s+");
        if (tok.length == 0 || tok[0].isEmpty()) return;
        String root = tok[0].contains(":") ? tok[0].substring(tok[0].indexOf(':') + 1) : tok[0];
        if (allowed(root, s)) return;
        e.setCancelled(true);
        p.sendMessage(pl.msg("disaster.command-blocked", "command", "/" + root));
    }

    private boolean allowed(String root, ConfigurationSection s) {
        Set<String> allow = new HashSet<>();
        for (String a : s.getStringList("commands.allowed")) allow.add(a.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        allow.add("disaster");
        if (allow.contains(root)) return true;
        try {
            Command c = Bukkit.getCommandMap().getCommand(root);       // resolves server aliases (/ewarp -> warp)
            if (c == null) return false;
            if (allow.contains(c.getName().toLowerCase(Locale.ROOT))) return true;
            for (String a : c.getAliases()) if (allow.contains(a.toLowerCase(Locale.ROOT))) return true;
        } catch (Throwable ignored) { }
        return false;
    }
}
