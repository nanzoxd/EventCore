package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Mouse;
import com.eventcore.util.Util;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Config editor: every key of config / messages / events / rituals / loot / gui as a widget for its type.
 * boolean: toggle | number: steppers (or type a value) | text: type in chat | list: add, remove last, or replace with YAML.
 */
public final class ConfigGui {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PER_PAGE = 45;
    private final EventCorePlugin pl;

    public ConfigGui(EventCorePlugin pl) { this.pl = pl; }

    private static String esc(String s) { return MM.escapeTags(s); }

    private static boolean secret(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        return l.contains("webhook") || l.contains("token") || l.contains("password") || l.contains("secret");
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    private String show(String path, Object v) {
        if (secret(path)) return "(hidden)";
        return esc(cut(String.valueOf(v).replace('\n', ' '), 48));
    }

    // ---------- file list ----------
    public void openFiles(Player p) {
        AdminGui a = pl.adminGui;
        a.open(p, "Config editor", 3, pg -> {
            String[][] files = {{"config", "Palette, style, discord, hunt and bounty settings", "COMPARATOR"}, {"messages", "Every chat message and title", "WRITABLE_BOOK"},
                    {"events", "Events, scheduler, airdrop", "NETHER_STAR"}, {"rituals", "Ritual creator: timer, sigil, particle palettes", "BEACON"},
                    {"loot", "Crate loot pools", "CHEST"}, {"gui", "GUI layouts (ritual, bounty)", "ITEM_FRAME"}};
            int slot = 10;
            for (String[] f : files) {
                Material m = Material.matchMaterial(f[2]);
                String name = f[0];
                a.set(pg, slot++, a.icon(m == null ? Material.PAPER : m, name + ".yml", f[1], "", Mouse.left(pl, "browse")), (pl2, c) -> open(pl2, name, "", 0));
            }
            a.back(pg, 22);
        });
    }

    // ---------- section browser ----------
    public void open(Player p, String file, String path, int page) {
        YamlConfiguration y = pl.cfg.get(file);
        ConfigurationSection sec = y == null ? null : path.isEmpty() ? y : y.getConfigurationSection(path);
        if (sec == null) { openFiles(p); return; }
        List<String> keys = new ArrayList<>(sec.getKeys(false));
        int pages = Math.max(1, (keys.size() + PER_PAGE - 1) / PER_PAGE);
        int pageNo = Math.max(0, Math.min(pages - 1, page));
        AdminGui a = pl.adminGui;
        String title = cut(file + (path.isEmpty() ? "" : " › " + path.replace(".", " › ")), 40);
        a.open(p, title, 6, pg -> {
            for (int i = 0; i < PER_PAGE; i++) {
                int idx = pageNo * PER_PAGE + i;
                if (idx >= keys.size()) break;
                String key = keys.get(idx), full = path.isEmpty() ? key : path + "." + key;
                Object v = sec.get(key);
                if (sec.isConfigurationSection(key)) {
                    int n = sec.getConfigurationSection(key).getKeys(false).size();
                    a.set(pg, i, a.icon(Material.BOOKSHELF, esc(key), n + " entries", "", Mouse.left(pl, "open")), (pl2, c) -> open(pl2, file, full, 0));
                } else widget(pg, i, file, full, key, v, pageNo);
            }
            a.set(pg, 45, a.icon(Material.ARROW, "Up", Mouse.left(pl, "go up")), (pl2, c) -> {
                if (path.isEmpty()) openFiles(pl2);
                else open(pl2, file, path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "", 0);
            });
            a.set(pg, 46, a.icon(Material.BOOKSHELF, "Files", Mouse.left(pl, "all files")), (pl2, c) -> openFiles(pl2));
            if (pageNo > 0) a.set(pg, 48, a.icon(Material.ARROW, "Previous page", Mouse.left(pl, "previous")), (pl2, c) -> open(pl2, file, path, pageNo - 1));
            a.set(pg, 49, a.icon(Material.PAPER, "Page " + (pageNo + 1) + " / " + pages, keys.size() + " keys"), null);
            if (pageNo < pages - 1) a.set(pg, 50, a.icon(Material.ARROW, "Next page", Mouse.left(pl, "next")), (pl2, c) -> open(pl2, file, path, pageNo + 1));
            a.set(pg, 53, a.icon(Material.NETHER_STAR, "Main menu", Mouse.left(pl, "open")), (pl2, c) -> pl.adminGui.openMain(pl2));
        });
    }

    private void widget(AdminGui.Page pg, int slot, String file, String full, String key, Object v, int pageNo) {
        AdminGui a = pl.adminGui;
        String name = esc(key);
        String parent = full.contains(".") ? full.substring(0, full.lastIndexOf('.')) : "";
        if (v instanceof Boolean b) {
            a.set(pg, slot, a.icon(b ? Material.LIME_DYE : Material.GRAY_DYE, name, "Value: " + (b ? "<color:%good%>true" : "<color:%bad%>false"), "", Mouse.left(pl, "turn " + (b ? "off" : "on"))),
                    (p, c) -> { set(file, full, !b); pg.fill.run(); });
        } else if (v instanceof Number n) {
            boolean whole = v instanceof Integer || v instanceof Long;
            double step = step(n.doubleValue(), whole);
            String st = whole ? String.valueOf((long) step) : String.valueOf(step);
            String st5 = whole ? String.valueOf((long) step * 5) : String.valueOf(step * 5);
            a.set(pg, slot, a.icon(Material.REPEATER, name, "Value: <color:%white%>" + n, "", Mouse.left(pl, "+" + st), Mouse.right(pl, "-" + st),
                            Mouse.shiftLeft(pl, "+" + st5), Mouse.shiftRight(pl, "-" + st5), Mouse.drop(pl, "type a value")),
                    (p, c) -> {
                        if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) { ask(p, file, full, v, parent, pageNo); return; }
                        double d = (c.isLeftClick() ? 1 : -1) * step * (c.isShiftClick() ? 5 : 1);
                        double nv = n.doubleValue() + d;
                        Object out = whole ? (Object) (v instanceof Long ? (Object) Math.round(nv) : (Object) (int) Math.round(nv))
                                : (Object) BigDecimal.valueOf(nv).setScale(3, RoundingMode.HALF_UP).doubleValue();
                        set(file, full, out);
                        pg.fill.run();
                    });
        } else if (v instanceof List<?> l) {
            boolean strings = l.stream().allMatch(o -> o instanceof String);
            List<String> lore = new ArrayList<>();
            lore.add(l.size() + " entries");
            for (int i = 0; i < Math.min(6, l.size()); i++) lore.add("<color:%white%>" + show(full, l.get(i)));
            if (l.size() > 6) lore.add("<color:%soft%>+" + (l.size() - 6) + " more");
            lore.add("");
            if (strings) { lore.add(Mouse.left(pl, "add an entry")); lore.add(Mouse.right(pl, "remove the last")); }
            lore.add(Mouse.shiftLeft(pl, "replace with YAML like [a, b]"));
            a.set(pg, slot, a.icon(Material.BOOK, name, lore.toArray(new String[0])), (p, c) -> {
                if (c == ClickType.SHIFT_LEFT || !strings) { ask(p, file, full, v, parent, pageNo); return; }
                if (c.isLeftClick()) {
                    p.closeInventory();
                    pl.chat.ask(p, pl.msg("gui.prompt-add", "path", full), in -> {
                        List<String> cur = new ArrayList<>(pl.cfg.get(file).getStringList(full));
                        cur.add(in);
                        set(file, full, cur);
                        return null;
                    }, () -> open(p, file, parent, pageNo));
                } else if (c.isRightClick() && !c.isShiftClick() && !l.isEmpty()) {
                    List<String> cur = new ArrayList<>(pl.cfg.get(file).getStringList(full));
                    cur.remove(cur.size() - 1);
                    set(file, full, cur);
                    pg.fill.run();
                }
            });
        } else {
            a.set(pg, slot, a.icon(v instanceof String ? Material.PAPER : Material.MAP, name, "Value: <color:%white%>" + show(full, v), "", Mouse.left(pl, "edit in chat")),
                    (p, c) -> ask(p, file, full, v, parent, pageNo));
        }
    }

