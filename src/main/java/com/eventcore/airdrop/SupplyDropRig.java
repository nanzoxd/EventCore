package com.eventcore.airdrop;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Supply drop made of three item displays that use the resource-pack models
 * eventcore:supply_crate, eventcore:supply_ropes and eventcore:supply_balloon_<tier>.
 * Origin of the whole rig = CENTRE of the 2x2x2 crate. Balloon + ropes are separate entities so they animate on their own.
 *
 * Layout (blocks above the crate centre):  crate 0 | rope pivot 2.625 (ropes slant down 1.5 blocks to the crate eyelets)
 * | balloon model origin 5.625 (platform underside sits on the rope tops). Balloon and ropes are scaled x2 (see the pack).
 */
public final class SupplyDropRig {
    private static final float ROPE_TOP = 2.625f, BALLOON_Y = 5.625f, EYELET = 1.125f;   // see layout above
    private static final float FLAME_Y = 3.2f;   // blocks above the crate centre: top of the burner, between deck and balloon skirt
    private final ItemDisplay crate;
    private ItemDisplay ropes, balloon;

    private static ItemStack stack(String model) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("eventcore", model));      // Paper 1.21.3+
        s.setItemMeta(m);
        return s;
    }

    private static ItemDisplay spawn(World w, Location at, String model, NamespacedKey marker, int tp, float range) {
        return w.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack(model));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.setTeleportDuration(tp);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(range);
            d.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 3);
        });
    }

    /** tier = iron | gold | diamond | netherite (any other id falls back to the red balloon). */
    public SupplyDropRig(World w, Location crateCentre, String tier, NamespacedKey marker, int teleportTicks, float viewRange) {
        String t = switch (tier == null ? "" : tier.toLowerCase()) { case "iron", "gold", "diamond", "netherite" -> tier.toLowerCase(); default -> "red"; };
        crate   = spawn(w, crateCentre, "supply_crate", marker, teleportTicks, viewRange);
        ropes   = spawn(w, crateCentre.clone().add(0, ROPE_TOP, 0), "supply_ropes", marker, teleportTicks, viewRange);
        balloon = spawn(w, crateCentre.clone().add(0, BALLOON_Y, 0), "supply_balloon_" + t, marker, teleportTicks, viewRange);
        pose(0f, 0f, 0f, 0, 0f);
    }

    /** Burner flame: call every tick (or every 2) while the balloon is in the air. Rises into the skirt of the balloon. */
    public void flames(Location crateCentre, int tick) {
        if (!hasBalloon()) return;
        World w = crateCentre.getWorld();
        Location l = crateCentre.clone().add(0, FLAME_Y, 0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            Location p = l.clone().add((r.nextDouble() - .5) * .25, r.nextDouble() * .1, (r.nextDouble() - .5) * .25);
            w.spawnParticle(Particle.FLAME, p, 0, 0, 1, 0, 0.07 + r.nextDouble() * .05);   // count 0 = direction + speed
        }
        if (tick % 5 == 0) w.spawnParticle(Particle.SMALL_FLAME, l, 2, .1, .05, .1, 0.02);
        if (tick % 8 == 0) w.spawnParticle(Particle.SMOKE, l.clone().add(0, .9, 0), 1, .15, .05, .15, 0.01);
    }

    public boolean isValid() { return crate.isValid(); }

    /** true while the balloon and ropes are still attached (they are removed when the crate lands). */
    public boolean hasBalloon() { return balloon != null && balloon.isValid(); }

    /** The balloon lets go when the crate lands: a puff of cloud and the balloon + ropes are gone. */
    public void detach(Location crateCentre) {
        if (!hasBalloon()) return;
        World w = crateCentre.getWorld();
        w.spawnParticle(Particle.CLOUD, crateCentre.clone().add(0, BALLOON_Y + 1.5, 0), 60, 1.6, 1.2, 1.6, 0.03);
        balloon.remove(); ropes.remove();
    }

    public void move(Location crateCentre) {
        crate.teleportAsync(crateCentre);
        if (!hasBalloon()) return;
        ropes.teleportAsync(crateCentre.clone().add(0, ROPE_TOP, 0));
        balloon.teleportAsync(crateCentre.clone().add(0, BALLOON_Y, 0));
    }

    /**
     * yawDeg: whole rig turns around its vertical axis. swayX/swayZ: the balloon + ropes lean (degrees) around the crate's
     * rope eyelets. bob: balloon lifts/drops this many blocks. duration: interpolation ticks.
     */
    public void pose(float yawDeg, float swayX, float swayZ, int duration, float bob) {
        Quaternionf yaw = new Quaternionf().rotateY((float) Math.toRadians(yawDeg));
        Quaternionf lean = new Quaternionf().rotateZ((float) Math.toRadians(swayZ)).rotateX((float) Math.toRadians(swayX));
        Quaternionf upper = new Quaternionf(lean).mul(yaw);
        apply(crate, yaw, new Vector3f(), 1f, duration);
        // Pivot = the crate's rope eyelets, so the ropes never come off the crate while the balloon sways.
        if (ropes == null) return;
        apply(ropes, upper, pivotShift(upper, new Vector3f(0, EYELET - ROPE_TOP, 0)), 2f, duration);
        Vector3f b = pivotShift(upper, new Vector3f(0, EYELET - BALLOON_Y, 0));
        b.y += bob;
        apply(balloon, upper, b, 2f, duration);
    }

    /** translation that keeps point p (relative to the entity) fixed while the model rotates by q around the entity. */
    private static Vector3f pivotShift(Quaternionf q, Vector3f p) {
        return p.sub(q.transform(new Vector3f(p)), new Vector3f());
    }

    private static void apply(ItemDisplay d, Quaternionf rot, Vector3f t, float scale, int duration) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(0, duration));
        d.setTransformation(new Transformation(t, rot, new Vector3f(scale), new Quaternionf()));
    }

    public void remove() { crate.remove(); if (ropes != null) ropes.remove(); if (balloon != null) balloon.remove(); }
}
