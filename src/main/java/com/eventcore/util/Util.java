package com.eventcore.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

public final class Util {
    private Util() { }

    /** Gives an item; whatever does not fit is dropped at the player's feet. */
    public static void give(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
    }

    /** STONE_BRICKS -> Stone Bricks */
    public static String pretty(Material m) {
        StringBuilder b = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    public static String mmss(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    public static String bar(double frac, int width, char full, char empty) {
        int f = (int) Math.round(Math.max(0, Math.min(1, frac)) * width);
        return String.valueOf(full).repeat(f) + String.valueOf(empty).repeat(width - f);
    }

    public static String last(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }
}
