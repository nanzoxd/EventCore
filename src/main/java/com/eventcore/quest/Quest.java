package com.eventcore.quest;

import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** One quest. Loaded from quests.yml and editable from the admin GUI. */
public final class Quest {
    public final String id;
    public String category = "misc", title, description = "", requires = "";
    public Material icon = Material.PAPER;
    public QuestType type = QuestType.BREAK_BLOCK;
    public final Set<String> targets = new LinkedHashSet<>();
    public int amount = 1, order;
    /** Every quest is worth exactly one point. */
    public final int points = 1;

    public Quest(String id, String title) { this.id = id; this.title = title; }

    /** targets empty or "*" = anything of that type. */
    public boolean matches(String key) {
        return targets.isEmpty() || targets.contains("*") || (key != null && targets.contains(key));
    }

    public static String pretty(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** "Diamond Ore": the shortest target name, so DEEPSLATE_ variants do not show up. */
    public String targetName() {
        String best = null;
        for (String t : targets) if (!t.equals("*") && (best == null || t.length() < best.length())) best = t;
        return best == null ? "" : pretty(best);
    }

    /** "Mine 64 Stone" */
    public String objective() {
        String t = targetName(), n = String.valueOf(amount);
        return switch (type) {
            case KILL_PLAYER -> "Kill " + n + (amount == 1 ? " player" : " players");
            case KILL_ENTITY -> "Kill " + n + " " + (t.isEmpty() ? "mobs" : t + (amount == 1 ? "" : "s"));
            case DAMAGE_PLAYER -> "Deal " + n + " damage to players";
            case WIN_PVP -> "Win " + n + " PvP " + (amount == 1 ? "fight" : "fights");
            case MINE_BLOCK -> "Mine " + n + " " + (t.isEmpty() ? "blocks" : t);
            case SMELT -> "Smelt " + n + " " + (t.isEmpty() ? "items" : t);
            case COLLECT -> "Collect " + n + " " + (t.isEmpty() ? "items" : t);
            case CRAFT -> "Craft " + n + " " + (t.isEmpty() ? "items" : t);
            case TRAVEL -> "Travel " + n + " blocks";
            case EXPLODE_TNT -> "Explode TNT " + n + (amount == 1 ? " time" : " times");
            case OPEN_CHEST -> "Open " + n + (amount == 1 ? " chest" : " chests");
            case BREED -> "Breed " + n + " animals";
            case FISH -> "Catch " + n + " fish";
            case TRADE -> "Trade with villagers " + n + (amount == 1 ? " time" : " times");
            case PLACE_BLOCK -> "Place " + n + " blocks";
            case BREAK_BLOCK -> "Break " + n + " blocks";
        };
    }
}
