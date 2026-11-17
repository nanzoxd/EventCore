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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crate you see is built from block displays (a 2x2x2 copper box with a frame and an X on every face, the same for every
 * tier) that tumble, tilted, while it falls; the invisible (scaled) shulker is only its hitbox. Hits ring like metal (copper, chain, shield) and shake the crate.
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
    private record Part(BlockDisplay e, Vector3f c, Vector3f s, Quaternionf r) { }   // one block display: centre, size and rotation inside the crate
    private final List<Part> parts = new ArrayList<>();   // the visible crate, built from entity displays
    private final List<Part> balloonParts = new ArrayList<>();   // balloon, ropes, burner: block displays that sway above the crate
    private SupplyDropRig rig;               // balloon + ropes + crate (resource-pack models), used when crate.model = balloon
    private double crateScale = 1.0;           // crate edge length in blocks (2 = a 2x2x2 crate)
    private float spin;                        // degrees, grows while falling
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
                "hp", hp, "max", maxHp, "pct", maxHp == 0 ? 0 : (int) Math.round(100.0 * hp / maxHp), "bar", hpBar(),
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
        crateScale = s().getBoolean("crate.enabled", true) ? Math.max(1.0, Math.min(8.0, s().getDouble("crate.size", 2.0))) : 1.0;
        if (balloonModel()) crateScale = 2.0;                              // the balloon models are made for a 2x2x2 crate
        marker = buildMarker();
        Location start = ground.clone().add(0, height, 0);
        DyeColor dye = BossBars.enumOf(DyeColor.class, t().getString("shulker-color", "PURPLE"), DyeColor.PURPLE);
        shulker = w.spawn(start, Shulker.class, sh -> {
            sh.setAI(false); sh.setGravity(false); sh.setInvulnerable(true); sh.setSilent(true);
            sh.setPersistent(false); sh.setRemoveWhenFarAway(false); sh.setColor(dye); sh.setPeek(0f);
            sh.setInvisible(s().getBoolean("crate.enabled", true));        // only the hitbox: the displays are the crate
            if (crateScale != 1.0) setScale(sh, crateScale);               // hitbox as big as the crate
            sh.getPersistentDataContainer().set(CHUTE, PersistentDataType.BYTE, (byte) 2);
        });
        spawnCrate(w, start);
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

    /** Height of the hologram above the crate base: the crate size plus a small gap (hologram.offset). */
    /** crate.model: balloon (default, plain blocks, no resource pack) | pack (resource-pack models) | legacy (old crate + chicken parachute). */
    private String modelMode() { return s().getString("crate.model", "balloon").toLowerCase(); }
    private boolean balloonModel() { return s().getBoolean("crate.enabled", true) && !modelMode().equals("legacy"); }
    private boolean packModel() { return s().getBoolean("crate.enabled", true) && modelMode().equals("pack"); }
    private double hologramOffset() { return crateScale + ((rig != null && rig.hasBalloon()) || !balloonParts.isEmpty() ? 9.6 : s().getDouble("hologram.offset", 0.45)); }   // above the balloon while it hangs there

    private static void setScale(LivingEntity e, double v) {
        org.bukkit.attribute.Attribute at = null;
        for (String k : new String[]{"scale", "generic.scale"}) { at = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k)); if (at != null) break; }
        if (at == null) return;
        var inst = e.getAttribute(at);
        if (inst != null) inst.setBaseValue(v);
    }

    // ---------- crate (block display) ----------
    private Color glowColor() {
        List<String> g = t().getStringList("gradient");
        try { if (!g.isEmpty()) return Color.fromRGB(Integer.parseInt(g.get(g.size() - 1).replace("#", ""), 16)); } catch (Exception ignored) { }
        return Color.fromRGB(0xFFB27A);
    }

    private Material crateMat(String path, String def) {
        Material m = Material.matchMaterial(s().getString(path, def));
        return m != null && m.isBlock() ? m : Material.matchMaterial(def) != null ? Material.matchMaterial(def) : Material.IRON_BLOCK;
    }

    private void addPart(World w, Location at, Material m, Vector3f c, Vector3f size, Quaternionf rot, boolean glow) {
        parts.add(makePart(w, at, m, c, size, rot, glow));
    }

    private Part makePart(World w, Location at, Material m, Vector3f c, Vector3f size, Quaternionf rot, boolean glow) {
        BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setTeleportDuration(Math.max(0, Math.min(59, s().getInt("crate.teleport-duration", 4))));   // smooth movement between teleports
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange((float) s().getDouble("crate.view-range", 2.0));                              // visible from far away
            if (glow) { e.setGlowing(true); e.setGlowColorOverride(glowColor()); }
            e.getPersistentDataContainer().set(CHUTE, PersistentDataType.BYTE, (byte) 3);
        });
        return new Part(d, c, size, rot);
    }

    /**
     * Builds the crate (the same design for every tier): a solid body, a frame of 12 beams and a raised X on all 6 faces,
     * all block displays that move and tumble together. Materials: crate.body / crate.frame / crate.cross.
     */
    private void spawnCrate(World w, Location start) {
        if (!s().getBoolean("crate.enabled", true)) return;
        if (packModel()) {
            rig = new SupplyDropRig(w, start.clone().add(0, crateScale / 2.0, 0), tier, CHUTE,
                    Math.max(0, Math.min(59, s().getInt("crate.teleport-duration", 4))), (float) s().getDouble("crate.view-range", 2.0));
            return;
        }
        float S = (float) crateScale;
        Material body = crateMat("crate.body", "DARK_OAK_PLANKS"), frame = crateMat("crate.frame", "DARK_OAK_LOG"), cross = crateMat("crate.cross", "SPRUCE_PLANKS");
        Location at = start.clone().add(0, S / 2.0, 0);                       // every part is spawned at the crate centre
        addPart(w, at, body, new Vector3f(), new Vector3f(S, S, S), new Quaternionf(), s().getBoolean("crate.glow", true));
        // Clean geometry: no two parts overlap and no two faces share a plane, so nothing flickers (z-fighting).
        float bw = S * 0.09f, e = S * 0.012f, half = S / 2f - bw / 2f + e;     // beam width, how far the frame stands out, beam centre
        if (s().getBoolean("crate.beams", true)) {
            float full = S + 2 * e, mid = S - 2 * bw + 2 * e;                    // X beams run the whole edge; Y and Z beams stop at the X beams
            for (int a = -1; a <= 1; a += 2) for (int b = -1; b <= 1; b += 2) {
                addPart(w, at, frame, new Vector3f(0, a * half, b * half), new Vector3f(full, bw, bw), new Quaternionf(), false);
                addPart(w, at, frame, new Vector3f(a * half, 0, b * half), new Vector3f(bw, mid, bw), new Quaternionf(), false);
                addPart(w, at, frame, new Vector3f(a * half, b * half, 0), new Vector3f(bw, bw, mid), new Quaternionf(), false);
            }
        }
        if (s().getBoolean("crate.cross-bars", true)) {
            float inner = S - 2 * bw, width = S * 0.07f, depth = S * 0.03f;
            float len = (float) (Math.sqrt(2) * inner) - width;                   // ends stay inside the frame, never over it
            double h = Math.PI / 2;
            Quaternionf[] faces = {new Quaternionf(), new Quaternionf().rotateY((float) Math.PI), new Quaternionf().rotateY((float) h),
                    new Quaternionf().rotateY((float) -h), new Quaternionf().rotateX((float) -h), new Quaternionf().rotateX((float) h)};
            boolean single = s().getBoolean("crate.single-diagonal", true);       // wooden crate look: one diagonal board on each of the 4 sides
            for (int fi = 0; fi < (single ? 4 : 6); fi++) for (int sign = -1; sign <= 1; sign += 2) {
                if (single && sign > 0) continue;
                Quaternionf f = faces[fi];
                float d = sign < 0 ? depth : depth + S * 0.006f;                  // one bar a hair thicker: the two never share a surface where they cross
                Quaternionf r = new Quaternionf(f).rotateZ((float) (sign * Math.PI / 4));
                Vector3f c = new Vector3f(0, 0, S / 2f + d / 2f - 0.002f);
                f.transform(c);
                addPart(w, at, cross, c, new Vector3f(len, width, d), r, false);
            }
        }
        if (balloonModel()) spawnBalloon(w, at);
        pose(0f, balloonModel() ? 0f : (float) s().getDouble("crate.tilt-degrees", 22.0), 0, 1.0);
    }

    /** Gentle hanging motion of the balloon + ropes (sway around the crate eyelets) and the burner flame, called every 2 ticks. */
    private void animateRig(Location base) {
        double t = tick;
        float yaw = (float) (10.0 * Math.sin(t / 45.0));
        float swayX = (float) (3.0 * Math.sin(t / 22.0)), swayZ = (float) (3.0 * Math.cos(t / 30.0));
        Location centre = base.clone().add(0, crateScale / 2.0, 0);
        if (rig != null) {
            rig.pose(yaw, swayX, swayZ, 3, (float) (0.05 * Math.sin(t / 14.0)));
            rig.flames(centre, tick);
        } else if (!balloonParts.isEmpty()) {
            pose(yaw, 0f, 3, 1.0);                       // the crate only turns a little, it does not tumble
            poseBalloon(yaw, swayX, swayZ, 3);
            flames(centre);
        }
    }

    // ---------- balloon made of plain blocks (no resource pack) ----------
    private static final float EYE = 1.125f;          // rope anchors on the crate, blocks above the crate centre
    private static final float CUBE = 5.0f;           // balloon edge: a true cube, 5 x 5 x 5 blocks

    private Material balloonMat(String key, Material def) {
        Material m = Material.matchMaterial(t().getString("balloon." + key, ""));
        return m != null && m.isBlock() ? m : def;
    }

    /** Builds the balloon: cube with dark seams (squares), darker lower band, cream collar, skirt, burner frame, 4 lead ropes. */
    private void spawnBalloon(World w, Location at) {
        Material body, seam, dark, rim;
        switch (tier.toLowerCase()) {
            case "iron" -> { body = Material.LIGHT_GRAY_CONCRETE; seam = Material.GRAY_CONCRETE; dark = Material.GRAY_CONCRETE; rim = Material.WHITE_CONCRETE; }
            case "gold" -> { body = Material.YELLOW_CONCRETE; seam = Material.ORANGE_CONCRETE; dark = Material.ORANGE_TERRACOTTA; rim = Material.YELLOW_WOOL; }
            case "diamond" -> { body = Material.LIGHT_BLUE_CONCRETE; seam = Material.CYAN_CONCRETE; dark = Material.BLUE_CONCRETE; rim = Material.LIGHT_BLUE_WOOL; }
            case "netherite" -> { body = Material.BLACK_CONCRETE; seam = Material.GRAY_CONCRETE; dark = Material.BLACK_CONCRETE; rim = Material.GRAY_CONCRETE; }
            default -> { body = Material.RED_CONCRETE; seam = Material.NETHER_WART_BLOCK; dark = Material.RED_NETHER_BRICKS; rim = Material.RED_WOOL; }
        }
        body = balloonMat("body", body); seam = balloonMat("seam", seam); dark = balloonMat("dark", dark); rim = balloonMat("rim", rim);
        Material cream = balloonMat("collar", Material.SMOOTH_SANDSTONE), iron = Material.IRON_BLOCK, rope = balloonMat("rope", Material.BROWN_WOOL);
        float B = CUBE, yb = 4.3f, cy = yb + B / 2f;            // cube bottom / centre above the crate centre
        // envelope: every protruding part differs by a few hundredths so no two faces share a plane (no flicker)
        bal(w, at, body, 0, cy, 0, B, B, B);
        float s1 = B / 6f, h = B / 2f - 0.08f;                  // two seams each way (1/3 and 2/3 of a face), rim inset
        for (int k = -1; k <= 1; k += 2) {
            bal(w, at, seam, k * s1, cy, 0, 0.26f, B + 0.04f, B + 0.04f);                 // vertical seams on north + south faces
            bal(w, at, seam, 0, cy, k * s1, B + 0.06f, B + 0.06f, 0.26f);                 // vertical seams on east + west faces
        }
        bal(w, at, seam, 0, cy + B * 0.08f, 0, B + 0.08f, 0.28f, B + 0.08f);              // horizontal belt, 42% down from the top
        bal(w, at, seam, 0, yb + 0.55f, 0, B + 0.10f, 1.1f, B + 0.10f);                   // darker lower band ...
        bal(w, at, dark, 0, yb + 0.225f, 0, B + 0.12f, 0.45f, B + 0.12f);                 // ... and the darkest strip at the very bottom
        // lighter bevel rim on all edges (vertical x4, top x4, bottom x4); each uses a different protrusion so no faces share a plane
        for (int a = -1; a <= 1; a += 2) for (int b = -1; b <= 1; b += 2) {
            bal(w, at, rim, a * h, cy, b * h, 0.22f, B, 0.22f);
            bal(w, at, rim, 0, cy + B / 2f - 0.08f, b * (B / 2f - 0.07f), B + 0.02f, 0.22f, 0.24f);
            bal(w, at, rim, a * (B / 2f - 0.06f), cy + B / 2f - 0.09f, 0, 0.26f, 0.24f, B + 0.02f);
        }
        for (int a = -1; a <= 1; a += 2) {
            bal(w, at, rim, 0, yb + 0.06f, a * (B / 2f - 0.07f), B + 0.02f, 0.2f, 0.24f);
            bal(w, at, rim, a * (B / 2f - 0.06f), yb + 0.07f, 0, 0.26f, 0.22f, B + 0.02f);
        }
        bal(w, at, cream, 0, yb - 0.15f, 0, B * 0.55f, 0.3f, B * 0.55f);               // collar
        bal(w, at, body, 0, yb - 0.55f, 0, B * 0.3f, 0.5f, B * 0.3f);                 // skirt
        // burner frame: plate, four posts, nozzle (the flame rises between plate and skirt)
        float plateY = EYE + 1.5f;                                                       // = 2.625, rope tops
        bal(w, at, iron, 0, plateY + 0.05f, 0, 0.8f, 0.1f, 0.8f);
        for (int a = -1; a <= 1; a += 2) for (int b = -1; b <= 1; b += 2)
            bal(w, at, Material.GRAY_CONCRETE, a * 0.36f, plateY + 0.1f + 0.39f, b * 0.36f, 0.08f, 0.78f, 0.08f);
        bal(w, at, Material.BLACKSTONE, 0, plateY + 0.2f, 0, 0.16f, 0.2f, 0.16f);
        // four lead ropes: top 0.25 from the axis, bottom 0.875 out (the crate eyelets), 22.5 degrees
        float L = 1.5f / (float) Math.cos(Math.toRadians(22.5)), mid = (plateY + EYE) / 2f, off = (0.25f + 0.875f) / 2f, a22 = (float) Math.toRadians(22.5);
        balRot(w, at, rope, 0, mid, -off, 0.09f, L, 0.09f, new Quaternionf().rotateX(a22));
        balRot(w, at, rope, 0, mid, off, 0.09f, L, 0.09f, new Quaternionf().rotateX(-a22));
        balRot(w, at, rope, -off, mid, 0, 0.09f, L, 0.09f, new Quaternionf().rotateZ(-a22));
        balRot(w, at, rope, off, mid, 0, 0.09f, L, 0.09f, new Quaternionf().rotateZ(a22));
        // on the crate itself (they turn with it, they do not sway): rope eyelets and two ballast sacks
        for (int a = -1; a <= 1; a += 2) {
            addPart(w, at, iron, new Vector3f(0, 1.0625f, a * 0.875f), new Vector3f(0.25f, 0.125f, 0.25f), new Quaternionf(), false);
            addPart(w, at, iron, new Vector3f(a * 0.875f, 1.0625f, 0), new Vector3f(0.25f, 0.125f, 0.25f), new Quaternionf(), false);
            addPart(w, at, Material.WHITE_WOOL, new Vector3f(a * 1.28f, 0.5f, 0), new Vector3f(0.4f, 0.62f, 0.5f), new Quaternionf().rotateZ(-a * 0.2f), false);
            addPart(w, at, Material.BROWN_WOOL, new Vector3f(a * 1.18f, 0.84f, 0), new Vector3f(0.3f, 0.1f, 0.56f), new Quaternionf().rotateZ(-a * 0.2f), false);
        }
        poseBalloon(0f, 0f, 0f, 0);
    }

    private void bal(World w, Location at, Material m, float x, float y, float z, float sx, float sy, float sz) {
        balRot(w, at, m, x, y, z, sx, sy, sz, new Quaternionf());
    }

    private void balRot(World w, Location at, Material m, float x, float y, float z, float sx, float sy, float sz, Quaternionf r) {
        balloonParts.add(makePart(w, at, m, new Vector3f(x, y, z), new Vector3f(sx, sy, sz), r, false));
    }

    /** Turns and leans the whole balloon assembly around the rope eyelets on the crate, so the ropes never leave the crate. */
    private void poseBalloon(float yawDeg, float swayX, float swayZ, int duration) {
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(swayZ)).rotateX((float) Math.toRadians(swayX)).rotateY((float) Math.toRadians(yawDeg));
        Vector3f pivot = new Vector3f(0, EYE, 0);
        for (Part p : balloonParts) {
            if (!p.e().isValid()) continue;
            Vector3f centre = new Vector3f(p.c()).sub(pivot); q.transform(centre); centre.add(pivot);
            Quaternionf rot = new Quaternionf(q).mul(p.r());
            Vector3f half = new Vector3f(p.s()).mul(0.5f); rot.transform(half);
            p.e().setInterpolationDelay(0);
            p.e().setInterpolationDuration(Math.max(0, duration));
            p.e().setTransformation(new Transformation(centre.sub(half), rot, new Vector3f(p.s()), new Quaternionf()));
        }
    }

    /** Burner flame rising between the plate and the balloon skirt. */
    private void flames(Location crateCentre) {
        World w = crateCentre.getWorld();
        Location l = crateCentre.clone().add(0, 3.0, 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            Location p = l.clone().add((r.nextDouble() - .5) * .2, r.nextDouble() * .1, (r.nextDouble() - .5) * .2);
            w.spawnParticle(Particle.FLAME, p, 0, 0, 1, 0, 0.06 + r.nextDouble() * .05);
        }
        if (tick % 5 == 0) w.spawnParticle(Particle.SMALL_FLAME, l, 2, .08, .05, .08, 0.02);
        if (tick % 8 == 0) w.spawnParticle(Particle.SMOKE, l.clone().add(0, .8, 0), 1, .12, .05, .12, 0.01);
    }

    private void releaseBalloon(Location crateCentre) {
        if (balloonParts.isEmpty()) return;
        crateCentre.getWorld().spawnParticle(Particle.CLOUD, crateCentre.clone().add(0, 6.5, 0), 60, 1.6, 1.2, 1.6, 0.03);
        for (Part p : balloonParts) p.e().remove();
        balloonParts.clear();
    }

    private boolean crateOk() { return rig != null ? rig.isValid() : !parts.isEmpty() && parts.get(0).e().isValid(); }

    /** Moves every part so the crate stands with its base at `base`. */
    private void moveCrate(Location base) {
        if (!crateOk()) return;
        if (rig != null) { rig.move(base.clone().add(0, crateScale / 2.0, 0)); return; }
        Location c = base.clone().add(0, crateScale / 2.0, 0);
        for (Part p : parts) if (p.e().isValid()) p.e().teleportAsync(c);
        for (Part p : balloonParts) if (p.e().isValid()) p.e().teleportAsync(c);
    }

    /** Rotates the whole crate around its own centre (yaw + tilt), smoothly over `duration` ticks; mul scales it. */
    private void pose(float yawDeg, float tiltDeg, int duration, double mul) {
        if (!crateOk()) return;
        if (rig != null) { rig.pose(yawDeg, 0f, 0f, duration, 0f); return; }
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).rotateX((float) Math.toRadians(tiltDeg)).rotateZ((float) Math.toRadians(tiltDeg * 0.6));
        float m = (float) mul;
        for (Part p : parts) {
            if (!p.e().isValid()) continue;
            Vector3f size = new Vector3f(p.s()).mul(m), centre = new Vector3f(p.c()).mul(m);
            Vector3f corner = new Vector3f(size).mul(0.5f);
            p.r().transform(corner);                                       // rotated half size
            Vector3f t = centre.sub(corner, new Vector3f());               // keeps the part's centre where it belongs
            q.transform(t);
            p.e().setInterpolationDelay(0);
            p.e().setInterpolationDuration(Math.max(0, duration));
            p.e().setTransformation(new Transformation(t, new Quaternionf(q).mul(p.r()), size, new Quaternionf()));
        }
    }

    /** Plays every sound spec under `parent` once, each with a slightly random pitch (metal never rings the same twice). */
    private void playExtras(ConfigurationSection parent) {
        if (parent == null) return;
        for (String k : parent.getKeys(false)) {
            var so = pl.fx.sound(parent.getConfigurationSection(k));
            if (so == null) continue;
            float jitter = 0.9f + (float) Math.random() * 0.25f;
            ground.getWorld().playSound(net.kyori.adventure.sound.Sound.sound(so.name(), so.source(), so.volume(), so.pitch() * jitter), ground.getX(), ground.getY(), ground.getZ());
        }
    }

    // ---------- parachute ----------
    private int chuteCount() { if (balloonModel()) return 0; return Math.max(0, Math.min(16, s().getInt("parachute.chickens", 5))); }
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
            if (crateOk()) {
                int iv = Math.max(1, s().getInt("crate.move-interval-ticks", 4));            // the displays are smoothed between these teleports
                if (tick % iv == 0) moveCrate(l);
                if (balloonModel() && tick % 2 == 0) animateRig(l);
                if (!balloonModel() && s().getBoolean("crate.spin", true) && tick % 10 == 0) {          // slow tumble: 10 ticks per step, always less than 180 degrees
                    spin += (float) Math.min(150.0, s().getDouble("crate.spin-degrees-per-second", 90.0)) / 2f;
                    pose(spin, (float) s().getDouble("crate.tilt-degrees", 22.0), 10, 1.0);
                }
            }
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
            if (s().getBoolean("land.smoke-column", true) && tick % 6 == 0) {            // thin smoke column that shows where the crate is
                ground.getWorld().spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, ground.clone().add(0, crateScale + 0.1, 0), 1, 0.15, 0, 0.15, 0.02);
                if (tick % 24 == 0) ground.getWorld().spawnParticle(Particle.LARGE_SMOKE, ground.clone().add(0, 0.4, 0), 4, 0.5, 0.2, 0.5, 0.01);
            }
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
        if (crateOk()) { moveCrate(ground); pose(0f, 0f, 6, 1.0); }      // lands upright
        if (rig != null) rig.detach(ground.clone().add(0, crateScale / 2.0, 0));   // the balloon lets go
        releaseBalloon(ground.clone().add(0, crateScale / 2.0, 0));
        if (holo != null) holo.teleportAsync(ground.clone().add(0, hologramOffset(), 0));
        releaseParachute();
        World w = ground.getWorld();
        playLoud(owner.resolve(tier, "sounds.land", "land.sound"), s().getDouble("land.sound-range", 160.0), ground.getX(), ground.getY(), ground.getZ());
        ConfigurationSection lp = t().getConfigurationSection("land-particles");
        if (lp != null) pl.particles.play(ground.clone(), pl.particles.read(lp), 0);
        landBlast();
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
                playExtras(s().getConfigurationSection("hit.extra-sounds"));                                      // chain / shield clang on top
                ground.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, ground.clone().add(0, crateScale / 2, 0), 8, 0.4, 0.4, 0.4, 0.05);
                if (crateOk() && s().getBoolean("crate.shake", true)) {
                    float t = (Math.random() < 0.5 ? -1 : 1) * (4f + (float) Math.random() * 4f);
                    pose(0f, t, 1, 1.04);
                    pl.sched.regionLater(ground, () -> pose(0f, 0f, 3, 1.0), 2);
                }
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
        boolean scatter = s().getBoolean("open.scatter.enabled", true);       // false = straight into the opener's inventory
        if (!scatter) for (ItemStack it : r.items()) Util.give(p, it);
        for (String c : r.commands()) pl.sched.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName())));
        var so = pl.fx.sound(owner.resolve(tier, "sounds.open", "open.sound"));
        Map<String, Object> ph = ph();
        ph.put("player", p.getName());
        pl.sched.region(ground, () -> {
            // the loot first, and a failure in any effect below can never swallow it
            if (scatter) {
                try { scatterLoot(r.items()); }
                catch (Throwable ex) { pl.getLogger().warning("lootdrop scatter failed, giving the loot to " + p.getName() + ": " + ex); for (ItemStack it : r.items()) Util.give(p, it); }
            }
            try { if (so != null) ground.getWorld().playSound(so, ground.getX(), ground.getY(), ground.getZ()); } catch (Throwable ignored) { }
            try { if (shulker != null && shulker.isValid()) shulker.setPeek(1f); pose(0f, 0f, 5, 1.25); } catch (Throwable ignored) { }
            try { playExtras(s().getConfigurationSection("open.extra-sounds")); } catch (Throwable ignored) { }
            try {
                ConfigurationSection op = s().getConfigurationSection("open.particles");
                if (op != null) pl.particles.play(ground.clone(), pl.particles.read(op), 0);
            } catch (Throwable ignored) { }
        });
        pl.fx.broadcast(s(), "open.chat", ph, pl.cfg.messages().getString("prefix", ""));
        announceTopHitters();
        pl.discord.crateOpened(Util.title(tier), p.getName(), coords());
        pl.sched.regionLater(ground, () -> destroy(false), 20);
    }

    /** The loot bursts out of the crate and lands on the ground around it (anybody can pick it up). */
    private void scatterLoot(List<ItemStack> items) {
        World w = ground.getWorld();
        ConfigurationSection sc = s().getConfigurationSection("open.scatter");
        double hMin = sc == null ? 0.12 : sc.getDouble("horizontal-min", 0.12), hMax = sc == null ? 0.38 : sc.getDouble("horizontal-max", 0.38);
        double up = sc == null ? 0.42 : sc.getDouble("up", 0.42);
        int delay = sc == null ? 15 : sc.getInt("pickup-delay-ticks", 15);
        boolean glow = sc != null && sc.getBoolean("glow", false);
        Location from = ground.clone().add(0, crateScale * 0.55, 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (ItemStack it : items) {
            double a = r.nextDouble(Math.PI * 2), sp = hMin + r.nextDouble() * Math.max(0.0, hMax - hMin);
            org.bukkit.util.Vector v = new org.bukkit.util.Vector(Math.cos(a) * sp, up * (0.7 + r.nextDouble() * 0.6), Math.sin(a) * sp);
            org.bukkit.entity.Item item = w.dropItem(from, it);               // lands on the ground around the crate, nothing goes into an inventory
            item.setVelocity(v);
            item.setPickupDelay(delay);
            item.setGlowing(glow);
        }
        w.spawnParticle(Particle.POOF, from, 18, 0.35, 0.3, 0.35, 0.06);
        w.spawnParticle(Particle.FIREWORK, from, 20, 0.3, 0.3, 0.3, 0.12);
    }

    /** After the fall: explosion, flying debris, a smoke cloud and sound (everything under land.blast). */
    private void landBlast() {
        ConfigurationSection b = s().getConfigurationSection("land.blast");
        if (b != null && !b.getBoolean("enabled", true)) return;
        World w = ground.getWorld();
        Location c = ground.clone().add(0, 0.6, 0);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.EXPLOSION, c, 8, 1.2, 0.4, 1.2, 0);
        w.spawnParticle(Particle.FLAME, c, 30, 0.8, 0.3, 0.8, 0.12);
        w.spawnParticle(Particle.LARGE_SMOKE, c, b == null ? 60 : b.getInt("smoke", 60), 1.3, 0.5, 1.3, 0.05);
        w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 12, 0.5, 0.1, 0.5, 0.04);
        try {                                                         // debris of the block it landed on
            org.bukkit.block.data.BlockData under = ground.clone().add(0, -1, 0).getBlock().getBlockData();
            if (under.getMaterial().isAir()) under = Material.DIRT.createBlockData();
            w.spawnParticle(Particle.BLOCK, c, 60, 1.2, 0.3, 1.2, 0.2, under);
        } catch (Throwable ignored) { }
        w.playSound(ground, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.6f, 0.6f);
        w.playSound(ground, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.0f, 0.9f);
        w.playSound(ground, Sound.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.7f, 0.6f);
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
            for (Part p : parts) p.e().remove();
            parts.clear();
            if (rig != null) { rig.remove(); rig = null; }
            for (Part p : balloonParts) p.e().remove();
            balloonParts.clear();
            if (holo != null) holo.remove();
            try { ground.getWorld().removePluginChunkTicket(ground.getBlockX() >> 4, ground.getBlockZ() >> 4, pl); } catch (Throwable ignored) { }
        };
        if (direct) { try { rm.run(); } catch (Throwable ignored) { } }
        else pl.sched.region(ground, rm);
    }
}
