package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Config-driven rewards (console commands + items), shared by Egg Hunt and Natural Disaster.
 * A reward is {commands: ["give {player} apple 1"], items: ["DIAMOND:3"], message: "..."}.
 * Commands run as console on the global scheduler; items go to the player's inventory (the overflow drops at their feet).
 * An offline player only receives the commands.
 */
public final class Rewards {
    private Rewards() { }

    /** at = the threshold (eggs) or the place this entry belongs to; 0 for a single reward section. */
    public record Entry(int at, List<String> commands, List<String> items, String message) {
        public boolean empty() { return commands.isEmpty() && items.isEmpty(); }
    }

    /** Reads a list of maps such as [{eggs: 10, items: [...]}]; atKey is the key holding the threshold / place. */
    public static List<Entry> read(List<Map<?, ?>> raw, String atKey) {
        List<Entry> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<?, ?> m : raw) {
            Object at = m.get(atKey);
            if (!(at instanceof Number n)) continue;
            Object msg = m.get("message");
            out.add(new Entry(n.intValue(), strings(m.get("commands")), strings(m.get("items")), msg == null ? "" : String.valueOf(msg)));
        }
        return out;
    }

    /** A single reward section {commands, items, message}; null gives an empty reward. */
    public static Entry section(ConfigurationSection s) {
        if (s == null) return new Entry(0, List.of(), List.of(), "");
        return new Entry(0, s.getStringList("commands"), s.getStringList("items"), s.getString("message", ""));
    }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    /** "DIAMOND", "DIAMOND:3" -> ItemStack, or null when the material is unknown. */
    public static ItemStack item(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] p = spec.trim().split(":");
        Material m = Material.matchMaterial(p[0].trim().toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) return null;
        int amount = 1;
        if (p.length > 1) {
            try { amount = Math.max(1, Math.min(2304, Integer.parseInt(p[1].trim()))); } catch (NumberFormatException ignored) { }
        }
        return new ItemStack(m, amount);
    }

    public static String fill(String s, Map<String, Object> ph) {
        for (Map.Entry<String, Object> e : ph.entrySet()) s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        return s;
    }

    /** Gives the reward. online may be null. {player} is always available in commands, plus whatever ph holds. */
    public static void give(EventCorePlugin pl, String name, Player online, Entry r, Map<String, Object> ph) {
        Map<String, Object> m = new LinkedHashMap<>(ph);
        m.put("player", name);
        for (String c : r.commands()) {
            String cmd = fill(c, m).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isEmpty()) continue;
            final String run = cmd;
            pl.sched.global(() -> {
                try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run); }
                catch (Exception ex) { pl.getLogger().warning("Reward command failed (" + run + "): " + ex.getMessage()); }
            });
        }
        if (online == null || !online.isOnline()) return;
        for (String spec : r.items()) {
            ItemStack st = item(spec);
            if (st == null) { pl.getLogger().warning("Reward item not recognised: " + spec); continue; }
            pl.sched.entity(online, () -> Util.give(online, st));
        }
    }
}
