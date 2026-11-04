package com.eventcore.util;

import org.bukkit.World;

/**
 * Locator bar gamerule through the typed API first (survives renames), then the old/new string names.
 * Works on servers that predate the rule too: get() then returns null.
 */
public final class LocatorRule {
    private LocatorRule() { }

    public static Boolean get(World w) {
        try { Boolean v = w.getGameRuleValue(org.bukkit.GameRule.LOCATOR_BAR); if (v != null) return v; } catch (Throwable ignored) { }
        for (String n : new String[]{"locatorBar", "locator_bar"}) {
            try { String v = w.getGameRuleValue(n); if (v != null) return Boolean.parseBoolean(v); } catch (Throwable ignored) { }
        }
        return null;
    }

    public static boolean set(World w, boolean value) {
        try { if (w.setGameRule(org.bukkit.GameRule.LOCATOR_BAR, value)) return true; } catch (Throwable ignored) { }
        for (String n : new String[]{"locatorBar", "locator_bar"}) {
            try { if (w.setGameRuleValue(n, String.valueOf(value))) return true; } catch (Throwable ignored) { }
        }
        return false;
    }
}
