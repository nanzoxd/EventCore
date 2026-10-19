package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager;
import com.eventcore.altar.AltarManager.Altar;
import com.eventcore.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /altar spawn|remove|list|recipes|bind|stop|reload */
public final class AltarCommand implements CommandExecutor, TabCompleter {
    private final EventCorePlugin pl;

    public AltarCommand(EventCorePlugin pl) { this.pl = pl; }

    private AltarManager am() { return pl.altar; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("eventcore.altar")) { s.sendMessage(pl.msg("errors.no-permission")); return true; }
        if (a.length == 0) { s.sendMessage(am().m("usage", Map.of())); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                String preset = a.length > 1 ? a[1] : "default";
                Altar al = am().spawn(p, preset);
                if (al == null) s.sendMessage(am().m("unknown-preset", Text.ph("input", preset)));
                else s.sendMessage(am().m("spawned", Text.ph("id", al.id, "preset", al.preset)));
            }
            case "remove" -> {
                Altar al = null;
                if (a.length > 1) {
                    try { al = am().altar(Integer.parseInt(a[1])); } catch (NumberFormatException ignored) { }
                } else if (s instanceof Player p) al = am().nearest(p.getLocation(), 6);
                if (al == null) { s.sendMessage(am().m("no-altar", Map.of())); return true; }
                int id = al.id;
                am().remove(al);
                s.sendMessage(am().m("removed", Text.ph("id", id)));
            }
            case "list" -> {
                s.sendMessage(am().m("list-header", Map.of()));
                for (Altar al : am().altars())
                    s.sendMessage(am().m("list-line", Text.ph("id", al.id, "preset", al.preset, "world", al.center.getWorld().getName(),
                            "x", al.center.getBlockX(), "y", al.center.getBlockY(), "z", al.center.getBlockZ(),
                            "state", al.busy() ? "ritual running" : "idle")));
            }
            case "recipes", "recipe", "editor", "gui" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return true; }
                pl.altarGui.openList(p);
            }
            case "bind" -> {
                if (a.length < 3) { s.sendMessage(am().m("usage", Map.of())); return true; }
                boolean none = a[2].equalsIgnoreCase("none");
                if (!none && am().recipe(a[2]) == null) { s.sendMessage(am().m("unknown-recipe", Text.ph("input", a[2]))); return true; }
                if (!am().bind(a[1], none ? "" : a[2])) s.sendMessage(am().m("unknown-preset", Text.ph("input", a[1])));
                else s.sendMessage(am().m("bound", Text.ph("preset", a[1], "recipe", none ? "any recipe" : a[2])));
            }
            case "stop" -> {
                int n = 0;
                if (a.length > 1 && !a[1].equalsIgnoreCase("all")) {
                    try { Altar al = am().altar(Integer.parseInt(a[1])); if (al != null && am().stopRitual(al, true)) n = 1; }
                    catch (NumberFormatException ignored) { }
                } else n = am().stopAll();
                s.sendMessage(am().m("stopped", Text.ph("count", n)));
            }
            case "reload" -> { am().reload(); s.sendMessage(am().m("reloaded", Map.of())); }
            default -> s.sendMessage(am().m("usage", Map.of()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("eventcore.altar")) return out;
        if (a.length == 1) out.addAll(List.of("spawn", "remove", "list", "recipes", "bind", "stop", "reload"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("spawn") || a[0].equalsIgnoreCase("bind"))) out.addAll(am().presetNames());
        else if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("stop"))) {
            for (Altar al : am().altars()) out.add(String.valueOf(al.id));
            if (a[0].equalsIgnoreCase("stop")) out.add("all");
        } else if (a.length == 3 && a[0].equalsIgnoreCase("bind")) {
            out.add("none");
            for (AltarManager.Recipe r : am().recipes()) out.add(r.id);
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
