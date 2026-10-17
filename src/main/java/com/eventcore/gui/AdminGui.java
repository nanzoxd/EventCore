package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * /eventcore gui. A main menu (layout in gui.yml) plus a generic YAML browser: every key of every config file is
 * listed with a widget matching its type, so literally every setting is editable in-game.
 * Booleans toggle, numbers step or take chat input, strings take chat input (sounds preview on right click),
 * lists add/remove entries. Values are validated, saved at once and applied live.
 */
public final class AdminGui implements Listener {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    static final class MainHolder implements InventoryHolder {
        Inventory inv; final Map<Integer, ConfigurationSection> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }
    static final class BrowserHolder implements InventoryHolder {
        Inventory inv; String file, path; int page; final List<String> keys = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public AdminGui(EventCorePlugin pl) { this.pl = pl; }

    private Component plain(String s, NamedTextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    // ---------- main menu ----------
    public void openMain(Player p) {
        ConfigurationSection g = pl.cfg.gui().getConfigurationSection("admin-main");
        MainHolder h = new MainHolder();
        h.inv = Bukkit.createInventory(h, Math.max(1, Math.min(6, g.getInt("rows", 3))) * 9, pl.text.render(g.getString("title", "eventcore admin"), null));
        ConfigurationSection items = g.getConfigurationSection("items");
        if (items != null) for (String k : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(k);
            if (s == null) continue;
            h.inv.setItem(s.getInt("slot"), GuiUtil.item(pl, s, Map.of()));
            h.actions.put(s.getInt("slot"), s);
        }
        p.openInventory(h.inv);
    }

    // ---------- browser ----------
    public void openBrowser(Player p, String file, String path, int page) {
        BrowserHolder h = new BrowserHolder();
        h.file = file; h.path = path; h.page = page;
        h.inv = Bukkit.createInventory(h, 54, pl.text.render("<g>" + file + (path.isEmpty() ? "" : " > " + path) + "</g>", null));
        render(h);
        p.openInventory(h.inv);
    }

    private ConfigurationSection section(BrowserHolder h) {
        YamlConfiguration y = pl.cfg.get(h.file);
        if (y == null) return null;
        return h.path.isEmpty() ? y : y.getConfigurationSection(h.path);
    }

    private void render(BrowserHolder h) {
        h.inv.clear();
        h.keys.clear();
        ConfigurationSection s = section(h);
        if (s == null) return;
        h.keys.addAll(s.getKeys(false));
        int from = h.page * 45;
        for (int i = 0; i < 45 && from + i < h.keys.size(); i++) h.inv.setItem(i, icon(s, h.keys.get(from + i), h.path));
        ConfigurationSection nav = pl.cfg.gui().getConfigurationSection("admin-browser.nav");
        for (String k : List.of("prev", "up", "home", "reload", "next")) {
            ConfigurationSection n = nav == null ? null : nav.getConfigurationSection(k);
            if (n != null) h.inv.setItem(n.getInt("slot"), GuiUtil.item(pl, n, Map.of()));
        }
    }

    private int navSlot(String key, int def) { return pl.cfg.gui().getInt("admin-browser.nav." + key + ".slot", def); }

    private ItemStack icon(ConfigurationSection s, String key, String parent) {
        Object v = s.get(key);
        String full = parent.isEmpty() ? key : parent + "." + key;
        Material m; List<Component> lore = new ArrayList<>();
        lore.add(plain(full, NamedTextColor.DARK_GRAY));
        if (v instanceof ConfigurationSection cs) {
            m = Material.BOOKSHELF;
            lore.add(plain(cs.getKeys(false).size() + " entries", NamedTextColor.GRAY));
            if (cs.isBoolean("enabled")) {
                lore.add(plain("enabled: " + cs.getBoolean("enabled"), cs.getBoolean("enabled") ? NamedTextColor.GREEN : NamedTextColor.RED));
                lore.add(plain("shift-click: toggle enabled", NamedTextColor.GRAY));
            }
            lore.add(plain("click: open", NamedTextColor.YELLOW));
        } else if (v instanceof Boolean b) {
            m = b ? Material.LIME_DYE : Material.GRAY_DYE;
            lore.add(plain("value: " + b, b ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(plain("click: toggle", NamedTextColor.YELLOW));
        } else if (v instanceof Number) {
            m = Material.PAPER;
            lore.add(plain("value: " + v, NamedTextColor.WHITE));
            lore.add(plain("click: type a value | shift-left +step | shift-right -step", NamedTextColor.YELLOW));
        } else if (v instanceof List<?> l) {
            m = Material.WRITABLE_BOOK;
            for (int i = 0; i < Math.min(8, l.size()); i++) lore.add(plain("- " + trim(String.valueOf(l.get(i))), NamedTextColor.WHITE));
            if (l.size() > 8) lore.add(plain("... +" + (l.size() - 8), NamedTextColor.GRAY));
            lore.add(plain("left: add | right: remove last | shift-right: clear", NamedTextColor.YELLOW));
        } else {
            m = key.equals("sound") ? Material.NOTE_BLOCK : Material.NAME_TAG;
            lore.add(plain("value: " + trim(String.valueOf(v)), NamedTextColor.WHITE));
            lore.add(plain("click: type a value" + (key.equals("sound") ? " | right: preview" : ""), NamedTextColor.YELLOW));
        }
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(key, NamedTextColor.GOLD));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static String trim(String s) { return s.length() > 60 ? s.substring(0, 57) + "..." : s; }

    // ---------- events ----------
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof MainHolder || h instanceof BrowserHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (holder instanceof MainHolder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory() || !p.hasPermission("eventcore.gui")) return;
            ConfigurationSection a = h.actions.get(e.getSlot());
            if (a == null) return;
            switch (a.getString("action", "browse")) {
                case "loot" -> pl.lootGui.openTiers(p);
                case "reload" -> { pl.reloadAll(p); p.closeInventory(); }
                default -> openBrowser(p, a.getString("file", "config"), a.getString("path", ""), 0);
            }
        } else if (holder instanceof BrowserHolder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory() || !p.hasPermission("eventcore.gui")) return;
            browserClick(p, h, e.getSlot(), e.getClick());
        }
    }

