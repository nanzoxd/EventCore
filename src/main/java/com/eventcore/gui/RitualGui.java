package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.*;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Ritual Creator main GUI and the item selection GUI (layout from gui.yml). */
public final class RitualGui implements Listener {
    static final class MainHolder implements InventoryHolder {
        Inventory inv; final Map<Integer, String> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }
    static final class SelectHolder implements InventoryHolder {
        Inventory inv; boolean started; int slot;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public RitualGui(EventCorePlugin pl) { this.pl = pl; }

    private Map<String, Object> ph() {
        return Text.ph("minutes", pl.ritual.minutes(), "min", pl.ritual.minMinutes(), "max", pl.ritual.maxMinutes(),
                "palette_label", pl.ritual.paletteLabel(), "palette_color_raw", pl.ritual.paletteColor());
    }

    /** Nether star: the lore lists EVERY palette (current one marked), not just the two written in gui.yml. */
    private ItemStack paletteItem(ConfigurationSection s) {
        ItemStack it = GuiUtil.item(pl, s, ph());
        ItemMeta meta = it.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        List<String> cfg = s.getStringList("lore");
        if (!cfg.isEmpty()) lore.add(pl.text.item(cfg.get(0), ph()));
        String cur = pl.ritual.currentPalette();
        for (String n : pl.ritual.paletteList()) {
            String line = "<color:" + pl.ritual.paletteColor(n) + ">■</color> <color:#fffbf0>" + pl.ritual.paletteLabel(n) + "</color>"
                    + (n.equals(cur) ? " <color:#f1d58f>◀</color>" : "");
            lore.add(pl.text.item(line, Map.of()));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public void openMain(Player p) {
        ConfigurationSection g = pl.cfg.gui().getConfigurationSection("ritual-main");
        MainHolder h = new MainHolder();
        h.inv = Bukkit.createInventory(h, Math.max(1, Math.min(6, g.getInt("rows", 3))) * 9, pl.text.render(g.getString("title", "ritual creator"), Map.of()));
        ConfigurationSection items = g.getConfigurationSection("items");
        if (items != null) for (String k : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(k);
            if (s == null) continue;
            int slot = s.getInt("slot", 0);
            h.inv.setItem(slot, k.equals("palette") ? paletteItem(s) : GuiUtil.item(pl, s, ph()));
            h.actions.put(slot, k);
        }
        p.openInventory(h.inv);
    }

    public void openSelect(Player p) {
        ConfigurationSection g = pl.cfg.gui().getConfigurationSection("ritual-select");
        SelectHolder h = new SelectHolder();
        h.slot = g.getInt("input-slot", 13);
        int size = Math.max(1, Math.min(6, g.getInt("rows", 3))) * 9;
        h.inv = Bukkit.createInventory(h, size, pl.text.render(g.getString("title", "please select an item"), Map.of()));
        ItemStack filler = GuiUtil.item(pl, g.getConfigurationSection("filler"), Map.of());
        for (int i = 0; i < size; i++) if (i != h.slot) h.inv.setItem(i, filler.clone());
        p.openInventory(h.inv);
    }

    private Material fillerType() {
        Material m = Material.matchMaterial(pl.cfg.gui().getString("ritual-select.filler.material", "BLACK_STAINED_GLASS_PANE"));
        return m == null ? Material.BLACK_STAINED_GLASS_PANE : m;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (top.getHolder() instanceof MainHolder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            String act = h.actions.get(e.getSlot());
            if (act == null || !p.hasPermission("eventcore.ritual")) return;
            switch (act) {
                case "cancel" -> {
                    if (!pl.ritual.active()) p.sendMessage(pl.msg("ritual.no-active"));
                    else pl.ritual.stop(p);
                }
                case "creation" -> {
                    if (pl.ritual.active()) p.sendMessage(pl.msg("ritual.wait-active"));
                    else pl.sched.entity(p, () -> openSelect(p));
                }
                case "submit" -> {
                    if (pl.ritual.active()) pl.ritual.finishNow(); else p.sendMessage(pl.msg("ritual.no-active"));
                }
                case "time" -> {
                    int d = e.getClick().isLeftClick() ? 1 : e.getClick().isRightClick() ? -1 : 0;
                    if (d != 0) {
                        pl.ritual.setMinutes(pl.ritual.minutes() + d);
                        ConfigurationSection s = pl.cfg.gui().getConfigurationSection("ritual-main.items.time");
                        top.setItem(e.getSlot(), GuiUtil.item(pl, s, ph()));
                    }
                }
                case "palette" -> {
                    pl.ritual.cyclePalette();
                    ConfigurationSection s = pl.cfg.gui().getConfigurationSection("ritual-main.items.palette");
                    top.setItem(e.getSlot(), paletteItem(s));
                }
                default -> { }
            }
        } else if (top.getHolder() instanceof SelectHolder h) {
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
            boolean inTop = e.getRawSlot() < top.getSize();
            if (inTop) {
                if (e.getRawSlot() != h.slot) { e.setCancelled(true); return; }
                ItemStack in = top.getItem(h.slot), cursor = e.getCursor();
                boolean cursorEmpty = cursor == null || cursor.getType().isAir();
                if (in != null && !in.getType().isAir() && cursorEmpty) {
                    e.setCancelled(true);
                    ItemStack it = in.clone();
                    top.setItem(h.slot, null);
                    h.started = true;
                    pl.sched.entity(p, () -> {
                        p.closeInventory();
                        if (!pl.ritual.start(p, it)) {
                            Util.give(p, it);
                            p.sendMessage(pl.msg("ritual.wait-active"));
                        }
                    });
                }
            } else if (e.isShiftClick() && e.getCurrentItem() != null && e.getCurrentItem().getType() == fillerType()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof MainHolder) { e.setCancelled(true); return; }
        if (top.getHolder() instanceof SelectHolder h)
            for (int s : e.getRawSlots()) if (s < top.getSize() && s != h.slot) { e.setCancelled(true); return; }
    }

    /** Never lose the admin's item: closing without starting returns it. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof SelectHolder h && !h.started && e.getPlayer() instanceof Player p) {
            ItemStack it = h.inv.getItem(h.slot);
            if (it != null && !it.getType().isAir()) { h.inv.setItem(h.slot, null); Util.give(p, it); }
        }
    }
}
