package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * %eventcore_quest_points% %eventcore_quest_completed% %eventcore_quest_rank% %eventcore_quest_active% %eventcore_quest_total%
 * %eventcore_quest_percent% %eventcore_quest_timeleft% %eventcore_quest_status%
 * %eventcore_quest_top_N_name% %eventcore_quest_top_N_points% %eventcore_quest_top_N_completed%   (N = 1-5)
 */
public final class QuestPapi extends PlaceholderExpansion {
    private final EventCorePlugin pl;
    private final QuestManager qm;

    public QuestPapi(EventCorePlugin pl, QuestManager qm) { this.pl = pl; this.qm = qm; }

    @Override public String getIdentifier() { return "eventcore"; }
    @Override public String getAuthor() { return "EventCore"; }
    @Override public String getVersion() { return pl.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!params.startsWith("quest_")) return null;
        String k = params.substring(6);
        if (k.startsWith("top_")) {
            String[] a = k.split("_");
            if (a.length != 3) return null;
            int n;
            try { n = Integer.parseInt(a[1]); } catch (NumberFormatException e) { return null; }
            List<PlayerQuestData> top = qm.top(QuestManager.TOP);
            if (n < 1 || n > QuestManager.TOP) return null;
            if (n > top.size()) return a[2].equals("name") ? "-" : "0";
            PlayerQuestData d = top.get(n - 1);
            return switch (a[2]) { case "name" -> d.name; case "points" -> String.valueOf(d.points); case "completed" -> String.valueOf(d.completed); default -> null; };
        }
        switch (k) {
            case "timeleft": return qm.timeLeft();
            case "total": return String.valueOf(qm.reg.size());
            case "status": return !qm.running ? "not running" : qm.paused ? "paused" : "running";
            default: break;
        }
        if (player == null) return "";
        PlayerQuestData d = qm.dataOf(player.getUniqueId());
        if (d == null) return switch (k) { case "rank" -> "-"; case "points", "completed", "active", "percent" -> "0"; default -> null; };
        return switch (k) {
            case "points" -> String.valueOf(d.points);
            case "completed" -> String.valueOf(d.completed);
            case "active" -> String.valueOf(d.activeCount());
            case "percent" -> String.valueOf(qm.reg.size() == 0 ? 0 : 100 * d.completed / qm.reg.size());
            case "rank" -> { int r = qm.rankOf(d.uuid); yield r == 0 ? "-" : String.valueOf(r); }
            default -> null;
        };
    }
}
