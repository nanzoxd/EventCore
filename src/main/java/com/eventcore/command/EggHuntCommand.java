package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EggHuntEvent;
import com.eventcore.util.Text;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /egghunt start [minutes] | stop | reset | leaderboard | reload | setarea [radius] | addspawn | clearspawns */
public final class EggHuntCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "stop", "reset", "leaderboard", "reload", "setarea", "addspawn", "clearspawns");
    private static final String PATH = "events.egg_hunt.settings.";
    private final EventCorePlugin pl;

    public EggHuntCommand(EventCorePlugin pl) { this.pl = pl; }

    private boolean can(CommandSender s, String sub) {
        if (sub.equals("leaderboard")) return s.hasPermission("eventcore.egghunt.leaderboard");
        return s.hasPermission("eventcore.admin") || s.hasPermission("eventcore.egghunt.admin") || s.hasPermission("eventcore.egghunt." + sub);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        EggHuntEvent ev = pl.eggHunt;
        if (a.length == 0 || !SUBS.contains(a[0].toLowerCase(Locale.ROOT))) { s.sendMessage(pl.msg("egg-hunt.usage")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!can(s, sub)) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        switch (sub) {
            case "start" -> {
                Integer minutes = null;
                if (a.length >= 2) {
                    try { minutes = Integer.parseInt(a[1]); if (minutes < 1) throw new NumberFormatException(); }
                    catch (NumberFormatException e) { s.sendMessage(pl.msg("errors.invalid-number", "input", a[1])); return true; }
                }
                if (ev.cleaning()) { s.sendMessage(pl.msg("egg-hunt.cleaning")); return true; }
                String err = pl.events.start(ev.id(), minutes, false);
                if (err != null) s.sendMessage(pl.msg("errors." + err, "event", ev.displayName()));
                else s.sendMessage(pl.msg("command.started", "event", ev.displayName()));
            }
            case "stop" -> {
                if (pl.events.stop(ev.id(), true)) s.sendMessage(pl.msg("command.stopped", "event", ev.displayName()));
                else s.sendMessage(pl.msg("errors.not-running", "event", ev.displayName()));
            }
            case "reset" -> { ev.reset(); s.sendMessage(pl.msg("egg-hunt.reset")); }
            case "leaderboard" -> {
                int size = Math.max(1, pl.cfg.events().getInt("events.egg_hunt.settings.leaderboard.size", 10));
                var board = ev.standings(size);
                if (board.isEmpty()) { s.sendMessage(pl.msg("egg-hunt.board-empty")); return true; }
                s.sendMessage(pl.msg(ev.showingLive() ? "egg-hunt.board-header" : "egg-hunt.board-header-last"));
                for (var e : board) s.sendMessage(pl.msg("egg-hunt.board-line", "rank", e.rank(), "player", e.name(), "eggs", e.eggs()));
            }
            case "reload" -> pl.reloadAll(s);
            case "setarea" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                int radius = pl.cfg.events().getInt(PATH + "area.radius", 100);
                if (a.length >= 2) {
                    try { radius = Math.max(5, Integer.parseInt(a[1])); }
                    catch (NumberFormatException e) { s.sendMessage(pl.msg("errors.invalid-number", "input", a[1])); return true; }
                }
                Location l = p.getLocation();
                pl.cfg.set("events", PATH + "area.world", l.getWorld().getName());
                pl.cfg.set("events", PATH + "area.center-x", (double) l.getBlockX());
                pl.cfg.set("events", PATH + "area.center-z", (double) l.getBlockZ());
                pl.cfg.set("events", PATH + "area.radius", radius);
                pl.afterConfigChange("events");
                s.sendMessage(pl.msg("egg-hunt.area-set", "world", l.getWorld().getName(), "x", l.getBlockX(), "z", l.getBlockZ(), "radius", radius));
            }
            case "addspawn" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                Location l = p.getLocation();
                List<String> list = new ArrayList<>(pl.cfg.events().getStringList(PATH + "eggs.fixed-spawns"));
                list.add(l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
                pl.cfg.set("events", PATH + "eggs.fixed-spawns", list);
                pl.afterConfigChange("events");
                s.sendMessage(pl.msg("egg-hunt.spawn-added", "count", list.size(), "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ()));
            }
            case "clearspawns" -> {
                pl.cfg.set("events", PATH + "eggs.fixed-spawns", new ArrayList<String>());
                pl.afterConfigChange("events");
                s.sendMessage(pl.msg("egg-hunt.spawns-cleared"));
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { for (String x : SUBS) if (can(s, x)) out.add(x); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("start")) out.addAll(List.of("10", "20", "30"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("setarea")) out.addAll(List.of("50", "100", "200"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
