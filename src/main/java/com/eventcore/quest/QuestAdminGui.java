package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/** /quest admin: event control, quest management (add / remove / edit objective, title), rewards, leaderboard and reset. */
public final class QuestAdminGui implements Listener {
    public enum View { MAIN, QUESTS, EDIT, CONFIRM }

    static final class Holder implements InventoryHolder {
        View view; int page; String qid;
        Inventory inv;
        final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final String NAME = "<gradient:#9babc7:#E7E7E7:#E7E7E7>", END = "</gradient>";
    private final EventCorePlugin pl;
    private final QuestManager qm;

    public QuestAdminGui(EventCorePlugin pl, QuestManager qm) { this.pl = pl; this.qm = qm; }

    public void open(Player p) { open(p, View.MAIN, 0, null); }

    public void open(Player p, View v, int page, String qid) {
        Holder h = new Holder();
        h.view = v; h.page = page; h.qid = qid;
        h.inv = Bukkit.createInventory(h, v == View.QUESTS ? 54 : 27, pl.text.render(NAME + switch (v) {
            case MAIN -> "quest admin"; case QUESTS -> "manage quests"; case EDIT -> "edit quest"; case CONFIRM -> "reset all statistics?"; } + END, Map.of()));
        render(h, p);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    private void go(Player p, View v, int page, String qid) { pl.sched.entity(p, () -> open(p, v, page, qid)); }

    private void set(Holder h, int slot, ItemStack it, BiConsumer<Player, ClickType> act) {
        h.inv.setItem(slot, it);
        if (act != null) h.actions.put(slot, act);
    }

    private ItemStack it(Material m, String name, List<String> lore, boolean glow) { return QuestGui.item(pl, m, name, lore, Map.of(), glow); }

    private void render(Holder h, Player p) {
        h.inv.clear(); h.actions.clear();
        switch (h.view) {
            case MAIN -> main(h, p);
            case QUESTS -> quests(h, p);
            case EDIT -> edit(h, p);
            case CONFIRM -> confirm(h, p);
        }
    }

    // ---------------------------------------------------------------- main
    private void main(Holder h, Player p) {
        int items = 0;
        for (List<ItemStack> l : qm.rewards().values()) items += l.size();
        String state = !qm.running ? "<#EE2424>not running" : qm.paused ? "<#FFA100>paused" : "<#00FF55>running";
        set(h, 4, it(Material.CLOCK, NAME + "event information" + END, List.of("<#AAAAAA>Status: " + state, "<#AAAAAA>Time left: <#FFFFFF>" + qm.timeLeft(), "<#AAAAAA>Quests: <#FFFFFF>" + qm.reg.size(),
                "<#AAAAAA>Players with points: <#FFFFFF>" + qm.participants(), "<#AAAAAA>Quests completed: <#FFFFFF>" + qm.totalCompleted(), "<#AAAAAA>Reward items saved: <#FFD54A>" + items), qm.running), null);
        if (!qm.running) set(h, 10, it(Material.LIME_DYE, "<#00FF55>start event", List.of("<#AAAAAA>Opens the reward setup first"), true), (pp, c) -> {
            pl.sched.entity(pp, () -> pl.rewardGui.open(pp, RewardSetupGui.Mode.START, pl.events.get("quest") == null ? 60 : pl.events.get("quest").sec().getInt("duration-minutes", 60)));
        });
        else set(h, 10, it(Material.RED_DYE, "<#EE2424>stop event", List.of("<#AAAAAA>Ends it now and gives the rewards", "<#AAAAAA>to the current top 5"), false), (pp, c) -> {
            pl.events.stop("quest", true);
            pp.sendMessage(qm.msg("stopped"));
            render(h, pp);
        });
        if (qm.running) set(h, 11, it(qm.paused ? Material.CLOCK : Material.SNOWBALL, qm.paused ? "<#00FF55>resume event" : "<#FFA100>pause event", List.of("<#AAAAAA>Progress and the timer are frozen while paused"), false), (pp, c) -> {
            if (qm.paused) { qm.resume(); pp.sendMessage(qm.msg("resumed")); } else { qm.pause(); pp.sendMessage(qm.msg("paused")); }
            render(h, pp);
        });
        set(h, 13, it(Material.WRITABLE_BOOK, NAME + "manage quests" + END, List.of("<#AAAAAA>Add, remove and edit quests", "<#AAAAAA>objectives and titles"), false), (pp, c) -> go(pp, View.QUESTS, 0, null));
        set(h, 14, it(Material.CHEST, NAME + "edit reward" + END, List.of("<#AAAAAA>The winner's reward item"), false), (pp, c) -> pl.sched.entity(pp, () -> pl.rewardGui.open(pp, RewardSetupGui.Mode.EDIT, 0)));
        set(h, 15, it(Material.NETHER_STAR, NAME + "quest menu" + END, List.of("<#AAAAAA>Open /quest as a player sees it"), false), (pp, c) -> pl.sched.entity(pp, () -> pl.questGui.open(pp)));
        set(h, 16, it(Material.TNT, "<#EE2424>reset statistics", List.of("<#AAAAAA>Removes all points and progress", "<#EE2424>cannot be undone"), false), (pp, c) -> go(pp, View.CONFIRM, 0, null));
        set(h, 22, it(Material.PAPER, "<#A5B8FF>reload quests.yml", List.of(), false), (pp, c) -> { qm.reload(); pp.sendMessage(qm.msg("reloaded")); render(h, pp); });
    }

    // ---------------------------------------------------------------- quest list
    private void quests(Holder h, Player p) {
        List<Quest> all = new ArrayList<>(qm.reg.all());
        int pages = Math.max(1, (all.size() + 44) / 45);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        for (int i = 0; i < 45; i++) {
            int idx = h.page * 45 + i;
            if (idx >= all.size()) break;
            Quest q = all.get(idx);
            ItemStack stack = QuestGui.item(pl, q.icon, "<#FFFFFF>{t}", List.of("<#A5B8FF>id: <#FFFFFF>" + q.id, "<#A5B8FF>type: <#FFFFFF>" + q.type.name().toLowerCase(), "<#A5B8FF>objective: <#FFFFFF>" + q.objective(),
                    "", "<#7F7F7F>left-click: edit | shift-right-click: delete"), Map.of("t", q.title), false);
            set(h, i, stack, (pp, c) -> {
                if (c == ClickType.SHIFT_RIGHT) { qm.reg.remove(q.id); pp.sendMessage(qm.msg("quest-removed", "title", q.title)); render(h, pp); }
                else go(pp, View.EDIT, 0, q.id);
            });
        }
        set(h, 45, it(Material.ARROW, "<#A5B8FF>back", List.of(), false), (pp, c) -> go(pp, View.MAIN, 0, null));
        if (h.page > 0) set(h, 47, it(Material.SPECTRAL_ARROW, "<#A5B8FF>previous page", List.of(), false), (pp, c) -> { h.page--; render(h, pp); });
        set(h, 49, it(Material.EMERALD, "<#00FF55>add quest", List.of("<#AAAAAA>You will be asked for the details in chat"), true), (pp, c) -> addQuest(pp));
        if (h.page < pages - 1) set(h, 51, it(Material.SPECTRAL_ARROW, "<#A5B8FF>next page", List.of(), false), (pp, c) -> { h.page++; render(h, pp); });
    }

    /** chat format:  id TYPE TARGET|* amount title...   e.g.  mine_emerald MINE_BLOCK EMERALD_ORE 10 Emerald Miner */
    private void addQuest(Player p) {
        p.closeInventory();
        pl.chat.ask(p, qm.msg("add-prompt"), in -> {
            String[] a = in.split("\\s+", 5);
            if (a.length < 5) return "need: id TYPE TARGET|* amount title";
            String id = a[0].toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]{2,32}")) return "the id may only contain letters, numbers and _";
            if (qm.reg.get(id) != null) return "that id already exists";
            QuestType t = QuestType.of(a[1]);
            if (t == null) return "unknown type, use one of: " + String.join(", ", java.util.Arrays.stream(QuestType.values()).map(Enum::name).toList());
            Quest q = new Quest(id, a[4]);
            q.type = t;
            if (!a[2].equals("*")) for (String x : a[2].split(",")) q.targets.add(x.toUpperCase(Locale.ROOT));
            try { q.amount = Math.max(1, Integer.parseInt(a[3])); } catch (NumberFormatException e) { return "amount must be a number"; }
            q.description = q.objective() + ".";
            q.category = qm.reg.categories().isEmpty() ? "misc" : qm.reg.categories().iterator().next().id();
            Material icon = q.targets.isEmpty() ? null : Material.matchMaterial(q.targets.iterator().next());
            q.icon = icon != null && icon.isItem() ? icon : Material.PAPER;
            qm.reg.add(q);
            return null;
        }, () -> { p.sendMessage(qm.msg("quest-added")); open(p, View.QUESTS, 9999, null); });
    }

