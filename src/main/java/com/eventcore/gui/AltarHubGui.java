package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager;
import com.eventcore.altar.AltarManager.Altar;
import com.eventcore.altar.AltarManager.Ingredient;
import com.eventcore.altar.AltarManager.Recipe;
import com.eventcore.util.Mouse;
import com.eventcore.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.Map;

/**
 * Altar menu: /altar opens it. Everything the altar commands do, with clicks:
 * place an altar where you stand (for one recipe or for every recipe), see / teleport to / stop / remove the placed altars,
 * and open the recipe editor (AltarGui).
 */
public final class AltarHubGui implements Listener {
    private enum Mode { HUB, PLACE, MANAGE }

    private static final int PAGE = 45, PREV = 45, BACK = 48, ANY = 49, NEXT = 53;
    private static final int H_PLACE = 10, H_MANAGE = 12, H_RECIPES = 14, H_STOP = 16, H_CLOSE = 22;
    private static final String NAME = "<gradient:#9babc7:#E7E7E7:#E7E7E7>";
    private static final String NAME_END = "</gradient>";
    private static final String GRAY = "<color:#a8a8a8>", DIM = "<color:#7a7a7a>", END = "</color>";

    static final class Holder implements InventoryHolder {
        Inventory inv; Mode mode; int page;
        final Map<Integer, String> ids = new HashMap<>();     // slot -> recipe id (PLACE) or altar id (MANAGE)
        int confirmRemove = -1;                               // altar id waiting for the second click
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public AltarHubGui(EventCorePlugin pl) { this.pl = pl; }

    private AltarManager am() { return pl.altar; }

    private ItemStack button(Material m, String name, List<String> lore, Map<String, Object> ph) {
        ItemStack it = new ItemStack(m);
        decorate(it, name, lore, ph);
        return it;
    }

