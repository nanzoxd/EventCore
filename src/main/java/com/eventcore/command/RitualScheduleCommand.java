package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.ritual.RitualScheduler;
import com.eventcore.util.Util;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/** /ritual [schedule | list | cancel <id>]   (permission eventcore.ritual) */
public final class RitualScheduleCommand implements TabExecutor {
    private final EventCorePlugin pl;

    public RitualScheduleCommand(EventCorePlugin pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("eventcore.ritual")) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "schedule" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                pl.ritualScheduleGui.open(p);
            }
            case "list" -> pl.ritualScheduleGui.list(s);
            case "cancel" -> {
                if (a.length < 2) { s.sendMessage(pl.msg("ritual-schedule.usage")); return true; }
                RitualScheduler.Entry e = pl.ritualSchedule.cancel(a[1]);
                if (e == null) { s.sendMessage(pl.msg("ritual-schedule.not-found", "input", a[1])); return true; }
                if (s instanceof Player p) { Util.give(p, e.item); s.sendMessage(pl.msg("ritual-schedule.cancelled", "id", e.id)); }
                else { pl.ritualSchedule.add0(e); s.sendMessage(pl.msg("ritual-schedule.player-only-cancel")); }
            }
            default -> {
                if (s instanceof Player p) pl.ritualGui.openMain(p); else s.sendMessage(pl.msg("ritual-schedule.usage"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("eventcore.ritual")) return out;
        if (a.length == 1) out.addAll(List.of("schedule", "list", "cancel"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("cancel")) out.addAll(pl.ritualSchedule.ids());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
