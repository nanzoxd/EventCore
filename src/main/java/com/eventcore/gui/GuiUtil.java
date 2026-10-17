package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds GUI items from gui.yml item sections {material, name, lore, glow}. */
public final class GuiUtil {
    private GuiUtil() { }

    public static ItemStack item(EventCorePlugin pl, ConfigurationSection s, Map<String, Object> ph) {
        Material m = Material.matchMaterial(s.getString("material", "STONE"));
        if (m == null || !m.isItem() || m.isAir()) m = Material.STONE;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(s.contains("name") ? pl.text.item(s.getString("name", " "), ph) : Component.text(" "));
        List<String> lore = s.getStringList("lore");
        if (!lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(pl.text.item(line, ph));
            meta.lore(l);
        }
        if (s.getBoolean("glow", false)) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }
}