    // ---------------------------------------------------------------- edit one quest
    private void edit(Holder h, Player p) {
        Quest q = qm.reg.get(h.qid);
        if (q == null) { go(p, View.QUESTS, 0, null); return; }
        set(h, 4, QuestGui.item(pl, q.icon, "<#FFFFFF>{t}", List.of("<#A5B8FF>id: <#FFFFFF>" + q.id, "<#A5B8FF>objective: <#FFFFFF>" + q.objective(),
                "<#A5B8FF>category: <#FFFFFF>" + q.category, "<#7F7F7F>hold an item and click here to change the icon"), Map.of("t", q.title), true), (pp, c) -> {
            ItemStack held = pp.getInventory().getItemInMainHand();
            if (held.getType() != Material.AIR) { q.icon = held.getType(); qm.reg.changed(); }
            render(h, pp);
        });
        set(h, 10, it(Material.RED_DYE, "<#EE2424>amount -1", List.of("<#7F7F7F>shift: -10"), false), (pp, c) -> { q.amount = Math.max(1, q.amount - (c.isShiftClick() ? 10 : 1)); qm.reg.changed(); render(h, pp); });
        set(h, 11, it(Material.TARGET, "<#A5B8FF>amount: <#FFFFFF>" + q.amount, List.of("<#AAAAAA>" + q.objective()), false), null);
        set(h, 12, it(Material.LIME_DYE, "<#00FF55>amount +1", List.of("<#7F7F7F>shift: +10"), false), (pp, c) -> { q.amount += c.isShiftClick() ? 10 : 1; qm.reg.changed(); render(h, pp); });
        set(h, 20, it(Material.NAME_TAG, "<#A5B8FF>change title", List.of("<#AAAAAA>" + q.title), false), (pp, c) -> ask(pp, h, "title", in -> { q.title = in; }));
        set(h, 21, it(Material.WRITABLE_BOOK, "<#A5B8FF>change objective targets", List.of("<#AAAAAA>" + (q.targets.isEmpty() ? "anything" : String.join(", ", q.targets)), "<#7F7F7F>e.g. DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE or *"), false), (pp, c) -> ask(pp, h, "targets (comma separated, or *)", in -> {
            q.targets.clear();
            if (!in.equals("*")) for (String x : in.split(",")) if (!x.isBlank()) q.targets.add(x.trim().toUpperCase(Locale.ROOT));
        }));
        set(h, 22, it(Material.PAPER, "<#A5B8FF>change description", List.of("<#AAAAAA>" + q.description), false), (pp, c) -> ask(pp, h, "description", in -> { q.description = in; }));
        set(h, 24, it(Material.TNT, "<#EE2424>delete quest", List.of("<#7F7F7F>shift-click to confirm"), false), (pp, c) -> {
            if (!c.isShiftClick()) return;
            qm.reg.remove(q.id);
            go(pp, View.QUESTS, 0, null);
        });
        set(h, 18, it(Material.ARROW, "<#A5B8FF>back", List.of(), false), (pp, c) -> go(pp, View.QUESTS, 0, null));
    }

    private void ask(Player p, Holder h, String what, java.util.function.Consumer<String> apply) {
        p.closeInventory();
        pl.chat.ask(p, qm.msg("edit-prompt", "what", what), in -> { apply.accept(in); qm.reg.changed(); return null; }, () -> open(p, View.EDIT, 0, h.qid));
    }

    // ---------------------------------------------------------------- reset confirmation
    private void confirm(Holder h, Player p) {
        set(h, 11, it(Material.LIME_DYE, "<#EE2424>yes, reset everything", List.of("<#AAAAAA>All points, progress and ranks are removed"), true), (pp, c) -> {
            qm.resetAll();
            pp.sendMessage(qm.msg("stats-reset"));
            go(pp, View.MAIN, 0, null);
        });
        set(h, 15, it(Material.RED_DYE, "<#00FF55>no, go back", List.of(), false), (pp, c) -> go(pp, View.MAIN, 0, null));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != h.inv) return;
        if (!p.hasPermission("eventcore.quest.admin")) { p.closeInventory(); return; }
        BiConsumer<Player, ClickType> a = h.actions.get(e.getSlot());
        if (a != null) a.accept(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
