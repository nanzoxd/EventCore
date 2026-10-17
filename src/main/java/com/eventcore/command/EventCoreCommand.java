package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.event.GameEvent;
import com.eventcore.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/** /eventcore start|force|rig|stop|list|reload|gui|schedule|airdrop. Permission: eventcore.admin or eventcore.&lt;sub&gt;. */
public final class EventCoreCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "force", "rig", "stop", "list", "reload", "gui", "schedule", "airdrop");
    private final EventCorePlugin pl;

    public EventCoreCommand(EventCorePlugin pl) { this.pl = pl; }

    private boolean can(CommandSender s, String sub) { return s.hasPermission("eventcore.admin") || s.hasPermission("eventcore." + sub); }

    private void err(CommandSender s, String key, Object... kv) { s.sendMessage(pl.msg("errors." + key, kv)); }

    private Integer minutes(CommandSender s, String[] a, int i) {
        if (a.length <= i) return null;
        try { int m = Integer.parseInt(a[i]); if (m < 1) throw new NumberFormatException(); return m; }
        catch (NumberFormatException e) { err(s, "invalid-number", "input", a[i]); return -1; }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0 || !SUBS.contains(a[0].toLowerCase(Locale.ROOT))) { s.sendMessage(pl.msg("command.usage")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!can(s, sub)) { err(s, "no-permission"); return true; }
        switch (sub) {
            case "start", "force" -> {
                if (a.length < 2) { s.sendMessage(pl.msg("command.usage")); return true; }
                Integer m = minutes(s, a, 2);
                if (m != null && m < 0) return true;
                boolean force = sub.equals("force");
                if (!force && a[1].matches("\\d+")) {
                    int n = Math.max(1, Integer.parseInt(a[1]));
                    List<String> started = pl.events.roll(n, m);
                    if (started.isEmpty()) err(s, "nothing-to-start");
                    else s.sendMessage(pl.msg("command.rolled", "events", String.join(", ", started)));
                    return true;
                }
                String id = pl.events.find(a[1]);
                if (id == null) { err(s, "unknown-event", "input", a[1]); return true; }
                String e = pl.events.start(id, m, force);
                if (e != null) err(s, e, "event", pl.events.get(id).displayName());
                else s.sendMessage(pl.msg(force ? "command.force-started" : "command.started", "event", pl.events.get(id).displayName()));
            }
            case "rig" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) { pl.events.clearRigs(); s.sendMessage(pl.msg("command.rig-cleared")); return true; }
                if (a.length < 2) { s.sendMessage(pl.msg("command.rig-list", "events", pl.events.rigged().isEmpty() ? "-" : String.join(", ", pl.events.rigged()))); return true; }
                String id = pl.events.find(a[1]);
                if (id == null) { err(s, "unknown-event", "input", a[1]); return true; }
                Integer m = minutes(s, a, 2);
                if (m != null && m < 0) return true;
                pl.events.rig(id, m);
                s.sendMessage(pl.msg("command.rigged", "event", pl.events.get(id).displayName()));
            }
            case "stop" -> {
                if (a.length >= 2) {
                    String id = pl.events.find(a[1]);
                    if (id == null) { err(s, "unknown-event", "input", a[1]); return true; }
                    if (pl.events.stop(id, true)) s.sendMessage(pl.msg("command.stopped", "event", pl.events.get(id).displayName()));
                    else err(s, "not-running", "event", pl.events.get(id).displayName());
                } else {
                    int n = pl.events.stopAll(true);
                    s.sendMessage(pl.msg(n == 0 ? "command.nothing-running" : "command.stopped-all", "count", n));
                }
            }
            case "list" -> {
                s.sendMessage(pl.msg("command.list-header"));
                for (GameEvent ev : pl.events.registry().values()) {
                    ActiveEvent ae = pl.events.active(ev.id());
                    String state = ae != null ? "running " + com.eventcore.util.Util.mmss(ae.secondsLeft()) : ev.sec().getBoolean("enabled", true) ? "idle" : "disabled";
                    s.sendMessage(pl.msg("command.list-line", "event", ev.displayName(), "id", ev.id(), "state", state,
                            "aliases", String.join(", ", ev.sec().getStringList("aliases")), "minutes", ev.sec().getInt("duration-minutes", 10)));
                }
            }
            case "reload" -> pl.reloadAll(s);
            case "gui" -> {
                if (!(s instanceof Player p)) { err(s, "player-only"); return true; }
                pl.adminGui.openMain(p);
            }
            case "schedule" -> {
                List<String> lines = pl.scheduler.describe();
                s.sendMessage(pl.msg("command.schedule-header", "zone", pl.scheduler.zone().getId()));
                if (lines.isEmpty()) s.sendMessage(pl.msg("command.schedule-none"));
                for (String l : lines) {
                    String[] p = l.split("\\|");
                    s.sendMessage(pl.msg("command.schedule-line", "event", pl.events.get(p[0]).displayName(), "at", p[1], "in", p[2]));
                }
            }
            case "airdrop" -> {
                String what = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (!(s instanceof Player p)) { err(s, "player-only"); return true; }
                if (what.equals("loot")) {
                    if (a.length >= 3 && pl.airdrop.tierSec(a[2].toLowerCase(Locale.ROOT)) != null) pl.lootGui.openEntries(p, a[2].toLowerCase(Locale.ROOT), 0);
                    else pl.lootGui.openTiers(p);
                } else if (what.equals("spawn") && a.length >= 3) {
                    String tier = a[2].toLowerCase(Locale.ROOT);
                    if (!pl.airdrop.spawnAt(p, tier)) err(s, "unknown-tier", "input", a[2]);
                    else s.sendMessage(pl.msg("command.airdrop-spawned", "tier", tier));
                } else s.sendMessage(pl.msg("command.usage"));
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { for (String x : SUBS) if (can(s, x)) out.add(x); }
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "start" -> { out.addAll(pl.events.allNames()); out.addAll(List.of("1", "2", "3")); }
                case "force", "stop" -> out.addAll(pl.events.allNames());
                case "rig" -> { out.addAll(pl.events.allNames()); out.add("clear"); }
                case "airdrop" -> out.addAll(List.of("spawn", "loot"));
                default -> { }
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "start", "force", "rig" -> out.addAll(List.of("10", "30", "60"));
                case "airdrop" -> out.addAll(pl.airdrop.tierIds());
                default -> { }
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
