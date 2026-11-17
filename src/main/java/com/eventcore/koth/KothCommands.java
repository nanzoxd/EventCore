package com.eventcore.koth;

import com.eventcore.EventCorePlugin;
import com.eventcore.gui.dialog.DialogSupport;
import com.eventcore.gui.dialog.SettingsDialog;
import com.eventcore.gui.dialog.SettingsDialog.*;
import com.eventcore.koth.KothData.Arena;
import com.eventcore.koth.KothData.Flag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.*;

/**
 * /eventcore koth ..., /tpkoth and /eventcore dialogdemo. The commands are caught here so EventCoreCommand and plugin.yml need no change.
 * Admin commands need eventcore.koth (ops have it). Start and stop the event itself with /event start koth and /event stop koth.
 */
public final class KothCommands implements Listener {
    private final EventCorePlugin pl;
    private final KothEvent ev;
    private final KothData d;
    private final SettingsDialog settings;

    public KothCommands(EventCorePlugin pl, KothEvent ev) {
        this.pl = pl; this.ev = ev; this.d = ev.data;
        this.settings = new SettingsDialog(pl, "King of the Hill", this::categories, d::save);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayer(PlayerCommandPreprocessEvent e) { if (route(e.getPlayer(), e.getMessage())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsole(ServerCommandEvent e) { if (route(e.getSender(), "/" + e.getCommand())) e.setCancelled(true); }

    private boolean route(CommandSender s, String raw) {
        String[] p = raw.trim().replaceFirst("^/", "").split("\\s+");
        String c = p[0].toLowerCase(Locale.ROOT).replaceFirst("^eventcore:", "");
        if (c.equals("tpkoth")) { player(s, pp -> { String a = p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : "menu"; if (a.equals("join")) ev.join(pp); else if (a.equals("leave")) ev.leave(pp); else ev.openJoinMenu(pp); }); return true; }
        if (!(c.equals("eventcore") || c.equals("ec")) || p.length < 2) return false;
        if (p[1].equalsIgnoreCase("dialogdemo")) { player(s, pp -> { if (!DialogSupport.enabled(pl)) ev.say(pp, "<#EE2424>dialogs are off or unsupported (gui.dialogs / Paper 1.21.6+)."); else SettingsDialog.example(pl).open(pp); }); return true; }
        if (!p[1].equalsIgnoreCase("koth")) return false;
        if (!s.hasPermission("eventcore.koth")) { ev.say(s, "<#EE2424>you do not have permission."); return true; }
        admin(s, Arrays.copyOfRange(p, 2, p.length));
        return true;
    }

    private void player(CommandSender s, java.util.function.Consumer<Player> run) {
        if (s instanceof Player p) run.accept(p); else ev.say(s, "<#EE2424>players only.");
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT), arg = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "menu" -> player(s, p -> { if (DialogSupport.enabled(pl)) settings.open(p); else ev.say(p, "dialogs are off: use /eventcore koth help"); });
            case "builds" -> ev.say(s, "builds (" + (KothBuilds.engine() == null ? "<#EE2424>no WorldEdit/FAWE" : KothBuilds.engine()) + "): <#FFD54A>" + String.join(", ", ev.builds.ids()));
            case "build" -> player(s, p -> ev.placeBuild(p, arg.isEmpty() ? Optional.ofNullable(d.selectedBuild).orElse("") : arg, a.length > 2 ? a[2] : null));
            case "remove" -> ev.removeArena(s, arg);
            case "select" -> { if (d.arenas.containsKey(arg)) { d.active = arg; d.save(); ev.say(s, "active arena: <#FFD54A>" + arg); } else ev.say(s, "<#EE2424>unknown arena."); }
            case "list" -> ev.say(s, d.arenas.isEmpty() ? "no arenas yet." : "arenas: <#FFD54A>" + String.join(", ", d.arenas.keySet()) + "</#FFD54A> (active: " + d.active + ")");
            case "pos1" -> player(s, p -> ev.setPos(p, 1));
            case "pos2" -> player(s, p -> ev.setPos(p, 2));
            case "area" -> player(s, ev::useSelection);
            case "setspawn" -> player(s, p -> ev.setSpawn(p, false));
            case "setexit" -> player(s, p -> ev.setSpawn(p, true));
            case "tp" -> player(s, p -> { Arena ar = arg.isEmpty() ? d.active() : d.arenas.get(arg); World w = ar == null ? null : d.world(ar);
                if (w == null) ev.say(p, "<#EE2424>unknown arena."); else p.teleportAsync(new Location(w, (ar.min[0] + ar.max[0]) / 2.0, ar.max[1] + 2, (ar.min[2] + ar.max[2]) / 2.0)); });
            default -> ev.say(s, "/eventcore koth <menu | builds | build [build] [name] | remove <arena> | select <arena> | list | pos1 | pos2 | area | setspawn | setexit | tp [arena]>  -  start with /event start koth");
        }
    }

    // ------------------------------------------------------------------ the settings dialog
    private List<Category> categories() {
        List<Entry> flags = new ArrayList<>();
        for (Flag f : Flag.values()) flags.add(new Toggle(f.label, f.tip, () -> d.flag(f), v -> d.flag(f, v)));
        flags.add(new Cycle("Breaking blocks", "NONE = nothing, PLACED_ONLY = only blocks players placed, ALL = anything outside the build", KothData.BREAK_MODES, d::breakMode, d::breakMode));

        List<Entry> rules = List.of(
                new Slider("Points to win (seconds on the hill)", 30, 900, 10, () -> d.num("points-to-win", 120), v -> d.setNum("points-to-win", v)),
                new Slider("PvP grace at the start (seconds)", 0, 120, 5, () -> d.num("grace-seconds", 30), v -> d.setNum("grace-seconds", v)),
                new Slider("Join window (minutes, 0 = always)", 0, 60, 1, () -> d.num("join-until-minutes", 10), v -> d.setNum("join-until-minutes", v)),
                new Slider("Spawn spread radius (blocks)", 0, 30, 1, () -> d.num("spread-radius", 4), v -> d.setNum("spread-radius", v)),
                new Slider("Zone margin around the build (blocks)", 0, 100, 5, () -> d.num("zone-margin", 30), v -> d.setNum("zone-margin", v)),
                new Toggle("Contested hill still scores", "Everybody on the hill scores while it is contested", () -> d.bool("contested-gives-points", false), v -> d.setBool("contested-gives-points", v)),
                new Toggle("Heal players on join", null, () -> d.bool("heal-on-start", true), v -> d.setBool("heal-on-start", v)),
                new Toggle("Best score wins when time runs out", null, () -> d.bool("timeout-winner", true), v -> d.setBool("timeout-winner", v)));

        List<String> ids = ev.builds.ids(); if (ids.isEmpty()) ids = List.of("none");
        if (d.selectedBuild == null || !ids.contains(d.selectedBuild)) d.selectedBuild = ids.get(0);
        List<Entry> arena = new ArrayList<>();
        arena.add(new Cycle("Build to place", "The schematic the next paste uses", ids, () -> d.selectedBuild, v -> d.selectedBuild = v));
        if (!d.arenas.isEmpty()) arena.add(new Cycle("Active arena", "The arena the event uses", new ArrayList<>(d.arenas.keySet()), () -> d.active == null ? "-" : d.active, v -> d.active = v));
        arena.add(new Action("Paste selected build here", "Centred on you, protected afterwards (FAWE or WorldEdit)", p -> ev.placeBuild(p, d.selectedBuild, null)));
        arena.add(new Action("Capture area: set Pos 1 here", null, p -> ev.setPos(p, 1)));
        arena.add(new Action("Capture area: set Pos 2 here", null, p -> ev.setPos(p, 2)));
        arena.add(new Action("Capture area: use my WorldEdit selection", null, ev::useSelection));
        arena.add(new Action("Set spawn point here", "Where players are teleported when they join", p -> ev.setSpawn(p, false)));
        arena.add(new Action("Set exit point here", "Where players go when the event ends", p -> ev.setSpawn(p, true)));
        arena.add(new Action("Remove active arena (restore terrain)", null, p -> { if (d.active != null) ev.removeArena(p, d.active); }));

        return List.of(new Category("Gameplay flags", "Rules inside the arena zone", false, flags),
                new Category("Capture & teleport", "Scoring, join window and spawning", false, rules),
                new Category("Arena & builds", "Admin: place builds and pick the capture area", true, arena));
    }
}
