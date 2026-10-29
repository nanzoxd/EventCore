package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.airdrop.LootService;
import com.eventcore.util.Util;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Loot editor. Tier menu (shulker boxes in tier colours), then the tier's loot as a locked chest of real items:
 *   left click  = chance +5      right click  = chance -5
 *   shift-left  = amount +1      shift-right  = amount -1
 *   Q           = remove entry
 *   add         = click an empty slot with an item on your cursor, or shift-click an item in your inventory
 * Bottom row: back, save, add command reward. Closing the editor saves too.
 * Nothing can be taken out of the editor, so no marked item ever ends up in a player's inventory.
 */
public final class LootGui implements Listener {
    private static final int LOOT_SLOTS = 45;
    private static final double CHANCE_STEP = 5.0;
    private final EventCorePlugin pl;
    private final NamespacedKey kId, kChance, kMin, kMax, kLore, kCmd, kCmds;

    static final class TierHolder implements InventoryHolder {
        Inventory inv; final List<String> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }
    static final class EditHolder implements InventoryHolder {
        Inventory inv; String tier; boolean skipSave;
        final List<String> hidden = new ArrayList<>();   // entries beyond the visible slots stay untouched
        @Override public Inventory getInventory() { return inv; }
    }

    /** Everything about one entry, stored in the item's PDC while it sits in the editor. */
    private record Data(String id, double chance, int min, int max, boolean cmdOnly, List<String> cmds) { }

    public LootGui(EventCorePlugin pl) {
        this.pl = pl;
        kId = new NamespacedKey(pl, "loot_id"); kChance = new NamespacedKey(pl, "loot_chance");
        kMin = new NamespacedKey(pl, "loot_min"); kMax = new NamespacedKey(pl, "loot_max");
        kLore = new NamespacedKey(pl, "loot_lore"); kCmd = new NamespacedKey(pl, "loot_cmd");
        kCmds = new NamespacedKey(pl, "loot_cmds");
    }

    // peach + gold palette (config.yml theme)
    private static final TextColor WHITE = TextColor.color(0xfffbf0), SOFT = TextColor.color(0xffd7ca), GOLD = TextColor.color(0xf1d58f),
            DARK = TextColor.color(0xe8c670), MUTED = TextColor.color(0xffe4d5);

