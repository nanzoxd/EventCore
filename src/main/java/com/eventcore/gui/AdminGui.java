package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.event.GameEvent;
import com.eventcore.util.Mouse;
import com.eventcore.util.Util;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Admin menu (/eventcore gui), peach + gold theme from config.yml theme.*:
 *   main -> Events | Airdrop | Loot tables | Ritual creator | Ritual settings | Altar recipes | Config editor
 *           | Player hunt | Bounty | Reload
 * Every control line in a tooltip uses the mouse icons (see util/Mouse).
 * Events: left = on/off, right = start/stop, shift = duration +-5m.
 * Airdrop: every number is a stepper (left +, right -, shift x5). Everything else lives in the yml files.
 */
public final class AdminGui implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String AIR = "events.airdrop.settings.";

    static final class Page implements InventoryHolder {
        Inventory inv; Runnable fill;
        final Map<Integer, BiConsumer<Player, ClickType>> acts = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public AdminGui(EventCorePlugin pl) { this.pl = pl; }

    // ---------- theme helpers ----------
    String th(String key, String def) { return pl.cfg.config().getString("theme." + key, def); }
    private String fmt(String s) {
        return s.replace("%main%", th("main", "#d8b04a")).replace("%soft%", th("soft", "#ffd7ca"))
                .replace("%good%", th("good", "#f1d58f")).replace("%bad%", th("bad", "#ffcfca"))
                .replace("%warn%", th("warn", "#e8c670")).replace("%info%", th("info", "#ffe4d5")).replace("%white%", th("white", "#fffbf0"));
    }
    Component mm(String s) { return MM.deserialize("<!italic>" + fmt(s)); }

    ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm("<color:%main%>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm("<color:%info%>" + s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    void open(Player p, String title, int rows, Consumer<Page> filler) {
        Page pg = new Page();
        String grad = title.equals("EventCore") ? String.join(":", pl.cfg.config().getStringList("eventcore-gradient").isEmpty()
                ? java.util.List.of("#808080", "#C4C4C4", "#808080") : pl.cfg.config().getStringList("eventcore-gradient")) : "%main%:%soft%";
        pg.inv = Bukkit.createInventory(pg, rows * 9, mm("<gradient:" + grad + ">" + title + "</gradient>"));
        pg.fill = () -> {
            pg.inv.clear();
            pg.acts.clear();
            ItemStack glass = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < pg.inv.getSize(); i++) pg.inv.setItem(i, glass);
            filler.accept(pg);
        };
        pg.fill.run();
        p.openInventory(pg.inv);
    }

    void set(Page pg, int slot, ItemStack it, BiConsumer<Player, ClickType> act) {
        pg.inv.setItem(slot, it);
        if (act != null) pg.acts.put(slot, act);
    }

    void back(Page pg, int slot) {
        set(pg, slot, icon(Material.ARROW, "Back", Mouse.left(pl, "go back")), (p, c) -> openMain(p));
    }

    // ---------- pages ----------
    public void openMain(Player p) {
        open(p, "EventCore", 4, pg -> {
            String go = Mouse.left(pl, "open");
            set(pg, 10, icon(Material.NETHER_STAR, "Events", "Turn events on or off and start them", "", go), (pl2, c) -> openEvents(pl2));
            set(pg, 11, icon(Material.SHULKER_BOX, "Airdrop", "Fall time, spawn area, crate health", "", go), (pl2, c) -> openAirdrop(pl2));
            set(pg, 12, icon(Material.CHEST, "Loot tables", "Edit what the crates drop", "", go), (pl2, c) -> pl.lootGui.openTiers(pl2));
            set(pg, 13, icon(Material.ENDER_EYE, "Ritual creator", "Open the ritual menu", "", go), (pl2, c) -> pl.ritualGui.openMain(pl2));
            set(pg, 14, icon(Material.BEACON, "Ritual settings", "Timer, sigil, particles, palettes, sounds, bossbar", "", go),
                    (pl2, c) -> pl.configGui.open(pl2, "rituals", "", 0));
            set(pg, 15, icon(Material.ENCHANTING_TABLE, "Altar recipes", "Create and edit altar recipes", "", go), (pl2, c) -> pl.altarGui.openList(pl2, 0));
            set(pg, 16, icon(Material.WRITABLE_BOOK, "Config editor", "Every key of every file, editable in game", "", go), (pl2, c) -> pl.configGui.openFiles(pl2));
            set(pg, 21, icon(Material.COMPASS, "Player hunt", "Hunted players: " + pl.hunt.count(), "Live coordinates in the bossbar", "", go), (pl2, c) -> pl.huntGui.openHunt(pl2));
            set(pg, 23, icon(Material.GOLDEN_SWORD, "Bounty", "Active bounties: " + pl.bounty.count(), "Reward, timer, survive or get killed", "", go), (pl2, c) -> pl.huntGui.openBounty(pl2));
            set(pg, 31, icon(Material.LIME_DYE, "Reload", "Re-read every config file", "", Mouse.left(pl, "reload")), (pl2, c) -> { pl.reloadAll(pl2); pl2.closeInventory(); });
        });
    }

    private Material eventIcon(String id) {
        return switch (id) {
            case "vulnerable" -> Material.REDSTONE;
            case "shieldless" -> Material.SHIELD;
            case "golden_trees" -> Material.GOLDEN_APPLE;
            case "anonymous" -> Material.PLAYER_HEAD;
            case "glow" -> Material.GLOWSTONE_DUST;
            case "elo_buff" -> Material.EXPERIENCE_BOTTLE;
            case "gold_frenzy" -> Material.GOLD_INGOT;
            case "locator_bar" -> Material.COMPASS;
            case "airdrop" -> Material.BEACON;
            default -> Material.PAPER;
        };
    }

    public void openEvents(Player p) {
        open(p, "Events", 4, pg -> {
            int slot = 10;
            for (GameEvent ev : pl.events.registry().values()) {
                if (slot == 17) slot = 19;
                if (slot > 25) break;
                boolean on = ev.sec().getBoolean("enabled", true);
                ActiveEvent ae = pl.events.active(ev.id());
                String state = ae != null ? "<color:%good%>Running " + Util.mmss(ae.secondsLeft()) : on ? "<color:%soft%>Idle" : "<color:%bad%>Disabled";
                int dur = ev.sec().getInt("duration-minutes", 10);
                String id = ev.id();
                set(pg, slot++, icon(eventIcon(id), Util.title(ev.displayName()),
                        "Status: " + state,
                        "Duration: <color:%white%>" + dur + " min",
                        "",
                        Mouse.left(pl, "turn " + (on ? "off" : "on")),
                        Mouse.right(pl, (ae != null ? "stop" : "start") + " now"),
                        Mouse.shiftLeft(pl, "duration +5 min"),
                        Mouse.shiftRight(pl, "duration -5 min")), (pl2, c) -> {
                    String path = "events." + id + ".";
                    if (c.isShiftClick()) {
                        int nd = Math.max(1, ev.sec().getInt("duration-minutes", 10) + (c.isLeftClick() ? 5 : -5));
                        pl.cfg.set("events", path + "duration-minutes", nd);
                    } else if (c.isLeftClick()) {
                        pl.cfg.set("events", path + "enabled", !ev.sec().getBoolean("enabled", true));
                    } else if (c.isRightClick()) {
                        if (pl.events.isActive(id)) pl.events.stop(id, true);
                        else {
                            String err = pl.events.start(id, null, false);
                            if (err != null) pl2.sendMessage(pl.msg("errors." + err, "event", ev.displayName()));
                        }
                    }
                    pl.afterConfigChange("events");
                    pg.fill.run();
                });
            }
            String dm = pl.cfg.config().getString("event-display", "BOSSBAR").toUpperCase(Locale.ROOT);
            set(pg, 29, icon(Material.OAK_SIGN, "Event display", "Now: <color:%white%>" + dm, "BOSSBAR, ACTIONBAR or BOTH", "", Mouse.left(pl, "switch")), (pl2, c) -> {
                String next = dm.equals("BOSSBAR") ? "ACTIONBAR" : dm.equals("ACTIONBAR") ? "BOTH" : "BOSSBAR";
                pl.cfg.set("config", "event-display", next);
                pg.fill.run();
            });
            back(pg, 31);
        });
    }

    public void openAirdrop(Player p) {
        open(p, "Airdrop", 4, pg -> {
            stepper(pg, 10, Material.CLOCK, "Fall time (minutes)", "fall.duration-minutes", 1, 1, 120, true);
            stepper(pg, 11, Material.HOPPER, "Incoming countdown (seconds)", "spawn.incoming-seconds", 10, 1, 3600, false);
            stepper(pg, 12, Material.REPEATER, "Seconds between drops", "spawn.interval-seconds", 10, 5, 3600, false);
            stepper(pg, 13, Material.SHULKER_BOX, "Max crates per event", "spawn.max-crates", 1, 0, 100, false);
            stepper(pg, 14, Material.MAP, "Area: blocks each side", "spawn.area.half-size", 500, 100, 30000000, false);
            stepper(pg, 15, Material.IRON_BARS, "Locked after landing (s)", "land.lock-seconds", 1, 0, 120, false);
            stepper(pg, 16, Material.FEATHER, "Parachute chickens", "parachute.chickens", 1, 0, 16, false);
            int s = 19;
            for (String tier : pl.airdrop.tierIds())
                if (s <= 22) stepper(pg, s++, Material.SHULKER_BOX, Util.title(tier) + " crate health", "tiers." + tier + ".health", 1, 1, 500, false);
            String mode = pl.cfg.events().getString(AIR + "spawn.mode", "AREA");
            set(pg, 24, icon(Material.COMPASS, "Spawn mode", "Now: <color:%white%>" + mode,
                    "AREA = random spot in the square", "AROUND_PLAYER = near a random player", "", Mouse.left(pl, "switch")), (pl2, c) -> {
                pl.cfg.set("events", AIR + "spawn.mode", mode.equalsIgnoreCase("AREA") ? "AROUND_PLAYER" : "AREA");
                pl.afterConfigChange("events");
                pg.fill.run();
            });
            back(pg, 31);
        });
    }

    private void stepper(Page pg, int slot, Material m, String label, String path, double step, double min, double max, boolean dbl) {
        double v = pl.cfg.events().getDouble(AIR + path);
        String shown = dbl ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf((long) v);
        set(pg, slot, icon(m, label, "Value: <color:%white%>" + shown, "", Mouse.left(pl, "+" + (long) step), Mouse.right(pl, "-" + (long) step), Mouse.shiftLeft(pl, "+" + (long) step * 5), Mouse.shiftRight(pl, "-" + (long) step * 5)),
                (p, c) -> {
                    double cur = pl.cfg.events().getDouble(AIR + path);
                    double nv = cur + (c.isLeftClick() ? 1 : -1) * step * (c.isShiftClick() ? 5 : 1);
                    nv = Math.max(min, Math.min(max, nv));
                    pl.cfg.set("events", AIR + path, dbl ? (Object) (Math.round(nv * 10.0) / 10.0) : (Object) (int) Math.round(nv));
                    pl.afterConfigChange("events");
                    pg.fill.run();
                });
    }

    // ---------- events ----------
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Page pg) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory() || !p.hasPermission("eventcore.gui")) return;
        BiConsumer<Player, ClickType> a = pg.acts.get(e.getSlot());
        if (a != null) a.accept(p, e.getClick());
    }
}
