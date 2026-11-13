package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Detects every quest action. Each handler leaves at once when no quest event is running,
 * so the cost outside of the event is a single boolean read.
 */
public final class QuestListener implements Listener {
    private final EventCorePlugin pl;
    private final QuestManager q;
    private final NamespacedKey droppedKey;

    public QuestListener(EventCorePlugin pl, QuestManager q) {
        this.pl = pl;
        this.q = q;
        this.droppedKey = new NamespacedKey(pl, "quest_dropped");
    }

    private static long pack(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL) ^ ((long) b.getWorld().getUID().hashCode() << 50);
    }

    // ---------- join / quit ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) { q.onJoin(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { q.onQuit(e.getPlayer()); }

    // ---------- mining / blocks ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!q.tracking()) return;
        Player p = e.getPlayer();
        Block b = e.getBlock();
        q.progress(p, QuestType.BREAK_BLOCK, b.getType().name(), 1);
        if (q.reg.mineTargets().contains(b.getType().name())) {
            Long placedAt = q.placed.remove(pack(b));                         // placed by a player a moment ago: does not count as mining
            if (placedAt == null) q.progress(p, QuestType.MINE_BLOCK, b.getType().name(), 1);
        } else if (q.reg.mineTargets().contains("*")) q.progress(p, QuestType.MINE_BLOCK, b.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!q.tracking()) return;
        Block b = e.getBlockPlaced();
        q.progress(e.getPlayer(), QuestType.PLACE_BLOCK, b.getType().name(), 1);
        if (q.reg.mineTargets().contains(b.getType().name()))
            q.placed.put(pack(b), System.currentTimeMillis() + 30 * 60_000L);
    }

    // ---------- combat ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!q.tracking() || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        q.recent.put("hit:" + attacker.getUniqueId() + ">" + victim.getUniqueId(), System.currentTimeMillis() + 30_000);    // for "win a PvP fight"
        int dmg = (int) Math.round(e.getFinalDamage());
        if (dmg > 0) q.progress(attacker, QuestType.DAMAGE_PLAYER, null, dmg);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!q.tracking()) return;
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) return;
        if (dead instanceof Player victim) {
            long now = System.currentTimeMillis();
            String same = "kill:" + killer.getUniqueId() + ">" + victim.getUniqueId();
            Long cd = q.recent.get(same);
            if (cd != null && cd > now) return;                                // the same victim again within a minute does not count (no farming)
            q.recent.put(same, now + 60_000);
            q.progress(killer, QuestType.KILL_PLAYER, null, 1);
            Long hitBack = q.recent.get("hit:" + victim.getUniqueId() + ">" + killer.getUniqueId());
            if (hitBack != null && hitBack > now) q.progress(killer, QuestType.WIN_PVP, null, 1);   // they fought back: a real fight
        } else q.progress(killer, QuestType.KILL_ENTITY, dead.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!q.tracking() || !(e.getEntity() instanceof TNTPrimed t)) return;
        if (t.getSource() instanceof Player p) q.progress(p, QuestType.EXPLODE_TNT, null, 1);
    }

    // ---------- items ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent e) {
        if (!q.tracking()) return;
        q.progress(e.getPlayer(), QuestType.SMELT, e.getItemType().name(), e.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!q.tracking() || !(e.getWhoClicked() instanceof Player p)) return;
        ItemStack res = e.getRecipe().getResult();
        if (res == null || res.getType() == Material.AIR) return;
        int crafts = 1;
        if (e.isShiftClick()) {                                                // shift-click crafts as many as the ingredients allow
            crafts = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && it.getType() != Material.AIR) crafts = Math.min(crafts, it.getAmount());
            if (crafts == Integer.MAX_VALUE || crafts < 1) crafts = 1;
        }
        int amount = crafts * res.getAmount();
        q.progress(p, QuestType.CRAFT, res.getType().name(), amount);
        q.progress(p, QuestType.COLLECT, res.getType().name(), amount);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!q.tracking()) return;
        e.getItemDrop().getPersistentDataContainer().set(droppedKey, PersistentDataType.BYTE, (byte) 1);   // dropped by a player: picking it up again is no progress
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!q.tracking() || !(e.getEntity() instanceof Player p)) return;
        if (e.getItem().getPersistentDataContainer().has(droppedKey, PersistentDataType.BYTE)) return;
        ItemStack st = e.getItem().getItemStack();
        int amount = st.getAmount() - e.getRemaining();
        if (amount > 0) q.progress(p, QuestType.COLLECT, st.getType().name(), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (!q.tracking()) return;
        Player p = e.getPlayer();
        q.progress(p, QuestType.TRADE, null, 1);
        ItemStack res = e.getTrade().getResult();
        if (res != null && res.getType() == Material.EMERALD) q.progress(p, QuestType.COLLECT, "EMERALD", res.getAmount());
    }

    // ---------- world ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!q.tracking() || !(e.getPlayer() instanceof Player p)) return;
        if (e.getInventory().getType() != InventoryType.CHEST) return;
        Location l = e.getInventory().getLocation();
        if (l == null || l.getWorld() == null) return;                         // virtual inventories (our own menus) are not chests
        String key = "chest:" + p.getUniqueId() + ":" + l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
        long now = System.currentTimeMillis();
        Long until = q.recent.get(key);
        if (until != null && until > now) return;                              // the same chest only counts once per 15 minutes
        q.recent.put(key, now + 15 * 60_000L);
        q.progress(p, QuestType.OPEN_CHEST, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!q.tracking()) return;
        if (e.getBreeder() instanceof Player p) q.progress(p, QuestType.BREED, e.getEntity().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!q.tracking() || e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Entity caught = e.getCaught();
        q.progress(e.getPlayer(), QuestType.FISH, caught == null ? null : caught.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!q.tracking()) return;
        Location a = e.getFrom(), b = e.getTo();
        if (b == null || (a.getX() == b.getX() && a.getZ() == b.getZ())) return;
        double d = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        if (d > 6) return;                                                     // teleports / lag spikes are not travelling
        Player p = e.getPlayer();
        double[] acc = q.travel.computeIfAbsent(p.getUniqueId(), u -> new double[1]);
        acc[0] += d;
        if (acc[0] >= 1) { int whole = (int) acc[0]; acc[0] -= whole; q.progress(p, QuestType.TRAVEL, null, whole); }
    }
}
