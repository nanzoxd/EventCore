package com.eventcore.airdrop;

import com.eventcore.EventCorePlugin;
import com.eventcore.airdrop.AirdropEvent.State;
import com.eventcore.util.BossBars;
import com.eventcore.util.ParticleEngine;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One crate. FALLING (timed descent under a chicken parachute, locked, invulnerable) -> LOCKED (optional grace) ->
 * READY (virtual HP, spam-hit to open) -> OPENED (loot straight into the opener's inventory).
 * The fall always takes exactly fall.duration-minutes, whatever the height. Parachute idea taken from Airdrop 4.x:
 * chickens leashed to the crate (shulker). All entity work runs on the region scheduler of the
 * landing spot (x/z never change during the fall, so the region never changes).
 */
public final class AirdropCrate {
    /** PDC marker on parachute entities so other listeners can protect them. */
    public static final NamespacedKey CHUTE = new NamespacedKey("eventcore", "chute");

    private final EventCorePlugin pl;
    private final AirdropEvent owner;
    private final String tier, code;
    private final Location ground;
    private Shulker shulker;
    private TextDisplay holo;
    private final List<Chicken> chickens = new ArrayList<>();
    private ScheduledTask task;
    public volatile State state = State.FALLING;
    private int tick, fallTicks, hp, maxHp;
    private double fallHeight;
    private final Map<UUID, Integer> damage = new ConcurrentHashMap<>();
    private final Map<UUID, String> hitterNames = new ConcurrentHashMap<>();
    private long lockUntil, expireAt;
    private String lastHolo = "";
    private final Map<UUID, Long> denyCd = new ConcurrentHashMap<>();
    private List<ParticleEngine.Layer> flare, smoke, marker;

    AirdropCrate(EventCorePlugin pl, AirdropEvent owner, String tier, Location ground, String code) {
        this.pl = pl; this.owner = owner; this.tier = tier; this.ground = ground; this.code = code;
    }

    private ConfigurationSection s() { return owner.settings(); }
    private ConfigurationSection t() { return owner.tierSec(tier); }

    public String tier() { return tier; }

    public String coords() { return ground.getBlockX() + " " + ground.getBlockY() + " " + ground.getBlockZ(); }
    public int secondsLeft() { return Math.max(0, (int) Math.ceil((fallTicks - tick) / 20.0)); }
    public double fallProgress() { return fallTicks <= 0 ? 1 : Math.min(1.0, tick / (double) fallTicks); }

    /** HP bar: filled part in the tier gradient, empty part soft peach. */
    private Component hpBar() {
        int w = 12;
        int f = maxHp == 0 ? 0 : (int) Math.round(w * (hp / (double) maxHp));
        List<String> g = t().getStringList("gradient");
        Component filled = f > 0 ? pl.text.gradient("█".repeat(f), g.isEmpty() ? pl.text.palette() : g, 0) : Component.empty();
        return Component.empty().append(filled).append(Component.text("█".repeat(w - f), TextColor.color(0xffe4d5)));
    }

    private Map<String, Object> ph() {
        return Text.ph("tier", owner.tierName(tier), "tier_plain", Util.title(tier), "code", code, "world", ground.getWorld().getName(),
                "x", ground.getBlockX(), "y", ground.getBlockY(), "z", ground.getBlockZ(),
                "hp", hp, "max", maxHp, "bar", hpBar(),
                "time", secondsLeft(), "time_fmt", AirdropEvent.fmtTime(secondsLeft()),
                "seconds", Math.max(0, (lockUntil - System.currentTimeMillis()) / 1000));
    }

    void spawn() {
        try { doSpawn(); }
        catch (RuntimeException ex) { destroy(false); throw ex; }
    }

    private void doSpawn() {
        World w = ground.getWorld();
        double height = owner.fallHeight();
        fallHeight = height;
        fallTicks = Math.max(1, owner.fallSeconds() * 20);
        try { w.addPluginChunkTicket(ground.getBlockX() >> 4, ground.getBlockZ() >> 4, pl); } catch (Throwable ignored) { }
        maxHp = Math.max(1, t().getInt("health", 10));
        hp = maxHp;
        flare = pl.particles.read(s().getConfigurationSection("fall.flare"));
        smoke = pl.particles.read(s().getConfigurationSection("land.smoke"));
        marker = buildMarker();
        Location start = ground.clone().add(0, height, 0);
        DyeColor dye = BossBars.enumOf(DyeColor.class, t().getString("shulker-color", "PURPLE"), DyeColor.PURPLE);
        shulker = w.spawn(start, Shulker.class, sh -> {
            sh.setAI(false); sh.setGravity(false); sh.setInvulnerable(true); sh.setSilent(true);
            sh.setPersistent(false); sh.setRemoveWhenFarAway(false); sh.setColor(dye); sh.setPeek(0f);
            sh.getPersistentDataContainer().set(CHUTE, PersistentDataType.BYTE, (byte) 2);
        });
        spawnParachute(w, start);
        if (s().getBoolean("hologram.enabled", true)) {
            holo = w.spawn(start.clone().add(0, hologramOffset(), 0), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setLineWidth(220);
                float sc = (float) s().getDouble("hologram.scale", 1.1);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
            });
        }
        owner.crates.put(shulker.getUniqueId(), this);
        task = pl.sched.regionTimer(ground, tk -> step(), 1, 1);
        // tell everyone where it is coming down (exactly once; a failure here must never destroy the crate,
        // otherwise the event would retry and announce a second drop)
        try { announceFalling(); }
        catch (Throwable ex) { pl.getLogger().warning("Airdrop: falling announcement failed: " + ex); }
    }

    private void announceFalling() {
        ConfigurationSection an = s().getConfigurationSection("fall.announce");
        if (an != null) {
            Map<String, Object> ph = ph();
            if (an.contains("chat")) pl.fx.broadcast(an, "chat", ph, pl.cfg.messages().getString("prefix", ""));
            if (an.getBoolean("show-title", true)) pl.fx.title(Bukkit.getServer(), an.getConfigurationSection("title"), ph);
        }
        pl.discord.crateFalling(Util.title(tier), ground.getWorld().getName(), coords(), (int) Math.ceil(fallTicks / 20.0));
    }

    /** Ring on the surface that shows where the crate will land (tier coloured, visible from far away). */
    private List<ParticleEngine.Layer> buildMarker() {
        ConfigurationSection m = s().getConfigurationSection("fall.marker");
        if (m == null || !m.getBoolean("enabled", true)) return List.of();
        List<ParticleEngine.Layer> l = pl.particles.read(m.getConfigurationSection("layers"));
        if (m.getBoolean("use-tier-color", true)) {
            List<String> g = t().getStringList("gradient");
            if (!g.isEmpty()) {
                try {
                    Color c = Color.fromRGB(Integer.parseInt(g.get(g.size() - 1).replace("#", ""), 16));
                    for (ParticleEngine.Layer x : l) { x.color = c; x.toColor = c; }
                } catch (Exception ignored) { }
            }
        }
        return l;
    }

    /** Plays a sound so that it carries `range` blocks (volume above 1 extends the audible distance). */
    private void playLoud(ConfigurationSection sec, double range, double x, double y, double z) {
        var so = pl.fx.sound(sec);
        if (so == null) return;
        // range <= 0: play at the configured volume as-is (quiet). range > 0: volume is raised so it carries that far.
        float vol = range <= 0 ? so.volume() : (float) Math.max(so.volume(), range / 16.0);
        ground.getWorld().playSound(net.kyori.adventure.sound.Sound.sound(so.name(), so.source(), vol, so.pitch()), x, y, z);
    }

    private double hologramOffset() { return s().getDouble("hologram.offset", 1.9); }

    // ---------- parachute ----------
    private int chuteCount() { return Math.max(0, Math.min(16, s().getInt("parachute.chickens", 5))); }
    private double chuteHeight() { return s().getDouble("parachute.height", 3.2); }

    private Location chickenPos(Location base, int i, int n) {
        double a = Math.PI * 2 * i / Math.max(1, n);
        double r = s().getDouble("parachute.radius", 1.4);
        double bob = Math.sin(tick * 0.15 + i) * 0.12;
        return base.clone().add(Math.cos(a) * r, chuteHeight() + bob, Math.sin(a) * r);
    }

    private void spawnParachute(World w, Location start) {
        int n = chuteCount();
        if (n == 0 || !s().getBoolean("parachute.enabled", true)) return;
        for (int i = 0; i < n; i++) {
            Chicken c = w.spawn(chickenPos(start, i, n), Chicken.class, ch -> {
                ch.setAI(false); ch.setGravity(false); ch.setInvulnerable(true); ch.setSilent(true);
                ch.setPersistent(false); ch.setCollidable(false);
                ch.setEggLayTime(Integer.MAX_VALUE); // parachute chickens never lay eggs
                ch.getPersistentDataContainer().set(CHUTE, PersistentDataType.BYTE, (byte) 1);
            });
            try { c.setLeashHolder(shulker); } catch (Throwable ignored) { }
            chickens.add(c);
        }
    }

    private void moveParachute(Location base) {
        for (int i = 0; i < chickens.size(); i++) {
            Chicken c = chickens.get(i);
            if (!c.isValid()) continue;
            c.teleportAsync(chickenPos(base, i, chickens.size()));
            c.setEggLayTime(Integer.MAX_VALUE);
            // the lead is tied to the crate (shulker); put it back if it ever vanished
            if (tick % 10 == 0 && shulker != null && shulker.isValid() && (!c.isLeashed() || c.getLeashHolder() != shulker)) {
                try { c.setLeashHolder(shulker); } catch (Throwable ignored) { }
            }
        }
    }

    private void releaseParachute() {
        for (Chicken c : chickens) {
            if (!c.isValid()) continue;
            try { c.setLeashHolder(null); } catch (Throwable ignored) { }
            ground.getWorld().spawnParticle(Particle.CLOUD, c.getLocation(), 4, 0.2, 0.2, 0.2, 0.02);
            c.remove();
        }
        chickens.clear();
    }

    // ---------- tick ----------
    private void step() {
        if (shulker == null || !shulker.isValid()) { destroy(false); return; }
        long now = System.currentTimeMillis();
        if (state == State.FALLING) {
            tick++;
            double f = Math.min(1.0, tick / (double) fallTicks);
            if (s().getString("fall.easing", "LINEAR").equalsIgnoreCase("EASE_IN")) f = f * f;
            double top = ground.getY() + fallHeight;
            Location l = new Location(ground.getWorld(), ground.getX(), top - (top - ground.getY()) * f, ground.getZ());
            shulker.teleportAsync(l);
            if (holo != null) holo.teleportAsync(l.clone().add(0, hologramOffset() + (chuteCount() > 0 ? chuteHeight() : 0), 0));
            moveParachute(l);
            ConfigurationSection trail = owner.resolve(tier, "trail", "fall.trail");
            if (trail != null && tick % Math.max(1, s().getInt("fall.trail-interval-ticks", 2)) == 0) pl.particles.play(l.clone(), List.of(pl.particles.readLayer("trail", trail)), tick);
            if (tick % 10 == 0 && !flare.isEmpty()) pl.particles.play(ground.clone(), flare, tick);
            int iv = Math.max(1, s().getInt("fall.sound-interval-ticks", 20));
            // quiet, low-pitched wind only during the last stretch above the ground
            if (tick % iv == 0 && l.getY() - ground.getY() <= s().getDouble("fall.sound-height", 25.0)) {
                playLoud(s().getConfigurationSection("fall.sound"), s().getDouble("fall.sound-range", 64.0), l.getX(), l.getY(), l.getZ());
            }
            if (tick >= fallTicks) land();
        } else if (state == State.LOCKED && now >= lockUntil) {
            state = State.READY;
        } else if (state != State.OPENED) {
            tick++;
            if (tick % 20 == 0 && !smoke.isEmpty()) pl.particles.play(ground.clone(), smoke, tick);
            // low-pitched elytra wind next to the landed crate
            if (tick % Math.max(1, s().getInt("land.ambient-interval-ticks", 30)) == 0) {
                playLoud(s().getConfigurationSection("land.ambient-sound"), s().getDouble("land.ambient-range", 100.0), ground.getX(), ground.getY(), ground.getZ());
            }
        }
        if (state != State.OPENED && !marker.isEmpty() && tick % Math.max(1, s().getInt("fall.marker.interval-ticks", 10)) == 0)
            pl.particles.play(ground.clone(), marker, tick);
        if (state != State.OPENED && expireAt > 0 && now >= expireAt) { destroy(false); return; }
        if (tick % 5 == 0 || state == State.READY) updateHolo();
    }

    private void land() {
        int lock = s().getInt("land.lock-seconds", 3);
        state = lock > 0 ? State.LOCKED : State.READY;
        lockUntil = System.currentTimeMillis() + lock * 1000L;
        int exp = s().getInt("land.expire-seconds", 600);
        expireAt = exp > 0 ? System.currentTimeMillis() + exp * 1000L : 0;
        shulker.teleportAsync(ground);
        if (holo != null) holo.teleportAsync(ground.clone().add(0, hologramOffset(), 0));
        releaseParachute();
        World w = ground.getWorld();
        playLoud(owner.resolve(tier, "sounds.land", "land.sound"), s().getDouble("land.sound-range", 160.0), ground.getX(), ground.getY(), ground.getZ());
        ConfigurationSection lp = t().getConfigurationSection("land-particles");
        if (lp != null) pl.particles.play(ground.clone(), pl.particles.read(lp), 0);
        ground.getWorld().spawnParticle(Particle.EXPLOSION, ground.clone().add(0, 0.5, 0), 3, 0.6, 0.3, 0.6, 0);
        shockwave();
        Map<String, Object> ph = ph();
        if (s().getBoolean("land.broadcast-title", true)) pl.fx.title(Bukkit.getServer(), s().getConfigurationSection("land.title"), ph);
        if (s().contains("land.chat")) pl.fx.broadcast(s(), "land.chat", ph, pl.cfg.messages().getString("prefix", ""));
        pl.discord.crateLanded(Util.title(tier), ground.getWorld().getName(), coords(), maxHp);
    }

    private void shockwave() {
        ConfigurationSection sw = s().getConfigurationSection("land.shockwave");
        if (sw == null || !sw.getBoolean("enabled", true) || sw.getConfigurationSection("layer") == null) return;
        ParticleEngine.Layer layer = pl.particles.readLayer("shockwave", sw.getConfigurationSection("layer"));
        int ticks = Math.max(1, sw.getInt("ticks", 12));
        double a = sw.getDouble("radius-start", 0.5), b = sw.getDouble("radius-end", 7.0);
        int[] i = {0};
        pl.sched.regionTimer(ground, tk -> {
            i[0]++;
            layer.radius = a + (b - a) * (i[0] / (double) ticks);
            pl.particles.play(ground, List.of(layer), 0);
            if (i[0] >= ticks) tk.cancel();
        }, 1, 1);
    }

    private void updateHolo() {
        if (holo == null || !holo.isValid()) return;
        String key = state == State.FALLING ? "falling" : state == State.LOCKED ? "locked" : "ready";
        Map<String, Object> ph = ph();
        String sig = key + ph.get("time") + ph.get("seconds") + hp;
        if (sig.equals(lastHolo)) return;
        lastHolo = sig;
        holo.text(pl.text.render(s().getString("hologram." + key, ""), ph));
    }

    private void deny(Player p, String key) {
        if (!s().getBoolean("messages.show-still-falling", true)) return;
        long now = System.currentTimeMillis();
        Long last = denyCd.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        denyCd.put(p.getUniqueId(), now);
        p.sendActionBar(pl.text.render(s().getString("messages." + key, key), ph()));
    }

    public void interact(Player p) {
        if (state == State.FALLING) deny(p, "still-falling");
        else if (state == State.LOCKED) deny(p, "locked");
    }

    public void hit(Player p) {
        switch (state) {
            case FALLING -> deny(p, "still-falling");
            case LOCKED -> deny(p, "locked");
            case READY -> {
                int dmg = Math.max(1, s().getInt("hit.damage-per-hit", 1));
                hp -= dmg;
                boolean first = damage.putIfAbsent(p.getUniqueId(), 0) == null;
                damage.merge(p.getUniqueId(), dmg, Integer::sum);
                hitterNames.put(p.getUniqueId(), p.getName());
                if (first && s().getBoolean("hit.announce-first-hit", true) && hp > 0) {
                    Map<String, Object> hph = ph();
                    hph.put("player", p.getName());
                    pl.fx.broadcast(s(), "hit.first-chat", hph, pl.cfg.messages().getString("prefix", ""));
                }
                var so = pl.fx.sound(owner.resolve(tier, "sounds.hit", "hit.sound"));
                if (so != null) ground.getWorld().playSound(so, ground.getX(), ground.getY(), ground.getZ());
                if (hp <= 0) { hp = 0; open(p); return; }
                updateHolo();
                p.sendActionBar(pl.text.render(s().getString("hit.actionbar", "{bar} {hp}/{max}"), ph()));
            }
            default -> { }
        }
    }

    private void open(Player p) {
        if (state == State.OPENED) return;
        state = State.OPENED;
        LootService.Result r = pl.loot.roll(tier);
        for (ItemStack it : r.items()) Util.give(p, it);
        for (String c : r.commands()) pl.sched.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName())));
        var so = pl.fx.sound(owner.resolve(tier, "sounds.open", "open.sound"));
        Map<String, Object> ph = ph();
        ph.put("player", p.getName());
        pl.sched.region(ground, () -> {
            if (so != null) ground.getWorld().playSound(so, ground.getX(), ground.getY(), ground.getZ());
            if (shulker != null && shulker.isValid()) shulker.setPeek(1f);
            ConfigurationSection op = s().getConfigurationSection("open.particles");
            if (op != null) pl.particles.play(ground.clone(), pl.particles.read(op), 0);
        });
        pl.fx.broadcast(s(), "open.chat", ph, pl.cfg.messages().getString("prefix", ""));
        announceTopHitters();
        pl.discord.crateOpened(Util.title(tier), p.getName(), coords());
        pl.sched.regionLater(ground, () -> destroy(false), 20);
    }

    /** Chat ranking of who hit this crate the most (1-2-3 by default). */
    private void announceTopHitters() {
        int n = Math.max(1, s().getInt("hit.top-count", 3));
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(damage.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (top.isEmpty()) return;
        String prefix = pl.cfg.messages().getString("prefix", "");
        Map<String, Object> hp0 = ph();
        Bukkit.getServer().sendMessage(pl.text.render(prefix + s().getString("hit.top-header", "<g>top hitters of the {tier_plain} lootdrop:</g>"), hp0));
        for (int i = 0; i < Math.min(n, top.size()); i++) {
            Map<String, Object> ph = ph();
            ph.put("rank", i + 1);
            ph.put("player", hitterNames.getOrDefault(top.get(i).getKey(), "?"));
            ph.put("damage", top.get(i).getValue());
            Bukkit.getServer().sendMessage(pl.text.render(prefix + s().getString("hit.top-line", "<g>#{rank} {player} - {damage} hits</g>"), ph));
        }
    }

    /** direct = true during plugin disable (schedulers are unavailable then). */
    public void destroy(boolean direct) {
        state = State.OPENED;
        Sched.cancel(task);
        if (shulker != null) owner.crates.remove(shulker.getUniqueId());
        Runnable rm = () -> {
            releaseParachute();
            if (shulker != null) shulker.remove();
            if (holo != null) holo.remove();
            try { ground.getWorld().removePluginChunkTicket(ground.getBlockX() >> 4, ground.getBlockZ() >> 4, pl); } catch (Throwable ignored) { }
        };
        if (direct) { try { rm.run(); } catch (Throwable ignored) { } }
        else pl.sched.region(ground, rm);
    }
}