    private void decorate(ItemStack it, String name, List<String> lore, Map<String, Object> ph) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, ph));
        List<Component> l = new ArrayList<>();
        if (lore != null) for (String line : lore) l.add(pl.text.item(line, ph));
        meta.lore(l.isEmpty() ? null : l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
    }

    private ItemStack pane() { return button(Material.GRAY_STAINED_GLASS_PANE, " ", null, Map.of()); }

    private String title(String key, String def) { return am().cfg().getString("gui." + key, def); }

    private int running() {
        int n = 0;
        for (Altar a : am().altars()) if (a.busy()) n++;
        return n;
    }

    // ---------------------------------------------------------------- hub
    /** Call on the player's own thread. */
    public void open(Player p) {
        Holder h = new Holder();
        h.mode = Mode.HUB;
        h.inv = Bukkit.createInventory(h, 27, pl.text.render(title("hub-title", "<g>altar menu</g>"), Map.of()));
        for (int i = 0; i < 27; i++) h.inv.setItem(i, pane());
        Map<String, Object> ph = Text.ph("altars", am().altars().size(), "recipes", am().recipes().size(), "running", running());
        h.inv.setItem(H_PLACE, button(Material.ENCHANTING_TABLE, NAME + "place an altar" + NAME_END, List.of(
                GRAY + "builds an altar where you stand" + END,
                GRAY + "pick one recipe or every recipe" + END,
                "", Mouse.left(pl, "choose a recipe")), ph));
        h.inv.setItem(H_MANAGE, button(Material.LECTERN, NAME + "placed altars" + NAME_END, List.of(
                GRAY + "Altars: {altars}" + END,
                GRAY + "Rituals running: {running}" + END,
                "", Mouse.left(pl, "see, teleport, stop or remove")), ph));
        h.inv.setItem(H_RECIPES, button(Material.BOOK, NAME + "recipes" + NAME_END, List.of(
                GRAY + "Recipes: {recipes}" + END,
                GRAY + "create, edit and delete recipes" + END,
                "", Mouse.left(pl, "open the recipe editor")), ph));
        h.inv.setItem(H_STOP, button(Material.REDSTONE_BLOCK, NAME + "stop all rituals" + NAME_END, List.of(
                GRAY + "Rituals running: {running}" + END,
                "", Mouse.left(pl, "stop every running ritual")), ph));
        h.inv.setItem(H_CLOSE, button(Material.BARRIER, NAME + "close" + NAME_END, List.of("", Mouse.left(pl, "close")), Map.of()));
        p.openInventory(h.inv);
    }

    // ---------------------------------------------------------------- place: choose a recipe
    private void openPlace(Player p, int page) {
        Holder h = new Holder();
        h.mode = Mode.PLACE;
        List<Recipe> all = new ArrayList<>();
        for (Recipe r : am().recipes()) if (r.output != null) all.add(r);
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        h.page = Math.max(0, Math.min(page, pages - 1));
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(title("place-title", "<g>place an altar</g>"), Map.of()));
        for (int i = 0; i < PAGE; i++) {
            int idx = h.page * PAGE + i;
            if (idx >= all.size()) break;
            Recipe r = all.get(idx);
            ItemStack it = r.output.clone();
            it.setAmount(1);
            List<String> lore = new ArrayList<>();
            lore.add(GRAY + "altar crafts only this recipe" + END);
            for (Ingredient in : r.inputs) lore.add(DIM + in.line() + END);
            lore.add("");
            lore.add(Mouse.left(pl, "place it where you stand"));
            decorate(it, NAME + AltarManager.plainName(r.output) + NAME_END, lore, Map.of());
            h.inv.setItem(i, it);
            h.ids.put(i, r.id);
        }
        for (int i = PAGE; i < 54; i++) h.inv.setItem(i, pane());
        if (h.page > 0) h.inv.setItem(PREV, button(Material.PAPER, NAME + "previous page" + NAME_END, List.of("", Mouse.left(pl, "go back")), Map.of()));
        if (h.page < pages - 1) h.inv.setItem(NEXT, button(Material.PAPER, NAME + "next page" + NAME_END, List.of("", Mouse.left(pl, "continue")), Map.of()));
        h.inv.setItem(BACK, button(Material.OAK_DOOR, NAME + "back" + NAME_END, List.of("", Mouse.left(pl, "go back")), Map.of()));
        h.inv.setItem(ANY, button(Material.NETHER_STAR, NAME + "altar for every recipe" + NAME_END, List.of(
                GRAY + "players choose the recipe at the altar" + END,
                "", Mouse.left(pl, "place it where you stand")), Map.of()));
        p.openInventory(h.inv);
    }

    private void place(Player p, String recipeId) {
        p.closeInventory();
        Altar al = am().spawn(p, recipeId == null ? "" : recipeId);
        if (al == null) p.sendMessage(am().m("unknown-spawn", Text.ph("input", recipeId == null ? "default" : recipeId)));
        else p.sendMessage(am().m("spawned-recipe", Text.ph("id", al.id, "preset", al.preset, "item", am().outputNames(al))));
    }

    // ---------------------------------------------------------------- manage: placed altars
    private List<Altar> sorted() {
        List<Altar> l = new ArrayList<>(am().altars());
        l.sort((a, b) -> Integer.compare(a.id, b.id));
        return l;
    }

    private void openManage(Player p, int page, int confirm) {
        Holder h = new Holder();
        h.mode = Mode.MANAGE;
        h.confirmRemove = confirm;
        List<Altar> all = sorted();
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        h.page = Math.max(0, Math.min(page, pages - 1));
        h.inv = Bukkit.createInventory(h, 54, pl.text.render(title("manage-title", "<g>placed altars</g>"), Map.of()));
        for (int i = 0; i < PAGE; i++) {
            int idx = h.page * PAGE + i;
            if (idx >= all.size()) break;
            Altar a = all.get(idx);
            Recipe bound = a.recipeId.isBlank() ? null : am().recipe(a.recipeId);
            ItemStack it = bound != null && bound.output != null ? bound.output.clone() : new ItemStack(Material.ENCHANTING_TABLE);
            it.setAmount(1);
            Location c = a.center;
            boolean sure = confirm == a.id;
            List<String> lore = new ArrayList<>();
            lore.add(GRAY + "Recipe: " + (bound == null ? "every recipe" : a.recipeId) + END);
            lore.add(GRAY + "Place: {world} {x} {y} {z}" + END);
            lore.add(GRAY + "State: " + (a.busy() ? "ritual running" : "idle") + END);
            lore.add("");
            lore.add(Mouse.left(pl, "teleport to it"));
            lore.add(Mouse.drop(pl, "stop its ritual"));
            lore.add(sure ? Mouse.shiftRight(pl, "remove it (click again to confirm)") : Mouse.shiftRight(pl, "remove it"));
            decorate(it, NAME + "altar {id}" + NAME_END, lore, Text.ph("id", a.id, "world", c.getWorld().getName(), "x", c.getBlockX(), "y", c.getBlockY(), "z", c.getBlockZ()));
            h.inv.setItem(i, it);
            h.ids.put(i, String.valueOf(a.id));
        }
        for (int i = PAGE; i < 54; i++) h.inv.setItem(i, pane());
        if (h.page > 0) h.inv.setItem(PREV, button(Material.PAPER, NAME + "previous page" + NAME_END, List.of("", Mouse.left(pl, "go back")), Map.of()));
        if (h.page < pages - 1) h.inv.setItem(NEXT, button(Material.PAPER, NAME + "next page" + NAME_END, List.of("", Mouse.left(pl, "continue")), Map.of()));
        h.inv.setItem(BACK, button(Material.OAK_DOOR, NAME + "back" + NAME_END, List.of("", Mouse.left(pl, "go back")), Map.of()));
        if (all.isEmpty()) h.inv.setItem(22, button(Material.BARRIER, NAME + "no altars placed" + NAME_END, List.of(GRAY + "use \"place an altar\" first" + END), Map.of()));
        p.openInventory(h.inv);
    }

    // ---------------------------------------------------------------- clicks
    private void hubClick(Player p, int slot) {
        switch (slot) {
            case H_PLACE -> pl.sched.entity(p, () -> openPlace(p, 0));
            case H_MANAGE -> pl.sched.entity(p, () -> openManage(p, 0, -1));
            case H_RECIPES -> pl.sched.entity(p, () -> pl.altarGui.openList(p, 0));
            case H_STOP -> {
                int n = am().stopAll();
                p.sendMessage(am().m("stopped", Text.ph("count", n)));
                pl.sched.entity(p, () -> open(p));
            }
            case H_CLOSE -> p.closeInventory();
            default -> { }
        }
    }

    private void placeClick(Player p, Holder h, int slot) {
        if (slot == PREV && h.page > 0) { pl.sched.entity(p, () -> openPlace(p, h.page - 1)); return; }
        if (slot == NEXT) { pl.sched.entity(p, () -> openPlace(p, h.page + 1)); return; }
        if (slot == BACK) { pl.sched.entity(p, () -> open(p)); return; }
        if (slot == ANY) { place(p, null); return; }
        String id = h.ids.get(slot);
        if (id != null) place(p, id);
    }

    private void manageClick(Player p, Holder h, int slot, ClickType click) {
        if (slot == PREV && h.page > 0) { pl.sched.entity(p, () -> openManage(p, h.page - 1, -1)); return; }
        if (slot == NEXT) { pl.sched.entity(p, () -> openManage(p, h.page + 1, -1)); return; }
        if (slot == BACK) { pl.sched.entity(p, () -> open(p)); return; }
        String raw = h.ids.get(slot);
        if (raw == null) return;
        Altar a = am().altar(Integer.parseInt(raw));
        if (a == null) { pl.sched.entity(p, () -> openManage(p, h.page, -1)); return; }
        switch (click) {
            case LEFT, SHIFT_LEFT -> {
                Location to = a.center.clone().add(0.5, 1.0, 0.5);
                p.closeInventory();
                p.teleportAsync(to);
            }
            case DROP, CONTROL_DROP -> {
                am().stopRitual(a, true);
                p.sendMessage(am().m("stopped", Text.ph("count", 1)));
                pl.sched.entity(p, () -> openManage(p, h.page, -1));
            }
            case SHIFT_RIGHT -> {
                if (h.confirmRemove != a.id) { pl.sched.entity(p, () -> openManage(p, h.page, a.id)); return; }
                int id = a.id;
                am().remove(a);
                p.sendMessage(am().m("removed", Text.ph("id", id)));
                pl.sched.entity(p, () -> openManage(p, h.page, -1));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("eventcore.altar") || e.getClickedInventory() != h.inv) return;
        switch (h.mode) {
            case HUB -> hubClick(p, e.getSlot());
            case PLACE -> placeClick(p, h, e.getSlot());
            case MANAGE -> manageClick(p, h, e.getSlot(), e.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
