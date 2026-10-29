package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.bounty.BountyManager;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/** /bountyadd &lt;player&gt; (opens the GUI) | /bountystop [player|all] | /bountylist   (permission eventcore.bounty) */
public final class BountyCommand implements TabExecutor {
    private final EventCorePlugin pl;

    public BountyCommand(EventCorePlugin pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("eventcore.bounty")) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "bountyadd" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                if (!pl.bounty.enabled()) { s.sendMessage(pl.msg("bounty.error-disabled")); return true; }
                if (a.length < 1) { s.sendMessage(pl.msg("bounty.usage")); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { s.sendMessage(pl.msg("bounty.error-not-online", "input", a[0])); return true; }
                if (pl.bounty.hasBounty(t.getUniqueId())) { s.sendMessage(pl.msg("bounty.error-already", "player", t.getName())); return true; }
                if (pl.bounty.count() >= pl.bounty.max()) { s.sendMessage(pl.msg("bounty.error-limit", "max", pl.bounty.max())); return true; }
                pl.bountyGui.open(p, t);
            }
            case "bountystop" -> {
                if (a.length == 0 || a[0].equalsIgnoreCase("all")) {
                    int n = pl.bounty.cancelAll();
                    s.sendMessage(pl.msg(n == 0 ? "bounty.nothing" : "bounty.stopped-all", "count", n));
                } else {
                    String n = pl.bounty.cancel(a[0]);
                    s.sendMessage(n != null ? pl.msg("bounty.stopped", "player", n) : pl.msg("bounty.not-found", "input", a[0]));
                }
            }
            default -> {
                List<BountyManager.View> views = pl.bounty.views();
                if (views.isEmpty()) { s.sendMessage(pl.msg("bounty.nothing")); return true; }
                s.sendMessage(pl.msg("bounty.list-header", "count", views.size()));
                for (BountyManager.View v : views)
                    s.sendMessage(pl.text.render(pl.fx.msg(pl.cfg.messages(), "bounty.list-line").tpl(),
                            Text.ph("player", v.targetName(), "reward", v.rewardName(), "time", Util.mmss(v.remaining()),
                                    "state", v.state() == BountyManager.State.ACTIVE ? "hunted" : "offline " + Util.mmss(v.grace()), "creator", v.creator())));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("eventcore.bounty") || a.length != 1) return out;
        String name = c.getName().toLowerCase(Locale.ROOT);
        if (name.equals("bountyadd")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (name.equals("bountystop")) { for (BountyManager.View v : pl.bounty.views()) out.add(v.targetName()); out.add("all"); }
        String last = a[0].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
