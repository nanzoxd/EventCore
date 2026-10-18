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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Millionaire: while it runs, every gold ore (stone, deepslate and nether gold ore by default) drops extra gold on top
 * of its normal drop. The extra is the same item the ore dropped (raw gold, or gold nuggets for nether gold ore), so
 * Fortune still applies to the normal part and silk touch (the ore block drops itself) gets no bonus.
 */
public final class MillionaireEvent extends GameEvent {

    public MillionaireEvent(EventCorePlugin pl) { super(pl, "millionaire"); }

    private Set<Material> ores() {
        Set<Material> set = new HashSet<>();
        for (String n : settings().getStringList("blocks")) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) set.add(m);
        }
        if (set.isEmpty()) { set.add(Material.GOLD_ORE); set.add(Material.DEEPSLATE_GOLD_ORE); set.add(Material.NETHER_GOLD_ORE); }
        return set;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!active() || e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        if (!ores().contains(e.getBlockState().getType())) return;
        ConfigurationSection s = settings();
        double chance = s.getDouble("chance", 100.0);
        if (chance < 100.0 && ThreadLocalRandom.current().nextDouble() * 100.0 >= chance) return;
        int min = Math.max(1, s.getInt("extra-min", 1)), max = Math.max(min, s.getInt("extra-max", 3));
        Location at = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        boolean gave = false;
        // copy first: dropping new items while iterating the event's own list is not allowed
        for (Item it : new java.util.ArrayList<>(e.getItems())) {
            Material type = it.getItemStack().getType();
            if (type != Material.RAW_GOLD && type != Material.GOLD_NUGGET) continue;   // silk touch etc: the ore itself, no bonus
            e.getBlock().getWorld().dropItemNaturally(at, new ItemStack(type, ThreadLocalRandom.current().nextInt(min, max + 1)));
            gave = true;
        }
        if (gave) pl.fx.play(e.getPlayer(), s.getConfigurationSection("bonus-sound"));
    }
}
