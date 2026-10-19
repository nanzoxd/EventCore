package com.eventcore.command;

import com.eventcore.EventCorePlugin;
import com.eventcore.altar.AltarManager;
import com.eventcore.altar.AltarManager.Altar;
import com.eventcore.altar.AltarManager.Recipe;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
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

/** /altar spawn|remove|list|recipe ...|bind|stop|reload */
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
            case "recipes", "recipe", "editor", "gui" -> recipeCmd(s, a);
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

    private static int num(String s, int def) { try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; } }

    /** /altar recipe list|create|output|input|clear|minutes|delete */
    private void recipeCmd(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            s.sendMessage(am().m("recipe-header", Map.of()));
            for (Recipe r : am().recipes())
                s.sendMessage(am().m("recipe-line", Text.ph("id", r.id, "inputs", r.inputLine(),
                        "output", r.output == null ? "-" : AltarManager.itemName(r.output), "minutes", r.minutes)));
            return;
        }
        if (a.length < 3) { s.sendMessage(am().m("usage-recipe", Map.of())); return; }
        String id = a[2];
        if (sub.equals("create")) {
            am().createRecipe(id, a.length > 3 ? num(a[3], am().defaultMinutes()) : am().defaultMinutes());
            s.sendMessage(am().m("recipe-created", Text.ph("id", id)));
            return;
        }
        Recipe r = am().recipe(id);
        if (r == null) { s.sendMessage(am().m("unknown-recipe", Text.ph("input", id))); return; }
        switch (sub) {
            case "delete" -> { am().deleteRecipe(id); s.sendMessage(am().m("deleted", Text.ph("id", id))); }
            case "clear" -> { am().clearInputs(r); s.sendMessage(am().m("recipe-updated", Text.ph("id", id))); }
            case "minutes" -> {
                if (a.length < 4) { s.sendMessage(am().m("usage-recipe", Map.of())); return; }
                r.minutes = num(a[3], r.minutes);
                am().saveRecipes();
                s.sendMessage(am().m("recipe-updated", Text.ph("id", id)));
            }
            case "output", "input" -> {
                if (!(s instanceof Player p)) { s.sendMessage(pl.msg("errors.player-only")); return; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (sub.equals("output")) {
                    if (held.getType().isAir()) { s.sendMessage(am().m("no-held-item", Map.of())); return; }
                    am().setOutput(r, held);
                } else if (a.length > 3 && Material.matchMaterial(a[3]) != null) {
                    // /altar recipe input <id> <MATERIAL> [amount]  -> any item of that material
                    am().addInput(r, am().inputFromMaterial(Material.matchMaterial(a[3]), a.length > 4 ? num(a[4], 1) : 1));
                } else {
                    // /altar recipe input <id> [amount]  -> the exact item in hand (custom name, model data, lore...)
                    if (held.getType().isAir()) { s.sendMessage(am().m("no-held-item", Map.of())); return; }
                    am().addInput(r, am().inputFromHeld(held, a.length > 3 ? num(a[3], held.getAmount()) : held.getAmount()));
                }
                s.sendMessage(am().m("recipe-updated", Text.ph("id", id)));
            }
            default -> s.sendMessage(am().m("usage-recipe", Map.of()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("eventcore.altar")) return out;
        if (a.length == 1) out.addAll(List.of("spawn", "remove", "list", "recipe", "bind", "stop", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("recipe")) out.addAll(List.of("list", "create", "output", "input", "clear", "minutes", "delete"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("recipe") && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list"))
            for (AltarManager.Recipe r : am().recipes()) out.add(r.id);
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
