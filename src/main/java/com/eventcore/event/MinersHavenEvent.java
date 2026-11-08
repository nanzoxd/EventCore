package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Miners Haven: while it runs, every ore drops double. Each item the ore dropped is dropped again (multiplier - 1)
 * more times, so Fortune still applies to the normal part and is then doubled as well. Silk touch (the ore block
 * drops itself) gets no bonus, so nobody can duplicate ore blocks.
 */
public final class MinersHavenEvent extends GameEvent {

    private static final List<String> DEFAULT_ORES = List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "NETHER_GOLD_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
            "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE",
            "DEEPSLATE_EMERALD_ORE", "NETHER_QUARTZ_ORE");

    public MinersHavenEvent(EventCorePlugin pl) { super(pl, "miners_haven"); }

    private Set<Material> ores() {
        Set<Material> set = new HashSet<>();
        List<String> names = settings().getStringList("blocks");
        if (names.isEmpty()) names = DEFAULT_ORES;
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) set.add(m);
        }
        return set;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!active() || e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        Set<Material> ores = ores();
        if (!ores.contains(e.getBlockState().getType())) return;
        ConfigurationSection s = settings();
        int extraTimes = Math.max(1, s.getInt("multiplier", 2)) - 1;
        Location at = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        boolean gave = false;
        // copy first: dropping new items while iterating the event's own list is not allowed
        for (Item it : new ArrayList<>(e.getItems())) {
            ItemStack drop = it.getItemStack();
            if (ores.contains(drop.getType())) continue;   // silk touch: the ore block itself, no bonus
            int total = drop.getAmount() * extraTimes, max = Math.max(1, drop.getType().getMaxStackSize());
            while (total > 0) {
                int n = Math.min(total, max);
                ItemStack extra = drop.clone();
                extra.setAmount(n);
                e.getBlock().getWorld().dropItemNaturally(at, extra);
                total -= n;
                gave = true;
            }
        }
        if (gave) pl.fx.play(e.getPlayer(), s.getConfigurationSection("bonus-sound"));
    }
}
