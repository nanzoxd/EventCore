package com.eventcore.util;

import org.bukkit.inventory.ItemStack;
import org.joml.AxisAngle4f;

/** Item display pose helpers. */
public final class ItemPose {
    private ItemPose() { }

    /**
     * Flat tool and weapon sprites are drawn on a 45 degree diagonal (tip to the top right), so a spinning display shows them
     * tilted. This is the local rotation (use it as the transformation's RIGHT rotation, so the spin stays around the vertical
     * axis) that stands them up straight. Other items, and items with a custom item model, are left untouched.
     */
    public static AxisAngle4f upright(ItemStack item) {
        if (item == null || item.getType().isAir()) return new AxisAngle4f();
        String n = item.getType().name();
        boolean diagonal = n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.endsWith("_SPEAR") || n.equals("TRIDENT") || n.equals("MACE") || n.equals("FISHING_ROD") || n.endsWith("_ON_A_STICK");
        if (!diagonal) return new AxisAngle4f();
        try {
            if (item.hasItemMeta() && item.getItemMeta().hasItemModel()) return new AxisAngle4f();   // resource pack model: unknown orientation
        } catch (Throwable ignored) { }
        return new AxisAngle4f((float) (Math.PI / 4), 0f, 0f, 1f);
    }
}
