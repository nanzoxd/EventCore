package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager;
import com.eventcore.altar.AltarManager.Ingredient;
import com.eventcore.altar.AltarManager.Recipe;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import net.kyori.adventure.text.Component;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Altar recipe GUI. /altar recipes opens the list; click a recipe to edit it or "new recipe" to make one.
 * Editor: put the ingredients in the 3x3 grid, the result in the right slot - click an item to take it out again.
 * The stack size you put in is the amount. Items you place are handed back when you close the editor; items that
 * were loaded from an existing recipe are only copies and simply disappear when taken out.
 */
public final class AltarGui implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int OUT = 24, ARROW = 22, INFO = 4, MINUTES = 40, SAVE = 48, BACK = 45, DELETE = 53;
    private static final int PAGE = 45, PREV = 45, NEW = 49, NEXT = 53;
    private static final int[] EDIT = {10, 11, 12, 19, 20, 21, 28, 29, 30, 24};   // 0-8 grid, 9 output
    private static final Map<Integer, Integer> MIN_BTN = Map.of(38, -10, 39, -1, 41, 1, 42, 10);

    static final class ListHolder implements InventoryHolder {
        Inventory inv; int page; final Map<Integer, String> ids = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    static final class EditHolder implements InventoryHolder {
        Inventory inv; String id; boolean existing, confirmDelete; int minutes;
        final Ingredient[] ing = new Ingredient[9];      // the original ingredient behind a loaded slot
        final boolean[] loaded = new boolean[10];        // slot still shows the loaded copy (not a real item)
        final List<Ingredient> extra = new ArrayList<>();   // inputs beyond 9 (yaml only) are kept untouched
        ItemStack output;                                // the original output behind a loaded slot
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public AltarGui(EventCorePlugin pl) { this.pl = pl; }

    private AltarManager am() { return pl.altar; }

    private ItemStack button(Material m, String name, List<String> lore, Map<String, Object> ph) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, ph));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(pl.text.item(line, ph));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack pane() { return button(Material.GRAY_STAINED_GLASS_PANE, " ", null, Map.of()); }

    private static boolean has(ItemStack s) { return s != null && !s.getType().isAir(); }

    // ---------------- list ----------------
    public void openList(Player p, int page) {
        ListHolder h = new ListHolder();
        List<Recipe> all = new ArrayList<>(am().recipes());
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        h.page = Math.max(0, Math.min(page, pages - 1));
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(am().cfg().getString("gui.list-title", "<g>altar recipes</g>"), Map.of()));
        for (int i = 0; i < PAGE; i++) {
            int idx = h.page * PAGE + i;
            if (idx >= all.size()) break;
            Recipe r = all.get(idx);
            h.inv.setItem(i, icon(r));
            h.ids.put(i, r.id);
        }
        for (int i = PAGE; i < 54; i++) h.inv.setItem(i, pane());
        if (h.page > 0) h.inv.setItem(PREV, button(Material.PAPER, "<color:#e8c670>◀ previous page</color>", null, Map.of()));
        if (h.page < pages - 1) h.inv.setItem(NEXT, button(Material.PAPER, "<color:#e8c670>next page ▶</color>", null, Map.of()));
        h.inv.setItem(NEW, button(Material.NETHER_STAR, "<color:#d8b04a>✦ new recipe</color>",
                List.of("<color:#fffbf0>click, then type an id in chat</color>"), Map.of()));
        p.openInventory(h.inv);
    }

    private ItemStack icon(Recipe r) {
        ItemStack it = r.output.clone();
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Component.empty());
        lore.add(pl.text.item("<color:#d8b04a>ingredients</color>", Map.of()));
        for (Ingredient in : r.inputs) lore.add(pl.text.item("<color:#fffbf0>- {line}</color>", Text.ph("line", in.line())));
        lore.add(pl.text.item("<color:#ffd7ca>{minutes} minutes - id {id}</color>", Text.ph("minutes", r.minutes, "id", r.id)));
        lore.add(Component.empty());
        lore.add(pl.text.item("<color:#f1d58f>click to edit</color>", Map.of()));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private void newRecipe(Player p) {
        pl.sched.entity(p, () -> {
            p.closeInventory();
            AtomicReference<String> made = new AtomicReference<>();
            pl.chat.ask(p, pl.text.render("<g>type a recipe id in chat (letters, numbers, _ -), or cancel</g>", Map.of()), msg -> {
                String id = msg.toLowerCase(Locale.ROOT).trim();
                if (!id.matches("[a-z0-9_\\-]{1,32}")) return "use 1-32 letters, numbers, _ or -";
                if (am().recipe(id) != null) return "that id already exists";
                made.set(id);
                return null;
            }, () -> {
                String id = made.get();
                if (id == null) openList(p, 0); else openEdit(p, id);
            });
        });
    }

    // ---------------- editor ----------------
    /** Opens the editor for an existing recipe, or a blank one when the id is new. */
    public void openEdit(Player p, String id) {
        EditHolder h = new EditHolder();
        h.id = id;
        Recipe r = am().recipe(id);
        h.existing = r != null;
        h.minutes = r != null ? r.minutes : am().defaultMinutes();
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(am().cfg().getString("gui.edit-title", "<g>edit altar recipe</g>"), Map.of()));
        for (int i = 0; i < 54; i++) h.inv.setItem(i, pane());
        for (int s : EDIT) h.inv.setItem(s, null);
        if (r != null) {
            for (int i = 0; i < r.inputs.size(); i++) {
                if (i < 9) {
                    h.ing[i] = r.inputs.get(i);
                    h.loaded[i] = true;
                    h.inv.setItem(GRID[i], am().stackOf(h.ing[i]));
                } else h.extra.add(r.inputs.get(i));
            }
            if (r.output != null) {
                h.output = r.output.clone();
                h.loaded[9] = true;
                ItemStack o = r.output.clone();
                o.setAmount(Math.max(1, Math.min(64, o.getAmount())));
                h.inv.setItem(OUT, o);
            }
        }
        decorate(h);
        p.openInventory(h.inv);
    }

    /** Buttons and labels (never touches the item slots). */
    private void decorate(EditHolder h) {
        Inventory inv = h.inv;
        inv.setItem(ARROW, button(Material.ARROW, "<color:#e8c670>➜ ritual</color>", null, Map.of()));
        inv.setItem(INFO, button(Material.BOOK, "<color:#d8b04a>recipe {id}</color>", List.of(
                "<color:#fffbf0>put the ingredients in the grid</color>",
                "<color:#fffbf0>and the result in the right slot</color>",
                "<color:#fffbf0>click an item to take it out</color>",
                "<color:#fffbf0>stack size = amount</color>",
                "<color:#ffd7ca>your items are returned on close</color>"), Text.ph("id", h.id)));
        inv.setItem(38, button(Material.RED_STAINED_GLASS_PANE, "<color:#ffcfca>-10 minutes</color>", null, Map.of()));
        inv.setItem(39, button(Material.RED_STAINED_GLASS_PANE, "<color:#ffcfca>-1 minute</color>", null, Map.of()));
        inv.setItem(MINUTES, button(Material.CLOCK, "<color:#e8c670>◷ ritual: <color:#fff0cc>{minutes} minutes</color></color>", null, Text.ph("minutes", h.minutes)));
        inv.setItem(41, button(Material.LIME_STAINED_GLASS_PANE, "<color:#bfe8a8>+1 minute</color>", null, Map.of()));
        inv.setItem(42, button(Material.LIME_STAINED_GLASS_PANE, "<color:#bfe8a8>+10 minutes</color>", null, Map.of()));
        inv.setItem(SAVE, button(Material.LIME_CONCRETE, "<color:#bfe8a8>✔ save recipe</color>", null, Map.of()));
        inv.setItem(BACK, button(Material.OAK_DOOR, "<color:#e8c670>◀ back (discard changes)</color>", null, Map.of()));
        if (h.existing) inv.setItem(DELETE, button(Material.BARRIER,
                h.confirmDelete ? "<color:#ff8a80>click again to DELETE</color>" : "<color:#ffcfca>✖ delete recipe</color>", null, Map.of()));
    }

    private static int editIndex(int slot) {
        for (int i = 0; i < EDIT.length; i++) if (EDIT[i] == slot) return i;
        return -1;
    }

    private void editClick(Player p, EditHolder h, InventoryClickEvent e) {
        int raw = e.getRawSlot();
        Inventory inv = h.inv;
        if (raw >= 54) {   // the player's own inventory: normal, except shift-click sends the item into the grid
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
            if (!e.isShiftClick()) return;
            e.setCancelled(true);
            ItemStack cur = e.getCurrentItem();
            if (!has(cur)) return;
            for (int i = 0; i < EDIT.length; i++) {
                if (has(inv.getItem(EDIT[i]))) continue;
                inv.setItem(EDIT[i], cur.clone());
                h.loaded[i] = false;
                e.setCurrentItem(null);
                return;
            }
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
            return;
        }
        e.setCancelled(true);
        int ei = editIndex(raw);
        if (ei >= 0) {
            ItemStack slotItem = inv.getItem(raw), cursor = p.getItemOnCursor();
            boolean real = has(slotItem) && !h.loaded[ei];
            if (e.isShiftClick()) {                        // take out
                if (real) Util.give(p, slotItem);
                inv.setItem(raw, null);
                h.loaded[ei] = false;
            } else if (has(cursor)) {                      // place (swaps with a real item already there)
                inv.setItem(raw, cursor.clone());
                p.setItemOnCursor(real ? slotItem : null);
                h.loaded[ei] = false;
            } else if (has(slotItem)) {                    // take out
                if (real) p.setItemOnCursor(slotItem);
                inv.setItem(raw, null);
                h.loaded[ei] = false;
            }
            return;
        }
        Integer d = MIN_BTN.get(raw);
        if (d != null) {
            h.minutes = Math.max(1, Math.min(999, h.minutes + d));
            decorate(h);
            return;
        }
        switch (raw) {
            case SAVE -> save(p, h);
            case BACK -> pl.sched.entity(p, () -> openList(p, 0));
            case DELETE -> {
                if (!h.existing) return;
                if (!h.confirmDelete) { h.confirmDelete = true; decorate(h); return; }
                am().deleteRecipe(h.id);
                p.sendMessage(am().m("deleted", Text.ph("id", h.id)));
                pl.sched.entity(p, () -> openList(p, 0));
            }
            default -> { }
        }
    }

    private void save(Player p, EditHolder h) {
        Inventory inv = h.inv;
        List<Ingredient> ins = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (h.loaded[i] && h.ing[i] != null) { ins.add(h.ing[i]); continue; }
            ItemStack s = inv.getItem(GRID[i]);
            if (has(s)) ins.add(am().fromStack(s));
        }
        ins.addAll(h.extra);
        ItemStack out = h.loaded[9] ? h.output : inv.getItem(OUT);
        if (!has(out)) { p.sendMessage(am().m("need-output", Map.of())); return; }
        if (ins.isEmpty()) { p.sendMessage(am().m("need-ingredient", Map.of())); return; }
        am().saveRecipe(h.id, ins, out, h.minutes);
        p.sendMessage(am().m("saved", Text.ph("id", h.id)));
        pl.sched.entity(p, () -> openList(p, 0));   // closing the editor hands the placed items back
    }

    private void listClick(Player p, ListHolder h, InventoryClickEvent e) {
        int slot = e.getSlot();
        if (slot == PREV && h.page > 0) { pl.sched.entity(p, () -> openList(p, h.page - 1)); return; }
        if (slot == NEXT) { pl.sched.entity(p, () -> openList(p, h.page + 1)); return; }
        if (slot == NEW) { newRecipe(p); return; }
        String id = h.ids.get(slot);
        if (id != null) pl.sched.entity(p, () -> openEdit(p, id));
    }

    // ---------------- events ----------------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof ListHolder h) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p && p.hasPermission("eventcore.altar") && e.getClickedInventory() == h.inv) listClick(p, h, e);
        } else if (holder instanceof EditHolder h) {
            if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("eventcore.altar")) { e.setCancelled(true); return; }
            editClick(p, h, e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof EditHolder)
            for (int s : e.getRawSlots()) if (s < 54) { e.setCancelled(true); return; }
    }

    /** Whatever the admin placed in the editor goes back to them. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EditHolder h) || !(e.getPlayer() instanceof Player p)) return;
        for (int i = 0; i < EDIT.length; i++) {
            ItemStack s = h.inv.getItem(EDIT[i]);
            if (has(s) && !h.loaded[i]) Util.give(p, s);
            h.inv.setItem(EDIT[i], null);
        }
    }
}
