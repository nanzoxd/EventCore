package com.eventcore.gui.dialog;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Gold-and-cream palette for every dialog. Colours come from the "theme:" section of config.yml and are re-read by
 * {@link #reload(ConfigurationSection)} (called on enable, so /reload applies changes, and by the dialogs' Reload button).
 * Missing or invalid values fall back to the defaults below.
 */
public final class Theme {
    public static final TextColor TEXT = TextColor.color(0xFFF0CC), SECONDARY = TextColor.color(0xF7E2AD), GOLD = TextColor.color(0xF1D58F),
            ENABLED = TextColor.color(0x80ED26), DISABLED = TextColor.color(0xFF1717), INFO = TextColor.color(0x5490FF),
            ADMIN_INFO = TextColor.color(0x91B8FF), SPECIAL = TextColor.color(0xFADE55);

    private static volatile Theme current = new Theme(null);

    public final TextColor text, secondary, gold, enabled, disabled, info, adminInfo, special;

    private Theme(ConfigurationSection s) {
        text = fromHex(s == null ? null : s.getString("text"), TEXT);
        secondary = fromHex(s == null ? null : s.getString("secondary"), SECONDARY);
        gold = fromHex(s == null ? null : s.getString("gold"), GOLD);
        enabled = fromHex(s == null ? null : s.getString("enabled"), ENABLED);
        disabled = fromHex(s == null ? null : s.getString("disabled"), DISABLED);
        info = fromHex(s == null ? null : s.getString("info"), INFO);
        adminInfo = fromHex(s == null ? null : s.getString("admin-info"), ADMIN_INFO);
        special = fromHex(s == null ? null : s.getString("special"), SPECIAL);
    }

    public static Theme get() { return current; }

    /** @param section the "theme:" section of config.yml (null = defaults) */
    public static void reload(ConfigurationSection section) { current = new Theme(section); }

    /** "#RRGGBB" or "RRGGBB" to a colour, or the fallback when the text is missing or invalid. */
    public static TextColor fromHex(String hex, TextColor fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String h = hex.trim();
        TextColor c = TextColor.fromHexString(h.startsWith("#") ? h : "#" + h);
        return c == null ? fallback : c;
    }
}
