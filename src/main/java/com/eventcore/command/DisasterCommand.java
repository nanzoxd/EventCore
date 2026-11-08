package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.NaturalDisasterEvent;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /disaster start [minutes] | stop | reset | setcenter | setarena | setexit | status | reload | join | leave */
public final class DisasterCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "stop", "reset", "setcenter", "setarena", "setexit", "status", "reload", "join", "leave");
    private static final String PATH = "events.natural_disaster.settings.";
    private final EventCorePlugin pl;

    public DisasterCommand(EventCorePlugin pl) { this.pl = pl; }

    private boolean can(CommandSender s, String sub) {
        if (sub.equals("join") || sub.equals("leave") || sub.equals("status")) return s.hasPermission("eventcore.disaster." + sub);
        return s.hasPermission("eventcore.admin") || s.hasPermission("eventcore.disaster.admin") || s.hasPermission("eventcore.disaster." + sub);
    }

    private void set(String path, Object v) { pl.cfg.set("events", PATH + path, v); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        NaturalDisasterEvent ev = pl.disaster;
        if (a.length == 0 || !SUBS.contains(a[0].toLowerCase(Locale.ROOT))) { s.sendMessage(pl.msg("disaster.usage")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!can(s, sub)) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        switch (sub) {
            case "start" -> {
                Integer minutes = null;
                if (a.length >= 2) {
                    try { minutes = Math.min(ev.maxMinutes(), Integer.parseInt(a[1])); if (minutes < 1) throw new NumberFormatException(); }
                    catch (NumberFormatException e) { s.sendMessage(pl.msg("errors.invalid-number", "input", a[1])); return true; }
                }
                String pre = ev.preflight();
                if (pre != null) { s.sendMessage(pl.msg(pre)); return true; }
                String err = pl.events.start(ev.id(), minutes, false);
                if (err != null) s.sendMessage(pl.msg("errors." + err, "event", ev.displayName()));
                else s.sendMessage(pl.msg("command.started", "event", ev.displayName()));
            }
            case "stop" -> {
                if (pl.events.stop(ev.id(), true)) s.sendMessage(pl.msg("command.stopped", "event", ev.displayName()));
                else s.sendMessage(pl.msg("errors.not-running", "event", ev.displayName()));
            }
            case "reset" -> {
                pl.events.stop(ev.id(), false);
                ev.sweepAll();
                s.sendMessage(pl.msg("disaster.reset"));
            }
            case "status" -> s.sendMessage(pl.msgMap(ev.running() ? "disaster.status-running" : "disaster.status-idle", ev.statusPlaceholders()));
            case "reload" -> pl.reloadAll(s);
            case "join" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                if (ev.running()) s.sendMessage(pl.msg("disaster.join-running"));
                else if (!ev.optIn()) s.sendMessage(pl.msg("disaster.join-not-needed"));
                else s.sendMessage(pl.msg(ev.signUp(p) ? "disaster.joined" : "disaster.already-joined"));
            }
            case "leave" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                s.sendMessage(pl.msg(ev.unSignUp(p.getUniqueId()) ? "disaster.left" : "disaster.not-joined"));
            }
            case "setcenter", "setarena", "setexit" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                Location l = p.getLocation();
                String w = l.getWorld().getName();
                if (sub.equals("setcenter")) {
                    set("center.world", w);
                    set("center.x", l.getBlockX() + 0.5);
                    set("center.z", l.getBlockZ() + 0.5);
                } else if (sub.equals("setarena")) {
                    set("arena.spawn.enabled", true);
                    set("arena.spawn.x", l.getBlockX() + 0.5);
                    set("arena.spawn.y", l.getY());
                    set("arena.spawn.z", l.getBlockZ() + 0.5);
                } else {
                    set("exit.world", w);
                    set("exit.x", l.getX()); set("exit.y", l.getY()); set("exit.z", l.getZ());
                    set("exit.yaw", (double) l.getYaw()); set("exit.pitch", (double) l.getPitch());
                }
                pl.afterConfigChange("events");
                s.sendMessage(pl.msg("disaster." + sub, "world", w, "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ()));
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { for (String x : SUBS) if (can(s, x)) out.add(x); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("start")) out.addAll(List.of("30", "60"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
