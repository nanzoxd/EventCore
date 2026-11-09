package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
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

/** /disaster difficulty: easy | mid | hard (default mid). The choice is saved in events.yml (settings.difficulty). */
public final class DisasterDifficultyGui implements Listener {
    private static final int EASY = 11, MID = 13, HARD = 15;
    private static final String NAME = "<gradient:#9babc7:#E7E7E7:#E7E7E7>", END = "</gradient>";
    private static final String PATH = "events.natural_disaster.settings.difficulty";

    static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public DisasterDifficultyGui(EventCorePlugin pl) { this.pl = pl; }

    public String current() { return pl.cfg.events().getString("events.natural_disaster.settings.difficulty", "mid").toLowerCase(); }

    private ItemStack item(Material m, String name, List<String> lore, boolean selected) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.text.item(name, Map.of()));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String line : lore) l.add(pl.text.item(line, Map.of()));
        meta.lore(l);
        if (selected) meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** Must be called on the player's own thread. */
    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 27, pl.text.render(NAME + "disaster difficulty" + END, Map.of()));
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 27; i++) h.inv.setItem(i, pane);
        String cur = current();
        h.inv.setItem(EASY, item(Material.LIME_CONCRETE, NAME + "easy" + END, List.of(
                "<#AAAAAA>less damage, mostly one disaster at a time", "<#AAAAAA>longer pauses, fewer big meteors and tornadoes", "",
                cur.equals("easy") ? "<#00FF55>selected" : "<#7F7F7F>click to select"), cur.equals("easy")));
        h.inv.setItem(MID, item(Material.YELLOW_CONCRETE, NAME + "mid" + END, List.of(
                "<#AAAAAA>normal damage, one or two disasters at once", "<#AAAAAA>a mix of small and big disasters", "",
                cur.equals("mid") ? "<#00FF55>selected" : "<#7F7F7F>click to select"), cur.equals("mid")));
        h.inv.setItem(HARD, item(Material.RED_CONCRETE, NAME + "hard" + END, List.of(
                "<#AAAAAA>more damage, often two disasters at once", "<#AAAAAA>short pauses, many big meteors and tornadoes", "",
                cur.equals("hard") ? "<#00FF55>selected" : "<#7F7F7F>click to select"), cur.equals("hard")));
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
    }

    public void set(String id) { pl.cfg.set("events", PATH, id); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != h.inv) return;
        int slot = e.getSlot();
        String id = slot == EASY ? "easy" : slot == MID ? "mid" : slot == HARD ? "hard" : null;
        if (id == null) return;
        set(id);
        p.closeInventory();
        p.sendMessage(pl.msg("disaster.difficulty-set", "difficulty", id));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
