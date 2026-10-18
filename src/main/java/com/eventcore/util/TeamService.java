package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/**
 * Scoreboard teams used by Glow (palette-coloured outlines) and Anonymous (hidden nametags).
 * Team names are ec_<colorIndex|x><h|s>. Teams are applied on EVERY scoreboard in use (the main one and any
 * per-player scoreboard another plugin gave to players), otherwise nametags stay visible for those players.
 * A player's previous team is remembered and restored. Scoreboard teams on Folia are limited by the platform.
 */
public final class TeamService {
    private final EventCorePlugin pl;
    private final Map<Scoreboard, Map<UUID, String>> previous = new WeakHashMap<>();
    private boolean warned;

    public TeamService(EventCorePlugin pl) { this.pl = pl; }

    public void refreshLater() { pl.sched.global(this::refresh); }

    private List<Scoreboard> boards() {
        List<Scoreboard> out = new ArrayList<>();
        try { out.add(Bukkit.getScoreboardManager().getMainScoreboard()); }
        catch (Throwable t) {
            if (!warned) { warned = true; pl.getLogger().warning("Scoreboard teams unavailable on this server: " + t.getMessage()); }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Scoreboard s = p.getScoreboard();
                boolean known = false;
                for (Scoreboard o : out) if (o == s) { known = true; break; }
                if (s != null && !known) out.add(s);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** Recomputes team membership for every online player on every scoreboard. */
    public void refresh() {
        boolean glow = pl.events.isActive("glow"), anon = pl.events.isActive("anonymous");
        List<String> pal = pl.text.palette();
        for (Scoreboard sb : boards()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    if (!glow && !anon) { release(sb, p); continue; }
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
                    if (cur != null && !cur.getName().startsWith("ec_"))
                        previous.computeIfAbsent(sb, k -> new HashMap<>()).put(p.getUniqueId(), cur.getName());
                    t.addEntry(p.getName());
                } catch (Throwable ex) {
                    if (!warned) { warned = true; pl.getLogger().warning("Team update failed: " + ex); }
                }
            }
        }
    }

    private void release(Scoreboard sb, Player p) {
        Team cur = sb.getEntryTeam(p.getName());
        if (cur != null && cur.getName().startsWith("ec_")) cur.removeEntry(p.getName());
        Map<UUID, String> m = previous.get(sb);
        String prev = m == null ? null : m.remove(p.getUniqueId());
        if (prev != null) {
            Team t = sb.getTeam(prev);
            if (t != null) t.addEntry(p.getName());
        }
    }

    public void release(Player p) {
        for (Scoreboard sb : boards()) { try { release(sb, p); } catch (Throwable ignored) { } }
    }

    public void shutdown() {
        for (Scoreboard sb : boards()) {
            try {
                for (Player p : Bukkit.getOnlinePlayers()) release(sb, p);
                for (Team t : new ArrayList<>(sb.getTeams())) if (t.getName().startsWith("ec_")) t.unregister();
            } catch (Throwable ignored) { }
        }
    }
}
