package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager;
import com.eventcore.altar.AltarManager.Ingredient;
import com.eventcore.altar.AltarManager.Recipe;
import com.eventcore.util.Text;
import net.kyori.adventure.text.Component;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Altar recipe GUI: /altar recipes opens the list, "new recipe" asks for an id in chat, the editor has a 3x3
 * ingredient grid, the output slot, minutes, save and delete. Every click is cancelled and the editor only works on
 * copies, so nobody can ever lose or duplicate a real item.
 */
public final class AltarGui implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int OUT = 24, ARROW = 22, INFO = 4, MINUTES = 40, SAVE = 48, BACK = 45, DELETE = 53;
    private static final int PAGE = 45, PREV = 45, NEW = 49, NEXT = 53;

    static final class ListHolder implements InventoryHolder {
        Inventory inv; int page; final Map<Integer, String> ids = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    static final class EditHolder implements InventoryHolder {
        Inventory inv; String id; boolean existing; int minutes;
        final Ingredient[] ing = new Ingredient[9];
        final List<Ingredient> extra = new ArrayList<>();   // inputs beyond 9 (yaml only) are kept untouched
        ItemStack output;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public AltarGui(EventCorePlugin pl) { this.pl = pl; }

    private AltarManager am() { return pl.altar; }

    private Component lab(String mm) { return pl.text.item(mm, Map.of()); }

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
        it.setAmount(Math.max(1, Math.min(64, r.output.getAmount())));
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Component.empty());
        lore.add(lab("<color:#d8b04a>ingredients</color>"));
        for (Ingredient in : r.inputs) lore.add(pl.text.item("<color:#fffbf0>- {line}</color>", Text.ph("line", in.line())));
        lore.add(pl.text.item("<color:#ffd7ca>{minutes} minutes - id {id}</color>", Text.ph("minutes", r.minutes, "id", r.id)));
        lore.add(Component.empty());
        lore.add(lab("<color:#f1d58f>left click: edit</color>"));
        lore.add(lab("<color:#ffcfca>shift + right click: delete</color>"));
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
        if (r != null) {
            for (int i = 0; i < r.inputs.size(); i++) {
                if (i < 9) h.ing[i] = r.inputs.get(i); else h.extra.add(r.inputs.get(i));
            }
            h.output = r.output == null ? null : r.output.clone();
        }
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(am().cfg().getString("gui.edit-title", "<g>edit altar recipe</g>"), Map.of()));
        render(h);
        p.openInventory(h.inv);
    }

    private void render(EditHolder h) {
        Inventory inv = h.inv;
        for (int i = 0; i < 54; i++) inv.setItem(i, pane());
        for (int i = 0; i < 9; i++) inv.setItem(GRID[i], h.ing[i] == null ? null : am().stackOf(h.ing[i]));
        if (h.output == null) inv.setItem(OUT, null);
        else {
            ItemStack o = h.output.clone();
            o.setAmount(Math.max(1, Math.min(64, o.getAmount())));
            inv.setItem(OUT, o);
        }
        inv.setItem(ARROW, button(Material.ARROW, "<color:#e8c670>➜ ritual</color>", null, Map.of()));
        inv.setItem(INFO, button(Material.BOOK, "<color:#d8b04a>recipe {id}</color>", List.of(
                "<color:#fffbf0>put an item on the cursor and click a</color>",
                "<color:#fffbf0>grid slot: it becomes an ingredient (a copy)</color>",
                "<color:#fffbf0>click an item in your inventory: next free slot</color>",
                "<color:#fffbf0>shift-click an inventory item: the output</color>",
                "<color:#fffbf0>empty cursor on a slot: left +1, right -1,</color>",
                "<color:#fffbf0>shift-click clears it. max 64 per slot</color>",
                "<color:#ffd7ca>nothing is taken from you</color>"), Text.ph("id", h.id)));
        inv.setItem(MINUTES, button(Material.CLOCK, "<color:#e8c670>◷ ritual: <color:#fff0cc>{minutes} minutes</color></color>", List.of(
                "<color:#fffbf0>left +1  right -1</color>",
                "<color:#fffbf0>shift: +10 / -10</color>"), Text.ph("minutes", h.minutes)));
        inv.setItem(SAVE, button(Material.LIME_CONCRETE, "<color:#bfe8a8>✔ save recipe</color>", null, Map.of()));
        inv.setItem(BACK, button(Material.OAK_DOOR, "<color:#e8c670>◀ back (discard changes)</color>", null, Map.of()));
        if (h.existing) inv.setItem(DELETE, button(Material.BARRIER, "<color:#ffcfca>✖ delete recipe</color>",
                List.of("<color:#fffbf0>shift-click to delete</color>"), Map.of()));
    }

    private static int gridIndex(int slot) {
        for (int i = 0; i < GRID.length; i++) if (GRID[i] == slot) return i;
        return -1;
    }

    private void editClick(Player p, EditHolder h, InventoryClickEvent e) {
        int raw = e.getRawSlot();
        ClickType ct = e.getClick();
        if (raw >= 54) {   // the player's own inventory
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType().isAir()) return;
            if (ct.isShiftClick()) h.output = cur.clone();
            else {
                int free = -1;
                for (int i = 0; i < 9 && free < 0; i++) if (h.ing[i] == null) free = i;
                if (free < 0) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f); return; }
                h.ing[free] = am().fromStack(cur);
            }
            render(h);
            return;
        }
        ItemStack cursor = e.getCursor();
        boolean hasCursor = cursor != null && !cursor.getType().isAir();
        int gi = gridIndex(raw);
        if (gi >= 0) {
            if (hasCursor) h.ing[gi] = am().fromStack(cursor);
            else if (h.ing[gi] != null) {
                Ingredient in = h.ing[gi];
                if (ct.isShiftClick()) h.ing[gi] = null;
                else if (ct.isLeftClick()) h.ing[gi] = am().withAmount(in, Math.min(64, in.amount + 1));
                else if (ct.isRightClick()) h.ing[gi] = in.amount <= 1 ? null : am().withAmount(in, in.amount - 1);
            }
            render(h);
            return;
        }
        switch (raw) {
            case OUT -> {
                if (hasCursor) h.output = cursor.clone();
                else if (h.output != null) {
                    if (ct.isShiftClick()) h.output = null;
                    else if (ct.isLeftClick()) h.output.setAmount(Math.min(64, h.output.getAmount() + 1));
                    else if (ct.isRightClick()) h.output.setAmount(Math.max(1, h.output.getAmount() - 1));
                }
                render(h);
            }
            case MINUTES -> {
                int d = switch (ct) {
                    case LEFT -> 1; case RIGHT -> -1; case SHIFT_LEFT -> 10; case SHIFT_RIGHT -> -10; default -> 0;
                };
                if (d != 0) { h.minutes = Math.max(1, Math.min(999, h.minutes + d)); render(h); }
            }
            case SAVE -> {
                if (h.output == null || h.output.getType().isAir()) { p.sendMessage(am().m("need-output", Map.of())); return; }
                List<Ingredient> ins = new ArrayList<>();
                for (Ingredient in : h.ing) if (in != null) ins.add(in);
                ins.addAll(h.extra);
                if (ins.isEmpty()) { p.sendMessage(am().m("need-ingredient", Map.of())); return; }
                am().saveRecipe(h.id, ins, h.output, h.minutes);
                p.sendMessage(am().m("saved", Text.ph("id", h.id)));
                pl.sched.entity(p, () -> openList(p, 0));
            }
            case BACK -> pl.sched.entity(p, () -> openList(p, 0));
            case DELETE -> {
                if (!h.existing || !ct.isShiftClick()) return;
                am().deleteRecipe(h.id);
                p.sendMessage(am().m("deleted", Text.ph("id", h.id)));
                pl.sched.entity(p, () -> openList(p, 0));
            }
            default -> { }
        }
    }

    private void listClick(Player p, ListHolder h, InventoryClickEvent e) {
        int slot = e.getSlot();
        ClickType ct = e.getClick();
        if (slot == PREV && h.page > 0) { pl.sched.entity(p, () -> openList(p, h.page - 1)); return; }
        if (slot == NEXT) { pl.sched.entity(p, () -> openList(p, h.page + 1)); return; }
        if (slot == NEW) { newRecipe(p); return; }
        String id = h.ids.get(slot);
        if (id == null) return;
        if (ct == ClickType.SHIFT_RIGHT) {
            am().deleteRecipe(id);
            p.sendMessage(am().m("deleted", Text.ph("id", id)));
            pl.sched.entity(p, () -> openList(p, h.page));
        } else if (ct == ClickType.LEFT || ct == ClickType.SHIFT_LEFT) {
            pl.sched.entity(p, () -> openEdit(p, id));
        }
    }

    // ---------------- events ----------------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof ListHolder) && !(holder instanceof EditHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("eventcore.altar")) return;
        if (holder instanceof ListHolder h) {
            if (e.getClickedInventory() == h.inv) listClick(p, h, e);
        } else if (holder instanceof EditHolder h) editClick(p, h, e);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof EditHolder) e.setCancelled(true);
    }
}
