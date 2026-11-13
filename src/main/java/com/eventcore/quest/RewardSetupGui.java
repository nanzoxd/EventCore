package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quest reward, as simple as the bounty menu: ONE free slot in the middle for the winner's reward (any item, exactly as placed),
 * a timer, a green start button and a red cancel button.
 * START mode (/quest start): the event only starts when you press start; every other way out returns the item.
 * EDIT mode (quest admin): closing the menu saves.
 */
public final class RewardSetupGui implements Listener {
    public enum Mode { START, EDIT }

    private static final int INFO = 4, TIMER = 10, REWARD = 13, START = 16, CANCEL = 22;
    private static final String NAME = "<gradient:#9babc7:#E7E7E7:#E7E7E7>", END = "</gradient>";

    static final class Holder implements InventoryHolder {
        Mode mode; int minutes; boolean finished;
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;
    private final QuestManager qm;

    public RewardSetupGui(EventCorePlugin pl, QuestManager qm) { this.pl = pl; this.qm = qm; }

    /** Only the winner gets a reward. */
    public int places() { return 1; }

    private static boolean has(ItemStack i) { return i != null && !i.getType().isAir(); }

    private ItemStack it(Material m, String name, List<String> lore) { return QuestGui.item(pl, m, name, lore, Map.of(), false); }

    public void open(Player p, Mode mode, int minutes) {
        Holder h = new Holder();
        h.mode = mode; h.minutes = Math.max(1, minutes);
        h.inv = Bukkit.createInventory(h, 27, pl.text.render(NAME + (mode == Mode.START ? "quest reward" : "quest reward | edit") + END, Map.of()));
        ItemStack filler = it(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) if (i != REWARD) h.inv.setItem(i, filler.clone());
        h.inv.setItem(INFO, it(Material.WRITTEN_BOOK, NAME + "winner reward" + END, List.of("<#AAAAAA>place the reward in the middle slot", "<#7F7F7F>the #1 player gets it when the event ends")));
        buttons(h);
        if (mode == Mode.EDIT) {                                                // show what is saved
            List<ItemStack> saved = qm.rewards().get(1);
            if (saved != null && !saved.isEmpty()) h.inv.setItem(REWARD, saved.get(0).clone());
        }
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private void buttons(Holder h) {
        if (h.mode == Mode.START) {
            h.inv.setItem(TIMER, it(Material.CLOCK, NAME + "event time" + END, List.of("<#AAAAAA>" + h.minutes + " minutes", "", "<#7F7F7F>left-click +10 | right-click -10", "<#7F7F7F>shift-click +1 | shift-right -1")));
            h.inv.setItem(START, it(Material.GREEN_CONCRETE, NAME + "start event" + END, List.of("<#AAAAAA>saves the reward and starts", "<#AAAAAA>the quest event", "", "<#7F7F7F>left-click to start")));
            h.inv.setItem(CANCEL, it(Material.RED_CONCRETE, NAME + "cancel" + END, List.of("<#AAAAAA>the reward is returned to you", "", "<#7F7F7F>left-click to close")));
        } else {
            h.inv.setItem(START, it(Material.GREEN_CONCRETE, NAME + "save reward" + END, List.of("<#AAAAAA>closing the menu saves too", "", "<#7F7F7F>left-click to save")));
            h.inv.setItem(CANCEL, it(Material.RED_CONCRETE, NAME + "close" + END, List.of("", "<#7F7F7F>left-click to close")));
        }
    }

    private Map<Integer, List<ItemStack>> collect(Holder h) {
        ItemStack r = h.inv.getItem(REWARD);
        return has(r) ? Map.of(1, List.of(r.clone())) : Map.of();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }   // double click must not pull the panes
        int raw = e.getRawSlot();
        if (raw >= top.getSize()) return;                      // own inventory: free (shift-click only fits the empty reward slot)
        if (raw == REWARD) return;                             // the reward slot takes anything, exactly as placed
        e.setCancelled(true);
        switch (raw) {
            case TIMER -> {
                if (h.mode != Mode.START) return;
                int step = e.getClick().isShiftClick() ? 1 : 10;
                int d = e.getClick().isLeftClick() ? step : e.getClick().isRightClick() ? -step : 0;
                if (d == 0) return;
                h.minutes = Math.max(1, h.minutes + d);
                buttons(h);
            }
            case START -> confirm(p, h);
            case CANCEL -> pl.sched.entity(p, p::closeInventory);   // onClose returns the item (or saves in edit mode)
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        for (int s : e.getRawSlots()) if (s < h.inv.getSize() && s != REWARD) { e.setCancelled(true); return; }
    }

    private void confirm(Player p, Holder h) {
        Map<Integer, List<ItemStack>> rewards = collect(h);
        if (h.mode == Mode.START) {
            if (rewards.isEmpty() && pl.cfg.config().getBoolean("quest-require-rewards", true)) { p.sendMessage(qm.msg("setup-empty")); return; }
            if (qm.running) { p.sendMessage(qm.msg("already-running")); return; }
            qm.setRewards(rewards);                                              // saved to the database
            h.finished = true;
            pl.sched.entity(p, p::closeInventory);
            String err = pl.events.start("quest", h.minutes, true);
            p.sendMessage(err != null ? qm.msg("start-failed", "reason", err) : qm.msg("setup-started", "minutes", h.minutes));
        } else {
            qm.setRewards(rewards);
            h.finished = true;
            pl.sched.entity(p, p::closeInventory);
            p.sendMessage(qm.msg("rewards-saved"));
        }
    }

    /** Never lose the admin's item: every way out except a started event returns it (edit mode saves instead). */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || !(e.getPlayer() instanceof Player p)) return;
        if (h.finished) return;
        h.finished = true;
        if (h.mode == Mode.START) {
            ItemStack r = h.inv.getItem(REWARD);
            if (has(r)) { h.inv.setItem(REWARD, null); Util.give(p, r); }
            p.sendMessage(qm.msg("setup-cancelled"));
        } else { qm.setRewards(collect(h)); p.sendMessage(qm.msg("rewards-saved")); }
    }
}
