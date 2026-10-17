package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scoreboard teams used by Glow (palette-coloured outlines) and Anonymous (hidden nametags).
 * Team names are ec_&lt;colorIndex|x&gt;&lt;h|s&gt;. A player's previous team is remembered and restored.
 * Scoreboard teams on Folia are limited by the platform; failures are caught and logged once.
 */
public final class TeamService {
    private final EventCorePlugin pl;
    private final Map<UUID, String> previous = new HashMap<>();
    private boolean warned;

    public TeamService(EventCorePlugin pl) { this.pl = pl; }

    public void refreshLater() { pl.sched.global(this::refresh); }

    private Scoreboard board() {
        try { return Bukkit.getScoreboardManager().getMainScoreboard(); }
        catch (Throwable t) {
            if (!warned) { warned = true; pl.getLogger().warning("Scoreboard teams unavailable on this server: " + t.getMessage()); }
            return null;
        }
    }

    /** Recomputes team membership for every online player from the active events. */
    public void refresh() {
        Scoreboard sb = board();
        if (sb == null) return;
        boolean glow = pl.events.isActive("glow"), anon = pl.events.isActive("anonymous");
        List<String> pal = pl.text.palette();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!glow && !anon) { release(p); continue; }
            int idx = glow ? Math.abs(p.getUniqueId().hashCode()) % pal.size() : -1;
            String name = "ec_" + (idx < 0 ? "x" : String.valueOf(idx)) + (anon ? "h" : "s");
            Team t = sb.getTeam(name);
            if (t == null) t = sb.registerNewTeam(name);
            if (idx >= 0) {
                TextColor tc = TextColor.fromHexString(pal.get(idx));
                if (tc != null) t.color(NamedTextColor.nearestTo(tc));
            }
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, anon ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
            Team cur = sb.getEntryTeam(p.getName());
            if (cur != null && cur.getName().equals(name)) continue;
            if (cur != null && !cur.getName().startsWith("ec_")) previous.put(p.getUniqueId(), cur.getName());
            t.addEntry(p.getName());
        }
    }

    public void release(Player p) {
        Scoreboard sb = board();
        if (sb == null) return;
        Team cur = sb.getEntryTeam(p.getName());
        if (cur != null && cur.getName().startsWith("ec_")) cur.removeEntry(p.getName());
        String prev = previous.remove(p.getUniqueId());
        if (prev != null) {
            Team t = sb.getTeam(prev);
            if (t != null) t.addEntry(p.getName());
        }
    }

    public void shutdown() {
        Scoreboard sb = board();
        if (sb == null) return;
        try {
            for (Player p : Bukkit.getOnlinePlayers()) release(p);
            for (Team t : sb.getTeams()) if (t.getName().startsWith("ec_")) t.unregister();
        } catch (Throwable ignored) { }
    }
}
