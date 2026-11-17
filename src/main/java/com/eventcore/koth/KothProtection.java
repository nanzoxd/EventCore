package com.eventcore.koth;

import com.eventcore.EventCorePlugin;
import com.eventcore.koth.KothData.Arena;
import com.eventcore.koth.KothData.Flag;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;

/**
 * Protects the pasted build (nobody breaks or places blocks inside it, explosions / pistons / fluids / fire never touch it)
 * and applies the gameplay flags in the arena zone: the build bounds grown by "zone-margin" blocks.
 * Players with eventcore.koth.bypass (or eventcore.bypass) are exempt from the block rules.
 */
public final class KothProtection implements Listener {
    private static final Set<SpawnReason> NATURAL = EnumSet.of(SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.VILLAGE_INVASION, SpawnReason.PATROL,
            SpawnReason.REINFORCEMENTS, SpawnReason.RAID, SpawnReason.JOCKEY, SpawnReason.MOUNT, SpawnReason.TRAP, SpawnReason.LIGHTNING);

    private final EventCorePlugin pl;
    private final KothEvent ev;
    private final KothData d;

    KothProtection(EventCorePlugin pl, KothEvent ev, KothData d) { this.pl = pl; this.ev = ev; this.d = d; }

    private int margin() { return (int) d.num("zone-margin", 30); }
    private Arena zone(Location l) { return d.arenas.isEmpty() ? null : d.arenaAt(l, margin()); }
    private Arena build(Location l) { return d.arenas.isEmpty() ? null : d.arenaAt(l, 0); }
    private static boolean bypass(Player p) { return p.hasPermission("eventcore.koth.bypass") || p.hasPermission("eventcore.bypass"); }

    // ------------------------------------------------------------------ blocks
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer(); Block b = e.getBlock();
        if (zone(b.getLocation()) == null || bypass(p)) return;
        if (build(b.getLocation()) != null) { e.setCancelled(true); ev.deny(p, "this build is protected."); return; }
        boolean tracked = d.placed.contains(d.key(b)); String mode = d.breakMode();
        if (tracked ? mode.equals("NONE") : !mode.equals("ALL")) { e.setCancelled(true); ev.deny(p, mode.equals("NONE") ? "breaking blocks is disabled here." : "you can only break blocks that players placed."); }
        else d.placed.remove(d.key(b));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer(); Block b = e.getBlockPlaced();
        if (zone(b.getLocation()) == null || bypass(p)) return;
        if (build(b.getLocation()) != null) { e.setCancelled(true); ev.deny(p, "you cannot build inside this build."); return; }
        if (!d.flag(Flag.PLACE_BLOCKS)) { e.setCancelled(true); ev.deny(p, "placing blocks is disabled here."); return; }
        d.placed.add(d.key(b));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock(); Player p = e.getPlayer();
        if (zone(b.getLocation()) == null || bypass(p)) return;
        if (build(b.getLocation()) != null || !d.flag(Flag.PLACE_BLOCKS)) { e.setCancelled(true); ev.deny(p, "you cannot place fluids here."); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock(); Player p = e.getPlayer();
        if (build(b.getLocation()) != null && !bypass(p)) { e.setCancelled(true); ev.deny(p, "this build is protected."); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (build(e.getBlock().getLocation()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (build(e.getBlock().getLocation()) != null) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) if (build(b.getLocation()) != null || build(b.getRelative(e.getDirection()).getLocation()) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (build(e.getBlock().getLocation()) != null) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) if (build(b.getLocation()) != null) { e.setCancelled(true); return; }
    }

    // ------------------------------------------------------------------ explosions, fire, fluids
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (zone(e.getLocation()) != null && !d.flag(Flag.EXPLOSIONS)) { e.setCancelled(true); return; }
        e.blockList().removeIf(b -> build(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (zone(e.getBlock().getLocation()) != null && !d.flag(Flag.EXPLOSIONS)) { e.setCancelled(true); return; }
        e.blockList().removeIf(b -> build(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (build(e.getBlock().getLocation()) != null || (zone(e.getBlock().getLocation()) != null && !d.flag(Flag.FIRE_SPREAD))) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null && bypass(e.getPlayer())) return;
        if (build(e.getBlock().getLocation()) != null || (zone(e.getBlock().getLocation()) != null && !d.flag(Flag.FIRE_SPREAD))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (e.getSource().getType().name().contains("FIRE") && zone(e.getBlock().getLocation()) != null && !d.flag(Flag.FIRE_SPREAD)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Location to = e.getToBlock().getLocation();
        if (build(to) != null || (zone(to) != null && !d.flag(Flag.FLUID_FLOW))) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ gameplay flags
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (NATURAL.contains(e.getSpawnReason()) && !d.flag(Flag.MOB_SPAWNING) && zone(e.getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !ev.running() || zone(victim.getLocation()) == null) return;
        Player attacker = e.getDamager() instanceof Player a ? a : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker.equals(victim)) return;
        if (!d.flag(Flag.PVP) || ev.inGrace(attacker) || ev.inGrace(victim)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !d.flag(Flag.ENDER_PEARLS) && (zone(e.getFrom()) != null || (e.getTo() != null && zone(e.getTo()) != null))) {
            e.setCancelled(true); ev.deny(e.getPlayer(), "ender pearls are disabled here.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (ev.running() && !d.flag(Flag.ITEM_DROP) && ev.isParticipant(e.getPlayer()) && zone(e.getPlayer().getLocation()) != null) { e.setCancelled(true); ev.deny(e.getPlayer(), "dropping items is disabled here."); }
    }
}