    private void browserClick(Player p, BrowserHolder h, int slot, org.bukkit.event.inventory.ClickType click) {
        if (slot == navSlot("prev", 45)) { if (h.page > 0) { h.page--; render(h); } return; }
        if (slot == navSlot("next", 53)) { if ((h.page + 1) * 45 < h.keys.size()) { h.page++; render(h); } return; }
        if (slot == navSlot("home", 49)) { openMain(p); return; }
        if (slot == navSlot("reload", 50)) { pl.reloadAll(p); render(h); return; }
        if (slot == navSlot("up", 48)) {
            if (h.path.isEmpty()) openMain(p);
            else openBrowser(p, h.file, h.path.contains(".") ? h.path.substring(0, h.path.lastIndexOf('.')) : "", 0);
            return;
        }
        int idx = h.page * 45 + slot;
        if (slot >= 45 || idx >= h.keys.size()) return;
        ConfigurationSection s = section(h);
        if (s == null) return;
        String key = h.keys.get(idx), full = h.path.isEmpty() ? key : h.path + "." + key;
        Object v = s.get(key);

        if (v instanceof ConfigurationSection cs) {
            if (click.isShiftClick() && cs.isBoolean("enabled")) { change(h, full + ".enabled", !cs.getBoolean("enabled")); render(h); }
            else openBrowser(p, h.file, full, 0);
        } else if (v instanceof Boolean b) {
            change(h, full, !b); render(h);
        } else if (v instanceof Number n) {
            if (click.isShiftClick()) {
                double step = n instanceof Double || n instanceof Float ? (Math.abs(n.doubleValue()) >= 5 ? 0.5 : 0.1) : 1;
                double nv = n.doubleValue() + (click.isLeftClick() ? step : -step);
                Object val = (n instanceof Double || n instanceof Float) ? (Object) (Math.round(nv * 100.0) / 100.0) : (Object) (int) Math.round(nv);
                try { validateNumber(full, ((Number) val).doubleValue()); change(h, full, val); render(h); }
                catch (IllegalArgumentException ex) { p.sendMessage(pl.msg("gui.invalid", "reason", ex.getMessage())); }
            } else prompt(p, h, full, v);
        } else if (v instanceof List<?> l) {
            if (click.isShiftClick() && click.isRightClick()) { change(h, full, new ArrayList<>()); render(h); }
            else if (click.isRightClick()) {
                if (!l.isEmpty()) { List<Object> c = new ArrayList<>(l); c.remove(c.size() - 1); change(h, full, c); render(h); }
            } else promptList(p, h, full, l);
        } else {
            if (click.isRightClick() && key.equals("sound")) { preview(p, s); return; }
            prompt(p, h, full, v);
        }
    }

    private void preview(Player p, ConfigurationSection s) {
        try {
            p.playSound(Sound.sound(Key.key(s.getString("sound", "").toLowerCase(Locale.ROOT)), Sound.Source.MASTER,
                    (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0)));
        } catch (Exception ex) { p.sendMessage(pl.msg("gui.invalid", "reason", "bad sound key")); }
    }

