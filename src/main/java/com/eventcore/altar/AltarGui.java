package com.eventcore.altar;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager.Altar;
import com.eventcore.altar.AltarManager.Recipe;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Three GUIs: the craft GUI players use at an altar (3x3 grid + 1 output), the recipe list and the recipe editor
 * (same layout; the admin puts the ingredients and the output in and presses save).
 */
public final class AltarGui implements Listener {
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int OUT = 24, ARROW = 23, SIZE = 45;
    static final int E_MINUTES = 37, E_SAVE = 39, E_DELETE = 41, E_BACK = 43;
    static final int L_NEW = 49;

    static final class CraftHolder implements InventoryHolder {
        Inventory inv; Altar altar;
        @Override public Inventory getInventory() { return inv; }
    }
    static final class ListHolder implements InventoryHolder {
        Inventory inv; final List<String> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }
    static final class EditHolder implements InventoryHolder {
        Inventory inv; String id; int minutes; ItemStack[] snapshot = new ItemStack[10];
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;
    private final AltarManager mgr;

    public AltarGui(EventCorePlugin pl, AltarManager mgr) { this.pl = pl; this.mgr = mgr; }

    // ---------------- helpers ----------------
    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, Map.of()));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(pl.text.item(s, Map.of()));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        it.setItemMeta(meta);
        return it;
    }

    private static boolean isGrid(int slot) { for (int g : GRID) if (g == slot) return true; return false; }
    private static boolean air(ItemStack it) { return it == null || it.getType().isAir(); }

    private ItemStack[] readGrid(Inventory inv) {
        ItemStack[] in = new ItemStack[9];
        for (int i = 0; i < 9; i++) { ItemStack it = inv.getItem(GRID[i]); in[i] = air(it) ? null : it; }
        return in;
    }

    private Inventory base(InventoryHolder h, String titleKey) {
        Inventory inv = Bukkit.createInventory(h, SIZE, pl.text.render(mgr.cfg().getString("gui." + titleKey, "altar"), Map.of()));
        ItemStack f = filler();
        for (int i = 0; i < SIZE; i++) inv.setItem(i, f.clone());
        for (int g : GRID) inv.setItem(g, null);
        return inv;
    }

    // ---------------- craft GUI (players) ----------------
    public void openCraft(Player p, Altar a) {
        CraftHolder h = new CraftHolder();
        h.altar = a;
        h.inv = base(h, "craft-title");
        h.inv.setItem(ARROW, named(Material.ARROW, "<g>output</g>"));
        h.inv.setItem(OUT, placeholder());
        p.openInventory(h.inv);
    }

    private ItemStack placeholder() { return named(Material.BARRIER, "<red>no matching recipe</red>", "<gray>put the ingredients in the grid</gray>"); }

    private void refreshCraft(CraftHolder h) {
        Recipe r = mgr.find(h.altar, readGrid(h.inv));
        if (r == null) { h.inv.setItem(OUT, placeholder()); return; }
        ItemStack out = r.output.clone();
        ItemMeta meta = out.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(pl.text.item("<g>click to begin the ritual ({minutes} minutes)</g>", Text.ph("minutes", r.minutes)));
        meta.lore(lore);
        out.setItemMeta(meta);
        h.inv.setItem(OUT, out);
    }

    private void craft(Player p, CraftHolder h) {
        Altar a = h.altar;
        if (a.busy()) { p.sendMessage(mgr.m("busy", Map.of())); return; }
        ItemStack[] in = readGrid(h.inv);
        Recipe r = mgr.find(a, in);
        if (r == null) { p.sendMessage(mgr.m("no-recipe", Map.of())); return; }
        // take the ingredients (the extra items of a stack stay in the grid and are handed back on close)
        for (int i = 0; i < 9; i++) {
            ItemStack need = r.grid[i];
            if (need == null) continue;
            ItemStack have = h.inv.getItem(GRID[i]);
            int left = have.getAmount() - need.getAmount();
            if (left <= 0) h.inv.setItem(GRID[i], null);
            else { have.setAmount(left); h.inv.setItem(GRID[i], have); }
        }
        if (!mgr.startRitual(a, r, p)) { p.sendMessage(mgr.m("busy", Map.of())); return; }
        pl.sched.entity(p, p::closeInventory);
    }

    // ---------------- recipe list (admins) ----------------
    public void openList(Player p) {
        ListHolder h = new ListHolder();
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(mgr.cfg().getString("gui.list-title", "altar recipes"), Map.of()));
        ItemStack f = filler();
        for (int i = 45; i < 54; i++) h.inv.setItem(i, f.clone());
        int slot = 0;
        for (Recipe r : mgr.recipes()) {
            if (slot >= 45) break;
            ItemStack it = r.output.clone();
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(pl.text.item("<gray>id: {id}</gray>", Text.ph("id", r.id)));
            lore.add(pl.text.item("<gray>ritual: {minutes} minutes</gray>", Text.ph("minutes", r.minutes)));
            lore.add(Component.empty());
            lore.add(pl.text.item("<g>left-click: edit</g>", Map.of()));
            lore.add(pl.text.item("<red>shift + right-click: delete</red>", Map.of()));
            meta.lore(lore);
            it.setItemMeta(meta);
            h.inv.setItem(slot, it);
            h.ids.add(r.id);
            slot++;
        }
        h.inv.setItem(L_NEW, named(Material.LIME_DYE, "<g>new recipe</g>", "<gray>add a recipe with the 3x3 grid and an output</gray>"));
        p.openInventory(h.inv);
    }

    // ---------------- recipe editor (admins) ----------------
    public void openEdit(Player p, Recipe existing) {
        EditHolder h = new EditHolder();
        h.id = existing == null ? null : existing.id;
        h.minutes = existing == null ? mgr.defaultMinutes() : existing.minutes;
        h.inv = base(h, "edit-title");
        h.inv.setItem(ARROW, named(Material.ARROW, "<g>output goes in the slot on the right</g>"));
        h.inv.setItem(OUT, null);
        if (existing != null) {
            for (int i = 0; i < 9; i++) {
                if (existing.grid[i] == null) continue;
                h.inv.setItem(GRID[i], existing.grid[i].clone());
                h.snapshot[i] = existing.grid[i].clone();
            }
            h.inv.setItem(OUT, existing.output.clone());
            h.snapshot[9] = existing.output.clone();
        }
        h.inv.setItem(E_SAVE, named(Material.EMERALD, "<g>save recipe</g>"));
        if (existing != null) h.inv.setItem(E_DELETE, named(Material.REDSTONE, "<red>delete recipe</red>"));
        h.inv.setItem(E_BACK, named(Material.SPECTRAL_ARROW, "<g>back to the list</g>", "<gray>items in the slots are returned to you</gray>"));
        h.inv.setItem(E_MINUTES, minutesItem(h));
        p.openInventory(h.inv);
    }

    private ItemStack minutesItem(EditHolder h) {
        return named(Material.CLOCK, "<g>ritual time: {m} minutes</g>".replace("{m}", String.valueOf(h.minutes)),
                "<gray>left-click +1, right-click -1</gray>", "<gray>shift: 5 minutes</gray>");
    }

    private void save(Player p, EditHolder h) {
        ItemStack out = h.inv.getItem(OUT);
        if (air(out)) { p.sendMessage(mgr.m("need-output", Map.of())); return; }
        ItemStack[] grid = readGrid(h.inv);
        boolean any = false;
        for (ItemStack g : grid) if (g != null) any = true;
        if (!any) { p.sendMessage(mgr.m("need-ingredient", Map.of())); return; }
        Recipe r = mgr.putRecipe(h.id, grid, out, h.minutes);
        p.sendMessage(mgr.m("saved", Text.ph("id", r.id)));
        pl.sched.entity(p, () -> { p.closeInventory(); openList(p); });
    }

    // ---------------- events ----------------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        boolean inTop = e.getRawSlot() < top.getSize();

        if (holder instanceof CraftHolder h) {
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
            if (inTop) {
                int s = e.getRawSlot();
                if (s == OUT) { e.setCancelled(true); craft(p, h); return; }
                if (!isGrid(s)) { e.setCancelled(true); return; }
            }
            pl.sched.entityLater(p, () -> refreshCraft(h), 1);
        } else if (holder instanceof EditHolder h) {
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
            if (inTop) {
                int s = e.getRawSlot();
                if (s == OUT || isGrid(s)) return;
                e.setCancelled(true);
                if (!p.hasPermission("eventcore.altar")) return;
                switch (s) {
                    case E_SAVE -> save(p, h);
                    case E_BACK -> pl.sched.entity(p, () -> { p.closeInventory(); openList(p); });
                    case E_DELETE -> {
                        if (h.id == null) return;
                        mgr.deleteRecipe(h.id);
                        p.sendMessage(mgr.m("deleted", Text.ph("id", h.id)));
                        pl.sched.entity(p, () -> { p.closeInventory(); openList(p); });
                    }
                    case E_MINUTES -> {
                        int step = e.isShiftClick() ? 5 : 1;
                        int d = e.isLeftClick() ? step : e.isRightClick() ? -step : 0;
                        if (d != 0) {
                            h.minutes = Math.max(1, Math.min(600, h.minutes + d));
                            top.setItem(E_MINUTES, minutesItem(h));
                        }
                    }
                    default -> { }
                }
            }
        } else if (holder instanceof ListHolder h) {
            e.setCancelled(true);
            if (!inTop || !p.hasPermission("eventcore.altar")) return;
            int s = e.getRawSlot();
            if (s == L_NEW) { pl.sched.entity(p, () -> openEdit(p, null)); return; }
            if (s < 0 || s >= h.ids.size()) return;
            Recipe r = mgr.recipe(h.ids.get(s));
            if (r == null) return;
            if (e.isShiftClick() && e.isRightClick()) {
                mgr.deleteRecipe(r.id);
                p.sendMessage(mgr.m("deleted", Text.ph("id", r.id)));
                pl.sched.entity(p, () -> openList(p));
            } else if (e.isLeftClick()) {
                pl.sched.entity(p, () -> openEdit(p, r));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof ListHolder) { e.setCancelled(true); return; }
        boolean craft = holder instanceof CraftHolder, edit = holder instanceof EditHolder;
        if (!craft && !edit) return;
        for (int s : e.getRawSlots()) {
            if (s >= top.getSize()) continue;
            if (isGrid(s) || (edit && s == OUT)) continue;
            e.setCancelled(true);
            return;
        }
        if (holder instanceof CraftHolder h && e.getWhoClicked() instanceof Player p) pl.sched.entityLater(p, () -> refreshCraft(h), 1);
    }

    /** Never lose items: whatever sits in the grid (and the editor's output slot) goes back to the player. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof CraftHolder h) {
            for (int g : GRID) {
                ItemStack it = h.inv.getItem(g);
                if (!air(it)) { h.inv.setItem(g, null); Util.give(p, it); }
            }
        } else if (holder instanceof EditHolder h) {
            for (int i = 0; i < 10; i++) {
                int slot = i < 9 ? GRID[i] : OUT;
                ItemStack it = h.inv.getItem(slot);
                if (air(it)) continue;
                h.inv.setItem(slot, null);
                ItemStack orig = h.snapshot[i];
                // items that were loaded from the saved recipe and are untouched are copies: do not hand them out again
                if (orig != null && orig.isSimilar(it) && orig.getAmount() == it.getAmount()) continue;
                Util.give(p, it);
            }
        }
    }
}
