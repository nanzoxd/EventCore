package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Leaves drop a configurable item (golden apples) instead of apples, with an optional bonus chance on any leaf.
 * Optional client-side block swap (never touches the world, so nothing needs restoring server-side).
 */
public final class GoldenTreesEvent extends GameEvent {
    private final Map<UUID, Set<Location>> swapped = new ConcurrentHashMap<>();
    private long lastSwap;

    public GoldenTreesEvent(EventCorePlugin pl) { super(pl, "golden_trees"); }

    private ItemStack dropStack() {
        ConfigurationSection s = settings();
        Material m = Material.matchMaterial(s.getString("drop-item", "GOLDEN_APPLE"));
        if (m == null || !m.isItem()) m = Material.GOLDEN_APPLE;
        int min = Math.max(1, s.getInt("amount-min", 1)), max = Math.max(min, s.getInt("amount-max", 1));
        return new ItemStack(m, ThreadLocalRandom.current().nextInt(min, max + 1));
    }

    private boolean bonusRoll() {
        double c = settings().getDouble("bonus-chance", 0.0);
        return c > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < c;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!active() || !Tag.LEAVES.isTagged(e.getBlockState().getType())) return;
        if (settings().getBoolean("replace-apples", true))
            for (Item it : e.getItems()) if (it.getItemStack().getType() == Material.APPLE) it.setItemStack(dropStack());
        if (bonusRoll()) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), dropStack());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (!active()) return;
        Block b = e.getBlock();
        Collection<ItemStack> drops = b.getDrops();
        boolean apple = settings().getBoolean("replace-apples", true) && drops.stream().anyMatch(i -> i.getType() == Material.APPLE);
        boolean bonus = bonusRoll();
        if (!apple && !bonus) return;
        e.setCancelled(true);
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        b.setType(Material.AIR);
        for (ItemStack d : drops) b.getWorld().dropItemNaturally(at, d.getType() == Material.APPLE && apple ? dropStack() : d);
        if (bonus) b.getWorld().dropItemNaturally(at, dropStack());
    }

    // ---- optional visual block swap ----
    @Override public void onSecond(ActiveEvent ae, int left) {
        ConfigurationSection sw = settings().getConfigurationSection("block-swap");
        if (sw == null || !sw.getBoolean("enabled", false)) return;
        long now = System.currentTimeMillis();
        if (now - lastSwap < sw.getInt("interval-seconds", 5) * 1000L) return;
        lastSwap = now;
        Set<Material> from = new HashSet<>();
        for (String n : sw.getStringList("from")) { Material m = Material.matchMaterial(n); if (m != null) from.add(m); }
        Material to = Material.matchMaterial(sw.getString("to", "FLOWERING_AZALEA_LEAVES"));
        if (to == null || from.isEmpty()) return;
        BlockData data = to.createBlockData();
        int r = Math.max(1, Math.min(24, sw.getInt("radius", 8)));
        for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> {
            Location c = p.getLocation();
            Set<Location> set = swapped.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                Block b = c.getWorld().getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                if (from.contains(b.getType())) { p.sendBlockChange(b.getLocation(), data); set.add(b.getLocation()); }
            }
        });
    }

    @Override protected void doStrip(Player p) {
        Set<Location> set = swapped.remove(p.getUniqueId());
        if (set == null) return;
        for (Location l : set) {
            try { p.sendBlockChange(l, l.getBlock().getBlockData()); } catch (Throwable ignored) { }
        }
    }
}
