package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import com.eventcore.quest.QuestManager.Status;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * /quest: ONE single menu with the quests (max 45, no pages), only as tall as the quests need: one row per 9 quests plus the glass row. Last row is glass with the scoreboard and a red close pane
 * in the middle. Left-click starts a quest, right-click abandons it.
 */
public final class QuestGui implements Listener {
    static final class Holder implements InventoryHolder {
        Inventory inv;
        int base;                                   // first slot of the glass row (the GUI is only as tall as it needs to be)
        final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final String NAME = "<gradient:#9babc7:#E7E7E7:#E7E7E7>", END = "</gradient>";
    private static final int MAX = 45;
    private final EventCorePlugin pl;
    private final QuestManager qm;

    public QuestGui(EventCorePlugin pl, QuestManager qm) { this.pl = pl; this.qm = qm; }

    // ================================================================================== items
    /** Shared item builder (also used by the quest admin and reward menus). */
    static ItemStack item(EventCorePlugin pl, Material m, String name, List<String> lore, Map<String, Object> ph, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, ph));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String s : lore) l.add(pl.text.item(s, ph));
        meta.lore(l);
        if (glow) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack glass(Material m, String name, List<String> lore) { return item(pl, m, name, lore, Map.of(), false); }

    // ================================================================================== open / refresh
    public void open(Player p) {
        Holder h = new Holder();
        int rows = Math.max(2, Math.min(6, (Math.min(MAX, qm.reg.all().size()) + 8) / 9 + 1));   // fewer quests = smaller menu
        h.base = (rows - 1) * 9;
        h.inv = Bukkit.createInventory(h, rows * 9, pl.text.render(NAME + "quests" + END, Map.of()));
        render(h, p);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    /** Live refresh: every open quest menu is redrawn in place (no reopening). */
    public void refreshOpen() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            pl.sched.entity(p, () -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) render(h, p);
            });
        }
    }

    // ================================================================================== render
    private void set(Holder h, int slot, ItemStack it, BiConsumer<Player, ClickType> act) {
        h.inv.setItem(slot, it);
        if (act != null) h.actions.put(slot, act);
    }

    private void render(Holder h, Player p) {
        h.inv.clear();
        h.actions.clear();
        PlayerQuestData d = qm.dataFor(p);

        // quests (max 45): active first, then available, completed, locked
        List<Quest> all = new ArrayList<>(qm.reg.all());
        all.sort(Comparator.comparingInt(q -> switch (qm.status(d, q)) { case ACTIVE -> 0; case AVAILABLE -> 1; case COMPLETED -> 2; default -> 3; }));
        for (int i = 0; i < Math.min(Math.min(MAX, h.base), all.size()); i++) {
            Quest q = all.get(i);
            set(h, i, questItem(d, q), (pp, c) -> clickQuest(h, pp, q, c));
        }

        // last row: glass
        ItemStack fill = glass(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = h.base; i < h.base + 9; i++) h.inv.setItem(i, fill);
        set(h, h.base + 4, glass(Material.RED_STAINED_GLASS_PANE, "<#EE2424>close", List.of()), (pp, c) -> pp.closeInventory());
        set(h, h.base + 2, board(p, d), null);
    }

    /** Simple scoreboard: top 5 plus your own place, all in one tooltip. */
    private ItemStack board(Player p, PlayerQuestData d) {
        List<String> lore = new ArrayList<>();
        List<PlayerQuestData> top = qm.top(QuestManager.TOP);
        if (top.isEmpty()) lore.add("<#7F7F7F>nobody has points yet");
        for (int i = 0; i < top.size(); i++) {
            PlayerQuestData t = top.get(i);
            boolean me = t.uuid.equals(p.getUniqueId());
            lore.add((me ? "<#00FF55>" : "<#FFFFFF>") + (i + 1) + ". {n" + i + "} <#AAAAAA>- <#00FF55>" + t.points);
        }
        int r = qm.rankOf(p.getUniqueId());
        lore.add("");
        lore.add("<#AAAAAA>You: <#FFFFFF>" + (r == 0 ? "no rank yet" : "#" + r) + " <#AAAAAA>- <#00FF55>" + d.points + " <#AAAAAA>points");
        Map<String, Object> ph = new HashMap<>();
        for (int i = 0; i < top.size(); i++) ph.put("n" + i, top.get(i).name);
        return item(pl, Material.NETHER_STAR, NAME + "scoreboard" + END, lore, ph, false);
    }

    private void clickQuest(Holder h, Player p, Quest q, ClickType c) {
        Status s = qm.status(qm.dataFor(p), q);
        if (c.isRightClick() && s == Status.ACTIVE) {
            String err = qm.abandon(p, q);
            p.sendMessage(err == null ? qm.msg("abandoned", "title", q.title) : qm.msg(err));
        } else if (c.isLeftClick() && s == Status.AVAILABLE) {
            String err = qm.startQuest(p, q);
            p.sendMessage(err == null ? qm.msg("started", "title", q.title) : qm.msg(err));
        } else return;
        render(h, p);
    }

    /** One quest: title, objective, progress and reward. Nothing else. */
    private ItemStack questItem(PlayerQuestData d, Quest q) {
        Status s = qm.status(d, q);
        int cur = d.state(q.id) == PlayerQuestData.DONE ? q.amount : d.progress(q.id);
        String col = switch (s) { case ACTIVE -> "<#FFD54A>"; case COMPLETED -> "<#00FF55>"; case LOCKED -> "<#7F7F7F>"; default -> "<#FFFFFF>"; };
        Map<String, Object> ph = new HashMap<>();
        ph.put("title", q.title);
        ph.put("obj", q.objective());
        Quest req = qm.reg.get(q.requires);
        ph.put("req", req == null ? "" : req.title);

        List<String> lore = new ArrayList<>();
        lore.add("<#AAAAAA>{obj}");
        switch (s) {
            case LOCKED -> lore.add(req != null ? "<#EE2424>Finish first: <#FFFFFF>{req}" : "<#EE2424>Locked");
            case COMPLETED -> lore.add("<#00FF55>Completed");
            default -> lore.add("<#A5B8FF>Progress: <#FFFFFF>" + cur + "<#AAAAAA>/" + q.amount);
        }
        lore.add("<#A5B8FF>Reward: <#00FF55>1 point");
        if (s == Status.AVAILABLE) { lore.add(""); lore.add("<#7F7F7F>left-click to start"); }
        else if (s == Status.ACTIVE && qm.reg.allowAbandon) { lore.add(""); lore.add("<#7F7F7F>right-click to abandon"); }

        Material icon = s == Status.LOCKED ? Material.BARRIER : q.icon;
        return item(pl, icon, col + "{title}", lore, ph, s == Status.COMPLETED);   // only completed quests are enchanted
    }

    // ================================================================================== clicks
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != h.inv) return;
        BiConsumer<Player, ClickType> a = h.actions.get(e.getSlot());
        if (a != null) a.accept(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
