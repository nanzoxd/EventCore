package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Mouse;
import com.eventcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Do you want to go to the event?" menu of the Natural Disaster.
 * Shown when the event starts, when a player joins the server during it, and by /tpdisaster.
 * Yes teleports the player into the arena (only once per event: after dying or leaving they cannot come back),
 * No keeps them where they are (nothing is taken from them, /tpdisaster opens the menu again).
 */
public final class DisasterInviteGui implements Listener {
    private static final int YES = 11, INFO = 13, NO = 15;
    private static final String NAME = "<gradient:#d8b04a:#f1d58f:#ffd7ca>";
    private static final String NAME_END = "</gradient>";

    static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public DisasterInviteGui(EventCorePlugin pl) { this.pl = pl; }

    private ItemStack button(Material m, String name, List<String> lore, Map<String, Object> ph) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, ph));
        if (lore != null && !lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String line : lore) l.add(pl.text.item(line, ph));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** Must be called on the player's own thread. */
    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 27, pl.text.render(NAME + "natural disaster" + NAME_END, Map.of()));
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ", null, Map.of());
        for (int i = 0; i < 27; i++) h.inv.setItem(i, pane);

        Map<String, Object> ph = pl.disaster.statusPlaceholders();
        h.inv.setItem(YES, button(Material.LIME_CONCRETE, NAME + "yes, take me to the arena" + NAME_END, List.of(
                "<color:#a8a8a8>last player standing wins</color>",
                "<color:#a8a8a8>the border shrinks, disasters hit</color>",
                "<color:#7a7a7a>if you are eliminated you cannot return</color>",
                "", Mouse.left(pl, "teleport to the arena")), ph));
        h.inv.setItem(INFO, button(Material.LIGHTNING_ROD, NAME + "natural disaster" + NAME_END, List.of(
                "<color:#a8a8a8>Time left: {time}</color>",
                "<color:#a8a8a8>Alive: {alive} of {total}</color>",
                "<color:#a8a8a8>Border: {radius} blocks</color>"), ph));
        h.inv.setItem(NO, button(Material.RED_CONCRETE, NAME + "no, stay here" + NAME_END, List.of(
                "<color:#a8a8a8>you are not teleported</color>",
                "<color:#7a7a7a>/tpdisaster opens this menu again</color>",
                "", Mouse.left(pl, "stay out")), ph));
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != h.inv) return;
        int slot = e.getSlot();
        if (slot == YES) {
            p.closeInventory();
            pl.disaster.accept(p);
        } else if (slot == NO) {
            p.closeInventory();
            p.sendMessage(pl.msg("disaster.invite-declined"));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
