package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/** /playerhunt [player ...] | stop [player|all] | list   (permission eventcore.playerhunt) */
public final class HuntCommand implements TabExecutor {
    private final EventCorePlugin pl;

    public HuntCommand(EventCorePlugin pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("eventcore.playerhunt")) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        if (!pl.hunt.enabled()) { s.sendMessage(pl.msg("hunt.disabled")); return true; }
        if (a.length == 0) { s.sendMessage(pl.msg("hunt.usage")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            var views = pl.hunt.views();
            if (views.isEmpty()) { s.sendMessage(pl.msg("hunt.nothing")); return true; }
            s.sendMessage(pl.msg("hunt.list-header", "count", views.size()));
            for (var v : views)
                s.sendMessage(pl.msgMap(v.online() ? "hunt.list-line" : "hunt.list-line-offline",
                        com.eventcore.util.Text.ph("player", v.name(), "world", v.world(), "x", v.x(), "y", v.y(), "z", v.z())));
            return true;
        }
        if (sub.equals("stop")) {
            if (a.length == 1 || a[1].equalsIgnoreCase("all")) {
                int n = pl.hunt.stopAll();
                s.sendMessage(pl.msg(n == 0 ? "hunt.nothing" : "hunt.stopped-all", "count", n));
                return true;
            }
            for (int i = 1; i < a.length; i++) {
                String n = pl.hunt.stop(a[i]);
                s.sendMessage(n != null ? pl.msg("hunt.stopped", "player", n) : pl.msg("hunt.not-hunted", "input", a[i]));
            }
            return true;
        }
        List<Player> targets = new ArrayList<>();
        for (String name : a) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) { s.sendMessage(pl.msg("hunt.not-online", "input", name)); continue; }
            if (!targets.contains(p)) targets.add(p);
        }
        if (targets.isEmpty()) return true;
        List<String> added = pl.hunt.start(targets);
        if (added.isEmpty()) s.sendMessage(pl.msg("hunt.nothing-added", "max", pl.hunt.max()));
        else s.sendMessage(pl.msg("hunt.started", "players", String.join(", ", added)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("eventcore.playerhunt")) return out;
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length >= 1 && a[0].equalsIgnoreCase("stop")) { out.addAll(pl.hunt.names()); if (a.length == 2) out.add("all"); }
        else if (a.length >= 1 && a[0].equalsIgnoreCase("list")) { }
        else {
            if (a.length == 1) { out.add("stop"); out.add("list"); }
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
