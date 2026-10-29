package com.eventcore.util;

import com.eventcore.EventCorePlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mouse-control lore lines in the style of the reference tooltips:
 * <pre>
 *   ▆ ▆  LMB to +1 minute      (left half lit)
 *   ▆ ▆  RMB to -1 minute     (right half lit)
 *   ◉    Q: remove
 * </pre>
 * The lit half uses lore.mouse.active, the other half lore.mouse.inactive (both palette colours, config.yml).
 * In yml files use the raw placeholders {ml_raw} {mr_raw} {msl_raw} {msr_raw} {md_raw}.
 */
public final class Mouse {
    public static final String GLYPH = "▆";
    public static final String DROP = "◉";

    private Mouse() { }

    private static String c(EventCorePlugin pl, String key, String def) {
        return pl.cfg.config().getString("lore.mouse." + key, def);
    }

    private static String half(String hex, String g) { return "<color:" + hex + ">" + g + "</color>"; }

    /** Just the icon: lit = 0 (left), 1 (right), 2 (none lit). */
    public static String icon(EventCorePlugin pl, int lit) {
        String on = c(pl, "active", "#d8b04a"), off = c(pl, "inactive", "#ffe4d5");
        return half(lit == 0 ? on : off, GLYPH) + " " + half(lit == 1 ? on : off, GLYPH);
    }

    public static String dropIcon(EventCorePlugin pl) { return half(c(pl, "active", "#d8b04a"), DROP); }

    private static String line(EventCorePlugin pl, String icon, String text) {
        return icon + "  <color:" + c(pl, "text", "#fffbf0") + ">" + text + "</color>";
    }

    public static String left(EventCorePlugin pl, String text) { return line(pl, icon(pl, 0), "LMB to " + text); }
    public static String right(EventCorePlugin pl, String text) { return line(pl, icon(pl, 1), "RMB to " + text); }
    public static String shiftLeft(EventCorePlugin pl, String text) { return line(pl, icon(pl, 0), "SHIFT + LMB to " + text); }
    public static String shiftRight(EventCorePlugin pl, String text) { return line(pl, icon(pl, 1), "SHIFT + RMB to " + text); }
    public static String drop(EventCorePlugin pl, String text) { return line(pl, dropIcon(pl) + " ", "Q to " + text); }

    /** Raw placeholders for gui.yml / messages.yml templates. */
    public static Map<String, Object> ph(EventCorePlugin pl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ml_raw", icon(pl, 0));
        m.put("mr_raw", icon(pl, 1));
        m.put("msl_raw", icon(pl, 0));
        m.put("msr_raw", icon(pl, 1));
        m.put("md_raw", dropIcon(pl) + " ");
        return m;
    }
}
