package com.eventcore.airdrop;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loot tables from loot.yml:  tiers.&lt;tier&gt;.entries.&lt;id&gt; { material, name, lore, enchants, item-data (base64 stack),
 * amount-min, amount-max, chance (0-100), commands }. Each entry is rolled independently.
 */
public final class LootService {
    public record Result(List<ItemStack> items, List<String> commands) { }

    private final EventCorePlugin pl;

    public LootService(EventCorePlugin pl) { this.pl = pl; }

    public ConfigurationSection entries(String tier) {
        return pl.cfg.loot().getConfigurationSection("tiers." + tier + ".entries");
    }

    public List<String> tiers() {
        ConfigurationSection s = pl.cfg.loot().getConfigurationSection("tiers");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    public Result roll(String tier) {
        List<ItemStack> items = new ArrayList<>();
        List<String> cmds = new ArrayList<>();
        ConfigurationSection es = entries(tier);
        if (es == null) return new Result(items, cmds);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (String id : es.getKeys(false)) {
            ConfigurationSection e = es.getConfigurationSection(id);
            if (e == null || r.nextDouble() * 100.0 >= e.getDouble("chance", 100.0)) continue;
            int min = Math.max(1, e.getInt("amount-min", 1)), max = Math.max(min, e.getInt("amount-max", min));
            ItemStack base = build(e);
            if (base != null) {
                int amount = r.nextInt(min, max + 1), stack = Math.max(1, base.getMaxStackSize());
                while (amount > 0) {
                    ItemStack part = base.clone();
                    part.setAmount(Math.min(stack, amount));
                    items.add(part);
                    amount -= part.getAmount();
                }
            }
            cmds.addAll(e.getStringList("commands"));
        }
        return new Result(items, cmds);
    }

    /** Builds one stack (amount 1) from an entry section, or null for command-only entries. */
    public ItemStack build(ConfigurationSection e) {
        String data = e.getString("item-data");
        if (data != null && !data.isBlank()) {
            try {
                ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
                it.setAmount(1);
                return it;
            } catch (Exception ex) { pl.getLogger().warning("Bad item-data in loot entry " + e.getName()); }
        }
        Material m = Material.matchMaterial(e.getString("material", "AIR"));
        if (m == null || m.isAir() || !m.isItem()) return null;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (e.contains("name")) meta.displayName(pl.text.item(e.getString("name", ""), Map.of()));
        List<String> lore = e.getStringList("lore");
        if (!lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String s : lore) l.add(pl.text.item(s, Map.of()));
            meta.lore(l);
        }
        ConfigurationSection en = e.getConfigurationSection("enchants");
        if (en != null) for (String k : en.getKeys(false)) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase(Locale.ROOT)));
            if (ench == null) continue;
            if (meta instanceof EnchantmentStorageMeta sm) sm.addStoredEnchant(ench, en.getInt(k), true);
            else meta.addEnchant(ench, en.getInt(k), true);
        }
        if (e.getBoolean("hide-flags", false)) meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

    /** Display helper for the GUI editor. */
    public ItemStack icon(ConfigurationSection e) {
        ItemStack it = build(e);
        if (it != null) return it;
        return new ItemStack(Material.COMMAND_BLOCK);
    }

    public String plainName(ConfigurationSection e) {
        ItemStack it = build(e);
        if (it == null) return "commands only";
        return it.getItemMeta() != null && it.getItemMeta().hasDisplayName()
                ? Text.plain(it.getItemMeta().displayName()) : it.getType().name().toLowerCase(Locale.ROOT);
    }
}