    private static double step(double v, boolean whole) {
        double a = Math.abs(v);
        if (whole) return a >= 1000 ? 100 : a >= 100 ? 10 : 1;
        return a >= 100 ? 10 : a >= 10 ? 1 : 0.1;
    }

    private void set(String file, String path, Object value) {
        pl.cfg.set(file, path, value);
        pl.afterConfigChange(file);
    }

    private void ask(Player p, String file, String full, Object cur, String parent, int pageNo) {
        p.closeInventory();
        pl.chat.ask(p, pl.msg("gui.prompt-edit", "path", full, "value", secret(full) ? "(hidden)" : cut(String.valueOf(cur), 60)), in -> {
            set(file, full, parse(cur, in));
            return null;
        }, () -> open(p, file, parent, pageNo));
    }

    /** Same type as the current value; lists and other values are read as YAML (e.g. [a, b] or ["#d8b04a", "#fff0cc"]). */
    private Object parse(Object cur, String in) {
        try {
            if (cur instanceof Boolean) {
                String l = in.toLowerCase(Locale.ROOT);
                if (l.equals("true") || l.equals("on") || l.equals("yes")) return true;
                if (l.equals("false") || l.equals("off") || l.equals("no")) return false;
                throw new IllegalArgumentException("expected true or false");
            }
            if (cur instanceof Integer) return Integer.parseInt(in.trim());
            if (cur instanceof Long) return Long.parseLong(in.trim());
            if (cur instanceof Number) return Double.parseDouble(in.trim());
            if (cur instanceof String) return in;
            YamlConfiguration t = new YamlConfiguration();
            t.loadFromString("v: " + in);
            Object o = t.get("v");
            if (cur instanceof List && !(o instanceof List)) throw new IllegalArgumentException("expected a list like [a, b]");
            if (o == null) throw new IllegalArgumentException("empty value");
            return o;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number");
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("invalid YAML");
        }
    }
}
