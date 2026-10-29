package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /bountyadd &lt;player&gt;: one free reward slot (any item, enchanted, custom named, shulker... exactly as placed),
 * a timer button and a start button. Layout comes from gui.yml (bounty-gui).
 * The item in the reward slot is handed back on every way out except a successful start.
 */
public final class BountyGui implements Listener {
    static final class Holder implements InventoryHolder {
        Inventory inv;
        UUID target;
        String targetName;
        int minutes;
        int rewardSlot;
        boolean started;
        final Map<Integer, String> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public BountyGui(EventCorePlugin pl) { this.pl = pl; }

    private ConfigurationSection g() { return pl.cfg.gui().getConfigurationSection("bounty-gui"); }

    private Map<String, Object> ph(Holder h) {
        return Text.ph("player", h.targetName, "minutes", h.minutes, "min", pl.bounty.minMinutes(), "max", pl.bounty.maxMinutes(),
                "step", pl.bounty.stepMinutes(), "step5", pl.bounty.stepMinutes() * 5);
    }

    private ItemStack build(Holder h, String key, ConfigurationSection s) {
        ItemStack it = GuiUtil.item(pl, s, ph(h));
        if (key.equals("head") && it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(h.target));
            it.setItemMeta(sm);
        }
        return it;
    }

    public void open(Player admin, Player target) {
        ConfigurationSection g = g();
        Holder h = new Holder();
        h.target = target.getUniqueId();
        h.targetName = target.getName();
        h.minutes = pl.bounty.defaultMinutes();
        h.rewardSlot = g.getInt("reward-slot", 13);
        int size = Math.max(3, Math.min(6, g.getInt("rows", 3))) * 9;
        h.inv = Bukkit.createInventory(h, size, pl.text.render(g.getString("title", "bounty | {player}"), ph(h)));
        ItemStack filler = GuiUtil.item(pl, g.getConfigurationSection("filler"), Map.of());
        for (int i = 0; i < size; i++) if (i != h.rewardSlot) h.inv.setItem(i, filler.clone());
        ConfigurationSection items = g.getConfigurationSection("items");
        if (items != null) for (String k : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(k);
            if (s == null) continue;
            int slot = s.getInt("slot", 0);
            if (slot == h.rewardSlot || slot < 0 || slot >= size) continue;
            h.inv.setItem(slot, build(h, k, s));
            if (!k.equals("head")) h.actions.put(slot, k);
        }
        admin.openInventory(h.inv);
    }

    private static boolean has(ItemStack i) { return i != null && !i.getType().isAir(); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("eventcore.bounty")) { e.setCancelled(true); return; }
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }   // double click must not pull the panes
        int raw = e.getRawSlot();
        if (raw >= top.getSize()) return;                      // own inventory: free (shift-click only fits the empty reward slot)
        if (raw == h.rewardSlot) return;                       // the reward slot takes anything, exactly as placed
        e.setCancelled(true);
        String act = h.actions.get(raw);
        if (act == null) return;
        switch (act) {
            case "timer" -> {
                int step = pl.bounty.stepMinutes() * (e.getClick().isShiftClick() ? 5 : 1);
                int d = e.getClick().isLeftClick() ? step : e.getClick().isRightClick() ? -step : 0;
                if (d == 0) return;
                h.minutes = Math.max(pl.bounty.minMinutes(), Math.min(pl.bounty.maxMinutes(), h.minutes + d));
                top.setItem(raw, build(h, "timer", g().getConfigurationSection("items.timer")));
            }
            case "start" -> start(p, h, top);
            case "cancel" -> pl.sched.entity(p, p::closeInventory);   // onClose gives the item back
            default -> { }
        }
    }

    private void start(Player p, Holder h, Inventory top) {
        ItemStack reward = top.getItem(h.rewardSlot);
        if (!has(reward)) { p.sendMessage(pl.msg("bounty.error-need-reward")); return; }
        Player target = Bukkit.getPlayer(h.target);
        if (target == null) { p.sendMessage(pl.msg("bounty.error-not-online", "input", h.targetName)); return; }
        String err = pl.bounty.create(p, target, reward.clone(), h.minutes);
        if (err != null) { p.sendMessage(pl.msg("bounty.error-" + err, "player", h.targetName, "max", pl.bounty.max())); return; }
        top.setItem(h.rewardSlot, null);      // the bounty owns the item now: it can never be handed out twice
        h.started = true;
        pl.sched.entity(p, p::closeInventory);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        for (int s : e.getRawSlots()) if (s < top.getSize() && s != h.rewardSlot) { e.setCancelled(true); return; }
    }

    /** Never lose the admin's item: every way out except a started bounty returns it. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || h.started || !(e.getPlayer() instanceof Player p)) return;
        ItemStack it = h.inv.getItem(h.rewardSlot);
        if (has(it)) { h.inv.setItem(h.rewardSlot, null); Util.give(p, it); }
    }
}