    private void prompt(Player p, BrowserHolder h, String full, Object old) {
        p.closeInventory();
        pl.chat.ask(p, pl.msg("gui.prompt-edit", "path", full, "value", String.valueOf(old)), in -> {
            Object val = parse(full, old, in);
            change(h, full, val);
            return null;
        }, () -> openBrowser(p, h.file, h.path, h.page));
    }

    private void promptList(Player p, BrowserHolder h, String full, List<?> old) {
        p.closeInventory();
        pl.chat.ask(p, pl.msg("gui.prompt-add", "path", full), in -> {
            Object val;
            if (!old.isEmpty() && old.get(0) instanceof List) {
                List<String> inner = new ArrayList<>();
                for (String part : in.split("[,\\s]+")) if (!part.isBlank()) inner.add(part.trim().toLowerCase(Locale.ROOT));
                val = inner;
            } else { validateString(full, in, true); val = in; }
            List<Object> c = new ArrayList<>(old);
            c.add(val);
            change(h, full, c);
            return null;
        }, () -> openBrowser(p, h.file, h.path, h.page));
    }

    /** Writes a value, saves the file and applies it live. */
    private void change(BrowserHolder h, String path, Object value) {
        pl.cfg.set(h.file, path, value);
        pl.afterConfigChange(h.file);
    }

    // ---------- parsing & validation ----------
    private Object parse(String path, Object old, String in) {
        if (old instanceof Number n) {
            try {
                Object val;
                if (in.contains(".") || n instanceof Double || n instanceof Float) val = Double.parseDouble(in);
                else val = Integer.parseInt(in);
                if (n instanceof Integer && val instanceof Double d && d == Math.rint(d)) val = (int) Math.rint(d);
                validateNumber(path, ((Number) val).doubleValue());
                return val;
            } catch (NumberFormatException ex) { throw new IllegalArgumentException("not a valid number"); }
        }
        validateString(path, in, false);
        return in;
    }

    private void validateNumber(String path, double v) {
        String k = Util.last(path);
        if (k.contains("chance") && (v < 0 || v > 100)) throw new IllegalArgumentException("must be 0-100");
        if (k.equals("volume") && (v < 0 || v > 10)) throw new IllegalArgumentException("volume must be 0-10");
        if (k.equals("pitch") && (v < 0 || v > 2)) throw new IllegalArgumentException("pitch must be 0-2");
        if (k.equals("rows") && (v < 1 || v > 6)) throw new IllegalArgumentException("rows must be 1-6");
        if ((k.equals("slot") || k.equals("weight") || k.endsWith("-minutes") || k.endsWith("-seconds") || k.endsWith("-ticks")
                || k.equals("count") || k.equals("health") || k.equals("hearts") || k.equals("length")) && v < 0)
            throw new IllegalArgumentException("must not be negative");
    }

    private void validateString(String path, String in, boolean listItem) {
        String k = Util.last(path);
        if (in.isBlank() && !k.equals("subtitle") && !k.equals("glow-color")) throw new IllegalArgumentException("value is empty");
        switch (k) {
            case "sound" -> { if (!in.equalsIgnoreCase("none") && !in.matches("[a-z0-9_.:/-]+")) throw new IllegalArgumentException("sound keys are lowercase like block.note_block.chime"); }
            case "material", "drop-item", "to" -> { if (Material.matchMaterial(in) == null) throw new IllegalArgumentException("unknown material"); }
            case "timezone" -> { try { ZoneId.of(in); } catch (Exception e) { throw new IllegalArgumentException("unknown timezone (example Europe/Berlin)"); } }
            case "particle" -> { try { Particle.valueOf(in.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("unknown particle"); } }
            case "shulker-color" -> { try { DyeColor.valueOf(in.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("unknown dye colour"); } }
            case "style" -> { try { BossBar.Overlay.valueOf(in.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12 or NOTCHED_20"); } }
            case "shape" -> { if (!List.of("ring", "scatter", "burst", "spiral", "helix").contains(in.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("ring, scatter, burst, spiral or helix"); }
            case "color", "to-color", "glow-color" -> {
                if (path.contains("bossbar")) { try { BossBar.Color.valueOf(in.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("not a bossbar colour"); } }
                else if (!in.isEmpty() && !HEX.matcher(in).matches()) throw new IllegalArgumentException("use a hex colour like #ff6666");
            }
            case "palette", "gradient" -> { if (!HEX.matcher(in).matches()) throw new IllegalArgumentException("use a hex colour like #ff6666"); }
            case "times" -> {
                try { LocalTime.parse(in.trim().split("\\s+")[in.trim().split("\\s+").length - 1]); }
                catch (Exception e) { throw new IllegalArgumentException("use HH:mm, optionally with a day: SAT 20:00"); }
            }
            default -> { }
        }
    }
}
