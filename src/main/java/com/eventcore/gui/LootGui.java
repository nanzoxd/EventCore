package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.airdrop.LootService;
import com.eventcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game loot table editor. Tier list -> entry list.
 * Entry: left = chance, right = amount (min-max), shift-left = add command / "clear", shift-right = delete.
 * Click the green "add" slot with an item on the cursor to add that exact item (NBT included).
 */
public final class LootGui implements Listener {
    static final class Holder implements InventoryHolder {
        Inventory inv; String tier; int page; List<String> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public LootGui(EventCorePlugin pl) { this.pl = pl; }

    private Component plain(String s, NamedTextColor c) { return Component.text(s, c).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false); }

    public void openTiers(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 27, pl.text.render("<g>loot tables</g>", null));
        int i = 10;
        for (String t : pl.loot.tiers()) {
            if (i > 16) break;
            ItemStack it = new ItemStack(Material.CHEST);
            ItemMeta m = it.getItemMeta();
            m.displayName(plain(t, NamedTextColor.WHITE));
            ConfigurationSection es = pl.loot.entries(t);
            m.lore(List.of(plain((es == null ? 0 : es.getKeys(false).size()) + " entries", NamedTextColor.GRAY)));
            it.setItemMeta(m);
            h.inv.setItem(i++, it);
            h.ids.add(t);
        }
        p.openInventory(h.inv);
    }

    public void openEntries(Player p, String tier, int page) {
        Holder h = new Holder();
        h.tier = tier; h.page = page;
        h.inv = Bukkit.createInventory(h, 54, pl.text.render("<g>loot: " + tier + "</g>", null));
        ConfigurationSection es = pl.loot.entries(tier);
        if (es != null) h.ids.addAll(es.getKeys(false));
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < h.ids.size(); i++) {
            ConfigurationSection e = es.getConfigurationSection(h.ids.get(from + i));
            ItemStack it = pl.loot.icon(e);
            ItemMeta m = it.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(plain("id: " + h.ids.get(from + i), NamedTextColor.DARK_GRAY));
            lore.add(plain("chance: " + e.getDouble("chance", 100) + "%", NamedTextColor.YELLOW));
            lore.add(plain("amount: " + e.getInt("amount-min", 1) + "-" + e.getInt("amount-max", e.getInt("amount-min", 1)), NamedTextColor.YELLOW));
            lore.add(plain("commands: " + e.getStringList("commands").size(), NamedTextColor.YELLOW));
            lore.add(plain("left: chance | right: amount", NamedTextColor.GRAY));
            lore.add(plain("shift-left: commands | shift-right: delete", NamedTextColor.GRAY));
            m.lore(lore);
            it.setItemMeta(m);
            h.inv.setItem(i, it);
        }
        h.inv.setItem(45, nav(Material.ARROW, "previous"));
        h.inv.setItem(48, nav(Material.BARRIER, "back"));
        h.inv.setItem(49, nav(Material.LIME_CONCRETE, "add: click with an item on your cursor"));
        h.inv.setItem(50, nav(Material.COMMAND_BLOCK, "add command-only reward"));
        h.inv.setItem(53, nav(Material.ARROW, "next"));
        p.openInventory(h.inv);
    }

    private ItemStack nav(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.WHITE));
        it.setItemMeta(meta);
        return it;
    }

    private String nextId(String tier, String base) {
        ConfigurationSection es = pl.loot.entries(tier);
        int n = 1;
        while (es != null && es.contains(base + "_" + n)) n++;
        return base + "_" + n;
    }

    private void save(Player p, String tier, int page) {
        pl.cfg.save("loot");
        pl.sched.entity(p, () -> openEntries(p, tier, page));
    }

    private void prompt(Player p, String tier, int page, String what, java.util.function.Function<String, String> fn) {
        p.closeInventory();
        pl.chat.ask(p, pl.msg("gui.prompt-simple", "what", what), fn, () -> openEntries(p, tier, page));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory() || !p.hasPermission("eventcore.gui")) return;
        int slot = e.getSlot();
        if (h.tier == null) {
            if (slot >= 10 && slot - 10 < h.ids.size()) openEntries(p, h.ids.get(slot - 10), 0);
            return;
        }
        String tier = h.tier;
        if (slot == 45) { if (h.page > 0) openEntries(p, tier, h.page - 1); return; }
        if (slot == 53) { if ((h.page + 1) * 45 < h.ids.size()) openEntries(p, tier, h.page + 1); return; }
        if (slot == 48) { openTiers(p); return; }
        String base = "tiers." + tier + ".entries.";
        if (slot == 49) {
            ItemStack cur = e.getCursor();
            if (cur == null || cur.getType().isAir()) { p.sendMessage(pl.msg("gui.loot-need-item")); return; }
            String id = nextId(tier, cur.getType().name().toLowerCase());
            pl.cfg.loot().set(base + id + ".item-data", LootService.encode(cur));
            pl.cfg.loot().set(base + id + ".amount-min", cur.getAmount());
            pl.cfg.loot().set(base + id + ".amount-max", cur.getAmount());
            pl.cfg.loot().set(base + id + ".chance", 50.0);
            save(p, tier, h.page);
            return;
        }
        if (slot == 50) {
            prompt(p, tier, h.page, "command to run as a reward (use {player}, no slash)", in -> {
                String id = nextId(tier, "command");
                pl.cfg.loot().set(base + id + ".chance", 10.0);
                pl.cfg.loot().set(base + id + ".commands", List.of(in.replaceFirst("^/", "")));
                pl.cfg.save("loot");
                return null;
            });
            return;
        }
        int idx = h.page * 45 + slot;
        if (slot >= 45 || idx >= h.ids.size()) return;
        String id = h.ids.get(idx), path = base + id;
        var click = e.getClick();
        if (click.isShiftClick() && click.isRightClick()) {
            pl.cfg.loot().set(path, null);
            save(p, tier, h.page);
        } else if (click.isShiftClick()) {
            prompt(p, tier, h.page, "command to add (or 'clear' to remove all)", in -> {
                List<String> l = new ArrayList<>(pl.cfg.loot().getStringList(path + ".commands"));
                if (in.equalsIgnoreCase("clear")) l.clear(); else l.add(in.replaceFirst("^/", ""));
                pl.cfg.loot().set(path + ".commands", l);
                pl.cfg.save("loot");
                return null;
            });
        } else if (click.isLeftClick()) {
            prompt(p, tier, h.page, "chance in percent (0-100)", in -> {
                double v;
                try { v = Double.parseDouble(in); } catch (Exception ex) { return "not a number"; }
                if (v < 0 || v > 100) return "must be between 0 and 100";
                pl.cfg.loot().set(path + ".chance", v);
                pl.cfg.save("loot");
                return null;
            });
        } else if (click.isRightClick()) {
            prompt(p, tier, h.page, "amount as min-max (example 2-5)", in -> {
                try {
                    String[] a = in.split("-");
                    int mn = Integer.parseInt(a[0].trim()), mx = a.length > 1 ? Integer.parseInt(a[1].trim()) : mn;
                    if (mn < 1 || mx < mn) return "invalid range";
                    pl.cfg.loot().set(path + ".amount-min", mn);
                    pl.cfg.loot().set(path + ".amount-max", mx);
                    pl.cfg.save("loot");
                    return null;
                } catch (Exception ex) { return "use the form 2-5"; }
            });
        }
    }
}
