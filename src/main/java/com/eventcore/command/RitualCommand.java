package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /ritualcreator (aliases /rcreator, /creator). */
public final class RitualCommand implements CommandExecutor {
    private final EventCorePlugin pl;

    public RitualCommand(EventCorePlugin pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
        if (!p.hasPermission("eventcore.ritual")) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        pl.ritualGui.openMain(p);
        return true;
    }
}
