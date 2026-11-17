package com.eventcore.koth;

import com.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Available KoTH builds (schematics) and pasting / restoring them with FAWE or WorldEdit (both provide the same API). */
public final class KothBuilds {
    private final EventCorePlugin pl;
    private final File dir;

    public KothBuilds(EventCorePlugin pl) {
        this.pl = pl;
        dir = new File(pl.getDataFolder(), "koth-builds");
        if (!dir.exists()) { dir.mkdirs(); try { pl.saveResource("koth-builds/eventcorekoth.schem", false); } catch (IllegalArgumentException ignored) { } }
    }

    /** "FAWE", "WorldEdit" or null when neither is installed. */
    public static String engine() {
        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null) return "FAWE";
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null ? "WorldEdit" : null;
    }

    /** Build ids = schematic file names (lower case, no extension) in plugins/EventCore/koth-builds. */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".schem") || n.endsWith(".schematic"));
        if (files != null) for (File f : files) out.add(f.getName().replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT));
        Collections.sort(out); return out;
    }

    public File file(String id) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).replaceFirst("\\.[^.]+$", "").equals(id));
        return files == null || files.length == 0 ? null : files[0];
    }

    public String display(String id) { return pl.cfg.events().getString("events.koth.settings.builds." + id + ".name", id); }
    public int yOffset(String id) { return pl.cfg.events().getInt("events.koth.settings.builds." + id + ".y-offset", 0); }

    /** Pastes the build centred on the location (its bottom layer at the location's Y plus the build's y-offset) and fills arena.min/max/backup. */
    public CompletableFuture<Void> place(KothData data, KothData.Arena a, Location at) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        File schem = file(a.build);
        if (engine() == null) { done.completeExceptionally(new IllegalStateException("install FastAsyncWorldEdit or WorldEdit")); return done; }
        if (schem == null) { done.completeExceptionally(new IllegalStateException("unknown build " + a.build)); return done; }
        File backup = data.backupFile(a); World w = at.getWorld(); int y = at.getBlockY() + yOffset(a.build);
        pl.sched.region(at, () -> {
            try {
                int[] b = WeBridge.paste(schem, w, at.getBlockX(), y, at.getBlockZ(), backup);
                a.world = w.getName(); a.min = new int[]{b[0], b[1], b[2]}; a.max = new int[]{b[3], b[4], b[5]}; a.backup = backup.getName();
                done.complete(null);
            } catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }

    /** Puts the terrain back from the backup taken before the paste. */
    public CompletableFuture<Void> restore(KothData data, KothData.Arena a) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        File backup = data.backupFile(a); World w = data.world(a);
        if (w == null || !backup.exists()) { done.completeExceptionally(new IllegalStateException("no backup found for " + a.name)); return done; }
        Location at = new Location(w, a.min[0], a.min[1], a.min[2]);
        pl.sched.region(at, () -> {
            try { WeBridge.restore(backup, w, a.min); backup.delete(); done.complete(null); } catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }

    /** The player's WorldEdit selection as {x1,y1,z1,x2,y2,z2}, or null. */
    public int[] selection(Player p) {
        if (engine() == null) return null;
        try { return WeBridge.selection(p); } catch (Throwable t) { return null; }
    }
}
