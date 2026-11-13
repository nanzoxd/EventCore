package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /quest for everybody; every admin sub command needs eventcore.quest.admin. */
public final class QuestCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER = List.of("top", "claim", "display", "help");
    private static final List<String> ADMIN = List.of("start", "stop", "pause", "resume", "reload", "give", "reset", "add", "remove", "leaderboard", "info", "admin");

    private final EventCorePlugin pl;
    private final QuestManager qm;

    public QuestCommand(EventCorePlugin pl, QuestManager qm) { this.pl = pl; this.qm = qm; }

    private boolean admin(CommandSender s) { return s.hasPermission("eventcore.quest.admin"); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {                                                    // /quest: the main GUI, for everybody
            if (!(s instanceof Player p)) { s.sendMessage(qm.msg("players-only")); return true; }
            qm.questGuiOpen(p);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "top" -> { for (Component c : qm.boardLines(s instanceof Player p ? p.getUniqueId() : null)) s.sendMessage(c); }
            case "claim" -> { if (s instanceof Player p) qm.claim(p); else s.sendMessage(qm.msg("players-only")); }
            case "display" -> display(s, a);
            case "help" -> { s.sendMessage(qm.raw("help-player")); if (admin(s)) s.sendMessage(qm.raw("help-admin")); }
            default -> {
                if (!ADMIN.contains(sub)) { s.sendMessage(qm.msg("unknown-sub")); return true; }
                if (!admin(s)) { s.sendMessage(qm.msg("no-permission")); return true; }
                adminCmd(s, sub, a);
            }
        }
        return true;
    }

    private void display(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(qm.msg("players-only")); return; }
        String m = a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "";
        if (!List.of("ACTIONBAR", "BOSSBAR", "BOTH", "OFF").contains(m)) { s.sendMessage(qm.msg("usage-display")); return; }
        PlayerQuestData d = qm.dataFor(p);
        d.display = m; d.dirty = true;
        s.sendMessage(qm.msg("display-set", "mode", m.toLowerCase()));
    }

    private void adminCmd(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "start" -> {
                int minutes = pl.events.get("quest") == null ? 60 : pl.events.get("quest").sec().getInt("duration-minutes", 60);
                if (a.length > 1) { try { minutes = Math.max(1, Integer.parseInt(a[1])); } catch (NumberFormatException e) { s.sendMessage(qm.msg("usage-start")); return; } }
                if (qm.running) { s.sendMessage(qm.msg("already-running")); return; }
                if (s instanceof Player p) pl.rewardGui.open(p, RewardSetupGui.Mode.START, minutes);   // the event does NOT start yet: rewards first
                else {                                                           // console: start with the saved rewards
                    String err = pl.events.start("quest", minutes, true);
                    s.sendMessage(err == null ? qm.msg("setup-started", "minutes", minutes) : qm.msg("start-failed", "reason", err));
                }
            }
            case "stop" -> { if (!qm.running) s.sendMessage(qm.msg("not-running")); else { pl.events.stop("quest", true); s.sendMessage(qm.msg("stopped")); } }
            case "pause" -> s.sendMessage(qm.pause() ? qm.msg("paused") : qm.msg("cannot-pause"));
            case "resume" -> s.sendMessage(qm.resume() ? qm.msg("resumed") : qm.msg("cannot-resume"));
            case "reload" -> { pl.reloadAll(s); }
            case "give" -> {
                if (a.length < 3) { s.sendMessage(qm.msg("usage-give")); return; }
                Quest q = qm.reg.get(a[2]);
                OfflinePlayer t = target(a[1]);
                if (q == null) { s.sendMessage(qm.msg("unknown-quest", "quest", a[2])); return; }
                if (t == null) { s.sendMessage(qm.msg("unknown-player", "player", a[1])); return; }
                s.sendMessage(qm.give(t.getUniqueId(), t.getName(), q) ? qm.msg("give-done", "player", t.getName(), "title", q.title) : qm.msg("already-completed"));
            }
            case "reset" -> {
                if (a.length < 2) { s.sendMessage(qm.msg("usage-reset")); return; }
                OfflinePlayer t = target(a[1]);
                if (t == null) { s.sendMessage(qm.msg("unknown-player", "player", a[1])); return; }
                qm.resetPlayer(t.getUniqueId(), t.getName());
                s.sendMessage(qm.msg("reset-done", "player", t.getName()));
            }
            case "add", "remove" -> {
                if (a.length < 3) { s.sendMessage(qm.msg("usage-points", "sub", sub)); return; }
                OfflinePlayer t = target(a[1]);
                if (t == null) { s.sendMessage(qm.msg("unknown-player", "player", a[1])); return; }
                int n;
                try { n = Integer.parseInt(a[2]); } catch (NumberFormatException e) { s.sendMessage(qm.msg("usage-points", "sub", sub)); return; }
                if (n <= 0) { s.sendMessage(qm.msg("usage-points", "sub", sub)); return; }
                qm.addPoints(t.getUniqueId(), t.getName(), sub.equals("add") ? n : -n);
                s.sendMessage(qm.msg(sub.equals("add") ? "points-added" : "points-removed", "player", t.getName(), "points", n));
            }
            case "leaderboard" -> { for (Component c : qm.boardLines(s instanceof Player p ? p.getUniqueId() : null)) s.sendMessage(c); }
            case "info" -> {
                s.sendMessage(qm.raw("info-head"));
                s.sendMessage(qm.raw("info-line", "label", "Status", "value", !qm.running ? "not running" : qm.paused ? "paused" : "running"));
                s.sendMessage(qm.raw("info-line", "label", "Time left", "value", qm.timeLeft()));
                s.sendMessage(qm.raw("info-line", "label", "Quests", "value", qm.reg.size()));
                s.sendMessage(qm.raw("info-line", "label", "Players with points", "value", qm.participants()));
                s.sendMessage(qm.raw("info-line", "label", "Quests completed", "value", qm.totalCompleted()));
                s.sendMessage(qm.raw("info-line", "label", "Reward places set", "value", qm.rewards().size() + " / " + QuestManager.TOP));
            }
            case "admin" -> { if (s instanceof Player p) pl.sched.entity(p, () -> pl.questAdmin.open(p)); else s.sendMessage(qm.msg("players-only")); }
            default -> { }
        }
    }

    /** Online or offline player by name (offline players only if they have played here before). */
    private OfflinePlayer target(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null && (off.hasPlayedBefore() || qm.dataOf(off.getUniqueId()) != null)) return off;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(PLAYER);
            if (admin(s)) out.addAll(ADMIN);
        } else if (a.length == 2 && admin(s)) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give", "reset", "add", "remove" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "start" -> out.addAll(List.of("30", "60", "120"));
                default -> { }
            }
            if (a[0].equalsIgnoreCase("display")) out.addAll(List.of("actionbar", "bossbar", "both", "off"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("display")) out.addAll(List.of("actionbar", "bossbar", "both", "off"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give") && admin(s)) qm.reg.all().forEach(q -> out.add(q.id));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