    private Component plain(String s, TextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    private Component title(String s) {
        String a = pl.cfg.config().getString("theme.main", "#d8b04a"), b = pl.cfg.config().getString("theme.soft", "#ffd7ca");
        return pl.text.gradient(s, List.of(a, b), 0);
    }

    // ---------- tier selection ----------
    public void openTiers(Player p) {
        TierHolder h = new TierHolder();
        h.inv = Bukkit.createInventory(h, 27, title("Loot tables"));
        int slot = 10;
        for (String t : pl.airdrop.tierIds()) {
            if (slot > 16) break;
            String dye = pl.airdrop.tierSec(t).getString("shulker-color", "PURPLE").toUpperCase(Locale.ROOT);
            Material m = Material.matchMaterial(dye + "_SHULKER_BOX");
            ItemStack it = new ItemStack(m == null ? Material.SHULKER_BOX : m);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(pl.airdrop.tierName(t).decoration(TextDecoration.ITALIC, false));
            ConfigurationSection es = pl.loot.entries(t);
            meta.lore(List.of(plain("Loot entries: " + (es == null ? 0 : es.getKeys(false).size()), SOFT),
                    plain("Click to edit the loot", GOLD)));
            it.setItemMeta(meta);
            h.inv.setItem(slot, it);
            h.ids.add(t);
            slot++;
        }
        h.inv.setItem(22, button(Material.ARROW, "Back"));
        p.openInventory(h.inv);
    }

    // ---------- editor ----------
    public void openEntries(Player p, String tier, int ignoredPage) {
        EditHolder h = new EditHolder();
        h.tier = tier;
        h.inv = Bukkit.createInventory(h, 54, title(Util.title(tier) + " loot"));
        ConfigurationSection es = pl.loot.entries(tier);
        int i = 0;
        if (es != null) for (String id : es.getKeys(false)) {
            ConfigurationSection e = es.getConfigurationSection(id);
            if (e == null) continue;
            if (i >= LOOT_SLOTS) { h.hidden.add(id); continue; }
            boolean cmdOnly = pl.loot.build(e) == null;
            ItemStack base = cmdOnly ? new ItemStack(Material.COMMAND_BLOCK) : pl.loot.icon(e);
            int min = Math.max(1, e.getInt("amount-min", 1)), max = Math.max(min, e.getInt("amount-max", min));
            h.inv.setItem(i++, mark(base, new Data(id, e.getDouble("chance", 100.0), min, max, cmdOnly, e.getStringList("commands"))));
        }
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.displayName(Component.text(" ")); glass.setItemMeta(gm);
        for (int s = LOOT_SLOTS; s < 54; s++) h.inv.setItem(s, glass);
        h.inv.setItem(45, button(Material.PAPER, "How it works",
                com.eventcore.util.Mouse.left(pl, "chance +" + (int) CHANCE_STEP), com.eventcore.util.Mouse.right(pl, "chance -" + (int) CHANCE_STEP),
                com.eventcore.util.Mouse.shiftLeft(pl, "amount +1"), com.eventcore.util.Mouse.shiftRight(pl, "amount -1"), com.eventcore.util.Mouse.drop(pl, "remove the entry"),
                "Add: click an empty slot with an item", "on your cursor, or shift-click one", "in your inventory"));
        h.inv.setItem(48, button(Material.ARROW, "Back", "Return to the tier list"));
        h.inv.setItem(49, button(Material.LIME_CONCRETE, "Save", "Save this loot pool"));
        h.inv.setItem(50, button(Material.COMMAND_BLOCK, "Add command reward", "Run a command when the crate opens"));
        p.openInventory(h.inv);
    }

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name, WHITE));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(s.startsWith("<color:") ? pl.text.item(s, java.util.Map.of()) : plain(s, SOFT));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    /** Item for the editor: the real item plus a short lore (chance, amount, commands) and hidden marks. */
    private ItemStack mark(ItemStack base, Data d) {
        ItemStack it = clean(base).clone();
        it.setAmount(Math.max(1, Math.min(d.min(), Math.max(1, it.getMaxStackSize()))));
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        List<Component> extra = new ArrayList<>();
        extra.add(plain("Chance: " + fmt(d.chance()) + "%", GOLD));
        if (!d.cmdOnly()) extra.add(plain("Amount: " + (d.min() == d.max() ? d.min() : d.min() + "-" + d.max()), GOLD));
        for (String c : d.cmds()) extra.add(plain("/" + (c.length() > 34 ? c.substring(0, 31) + "..." : c), DARK));
        extra.add(pl.text.item(com.eventcore.util.Mouse.left(pl, "chance +" + (int) CHANCE_STEP), java.util.Map.of()));
        extra.add(pl.text.item(com.eventcore.util.Mouse.right(pl, "chance -" + (int) CHANCE_STEP), java.util.Map.of()));
        extra.add(pl.text.item(com.eventcore.util.Mouse.shiftLeft(pl, "amount +1"), java.util.Map.of()));
        extra.add(pl.text.item(com.eventcore.util.Mouse.shiftRight(pl, "amount -1"), java.util.Map.of()));
        extra.add(pl.text.item(com.eventcore.util.Mouse.drop(pl, "remove"), java.util.Map.of()));
        lore.addAll(extra);
        meta.lore(lore);
        PersistentDataContainer p = meta.getPersistentDataContainer();
        p.set(kId, PersistentDataType.STRING, d.id());
        p.set(kChance, PersistentDataType.DOUBLE, d.chance());
        p.set(kMin, PersistentDataType.INTEGER, d.min());
        p.set(kMax, PersistentDataType.INTEGER, d.max());
        p.set(kLore, PersistentDataType.INTEGER, extra.size());
        p.set(kCmds, PersistentDataType.STRING, String.join("\n", d.cmds()));
        if (d.cmdOnly()) p.set(kCmd, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean marked(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kId, PersistentDataType.STRING);
    }

    private Data read(ItemStack it) {
        PersistentDataContainer p = it.getItemMeta().getPersistentDataContainer();
        String cmds = p.getOrDefault(kCmds, PersistentDataType.STRING, "");
        int min = p.getOrDefault(kMin, PersistentDataType.INTEGER, 1);
        return new Data(p.get(kId, PersistentDataType.STRING), p.getOrDefault(kChance, PersistentDataType.DOUBLE, 100.0),
                min, p.getOrDefault(kMax, PersistentDataType.INTEGER, min), p.has(kCmd, PersistentDataType.BYTE),
                cmds.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(cmds.split("\n"))));
    }

    /** Strips our lore lines and marks. */
    private ItemStack clean(ItemStack it) {
        if (!marked(it)) return it;
        ItemMeta meta = it.getItemMeta();
        int n = meta.getPersistentDataContainer().getOrDefault(kLore, PersistentDataType.INTEGER, 0);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (int i = 0; i < n && !lore.isEmpty(); i++) lore.remove(lore.size() - 1);
        meta.lore(lore.isEmpty() ? null : lore);
        for (NamespacedKey k : List.of(kId, kChance, kMin, kMax, kLore, kCmd, kCmds)) meta.getPersistentDataContainer().remove(k);
        ItemStack c = it.clone();
        c.setItemMeta(meta);
        return c;
    }

    private String nextId(Set<String> used, String base) {
        int n = 1;
        while (used.contains(base + "_" + n)) n++;
        return base + "_" + n;
    }

    private Set<String> usedIds(EditHolder h) {
        Set<String> used = new HashSet<>(h.hidden);
        ConfigurationSection es = pl.loot.entries(h.tier);
        if (es != null) used.addAll(es.getKeys(false));
        for (ItemStack o : h.inv.getContents()) if (marked(o)) used.add(read(o).id());
        return used;
    }

    /** Writes the chest back into loot.yml. */
    private void save(EditHolder h) {
        String path = "tiers." + h.tier + ".entries";
        ConfigurationSection old = pl.cfg.loot().getConfigurationSection(path);
        Map<String, Map<String, Object>> oldData = new LinkedHashMap<>();
        if (old != null) for (String id : old.getKeys(false)) {
            ConfigurationSection e = old.getConfigurationSection(id);
            if (e != null) oldData.put(id, e.getValues(false));
        }
        LinkedHashMap<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (int s = 0; s < LOOT_SLOTS; s++) {
            ItemStack it = h.inv.getItem(s);
            if (!marked(it)) continue;
            Data d = read(it);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("chance", d.chance());
            if (!d.cmdOnly()) {
                e.put("amount-min", d.min());
                e.put("amount-max", d.max());
                ItemStack one = clean(it).clone();
                one.setAmount(1);
                e.put("item-data", LootService.encode(one));
            }
            if (!d.cmds().isEmpty()) e.put("commands", d.cmds());
            out.put(d.id(), e);
        }
        for (String id : h.hidden) if (oldData.containsKey(id)) out.put(id, oldData.get(id));
        pl.cfg.loot().set(path, null);
        for (Map.Entry<String, Map<String, Object>> en : out.entrySet())
            for (Map.Entry<String, Object> f : en.getValue().entrySet())
                pl.cfg.loot().set(path + "." + en.getKey() + "." + f.getKey(), f.getValue());
        pl.cfg.save("loot");
    }

    // ---------- events ----------
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof TierHolder || h instanceof EditHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (holder instanceof TierHolder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory() || !p.hasPermission("eventcore.gui")) return;
            int slot = e.getSlot();
            if (slot == 22) pl.adminGui.openMain(p);
            else if (slot >= 10 && slot - 10 < h.ids.size()) openEntries(p, h.ids.get(slot - 10), 0);
            return;
        }
        if (!(holder instanceof EditHolder h)) return;
        if (!p.hasPermission("eventcore.gui")) { e.setCancelled(true); return; }
        boolean top = e.getClickedInventory() == e.getView().getTopInventory();

        if (!top) {                                   // player's own inventory
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
            else if (e.isShiftClick()) {              // shift-click adds a copy as a new entry
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it != null && !it.getType().isAir()) addEntry(h, it, -1);
            }
            return;
        }

        e.setCancelled(true);                          // the editor chest is fully locked
        int slot = e.getSlot();
        if (slot >= LOOT_SLOTS) {
            switch (slot) {
                case 48 -> { save(h); h.skipSave = true; pl.sched.entity(p, () -> openTiers(p)); }
                case 49 -> { save(h); p.sendMessage(pl.msg("gui.loot-saved")); }
                case 50 -> command(p, h);
                default -> { }
            }
            return;
        }
        ItemStack it = h.inv.getItem(slot);
        if (!marked(it)) {                             // empty slot: add the cursor item
            ItemStack cur = e.getCursor();
            if (cur != null && !cur.getType().isAir()) addEntry(h, cur, slot);
            return;
        }
        ClickType c = e.getClick();
        Data d = read(it);
        if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) { h.inv.setItem(slot, null); return; }
        if (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) {
            if (d.cmdOnly()) return;
            int delta = c == ClickType.SHIFT_LEFT ? 1 : -1;
            int min = Math.max(1, d.min() + delta), max = Math.max(min, d.max() + delta);
            h.inv.setItem(slot, mark(it, new Data(d.id(), d.chance(), min, max, false, d.cmds())));
        } else if (c == ClickType.LEFT || c == ClickType.RIGHT) {
            double ch = Math.max(0, Math.min(100, d.chance() + (c == ClickType.LEFT ? CHANCE_STEP : -CHANCE_STEP)));
            h.inv.setItem(slot, mark(it, new Data(d.id(), ch, d.min(), d.max(), d.cmdOnly(), d.cmds())));
        }
    }

    private void addEntry(EditHolder h, ItemStack source, int slot) {
        if (slot < 0) for (int i = 0; i < LOOT_SLOTS; i++) if (h.inv.getItem(i) == null) { slot = i; break; }
        if (slot < 0 || h.inv.getItem(slot) != null) return;
        String id = nextId(usedIds(h), source.getType().name().toLowerCase(Locale.ROOT));
        ItemStack base = clean(source).clone();
        int amt = Math.max(1, source.getAmount());
        h.inv.setItem(slot, mark(base, new Data(id, 50.0, amt, amt, false, new ArrayList<>())));
    }

    private void command(Player p, EditHolder h) {
        save(h);
        h.skipSave = true;
        p.closeInventory();
        pl.chat.ask(p, pl.msg("gui.prompt-simple", "what", "command to run as a reward (use {player}, no slash)"), in -> {
            ConfigurationSection es = pl.loot.entries(h.tier);
            Set<String> used = es == null ? new HashSet<>() : new HashSet<>(es.getKeys(false));
            String id = nextId(used, "command");
            pl.cfg.loot().set("tiers." + h.tier + ".entries." + id + ".chance", 10.0);
            pl.cfg.loot().set("tiers." + h.tier + ".entries." + id + ".commands", List.of(in.replaceFirst("^/", "")));
            pl.cfg.save("loot");
            return null;
        }, () -> openEntries(p, h.tier, 0));
    }

    /** Closing the editor saves it. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EditHolder h) || !(e.getPlayer() instanceof Player p)) return;
        if (!h.skipSave && p.hasPermission("eventcore.gui")) save(h);
    }
}
