package com.eventcore.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Folia-safe scheduling facade. The Paper API ships the region/entity/global schedulers, and on a
 * normal Paper server they simply run on the main thread, so a single code path serves both.
 */
public final class Sched {
    private final Plugin pl;

    public Sched(Plugin pl) { this.pl = pl; }

    public ScheduledTask global(Runnable r) { return Bukkit.getGlobalRegionScheduler().run(pl, t -> r.run()); }
    public ScheduledTask globalLater(Runnable r, long delay) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(pl, t -> r.run(), Math.max(1, delay));
    }
    public ScheduledTask globalTimer(Consumer<ScheduledTask> c, long delay, long period) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(pl, c, Math.max(1, delay), Math.max(1, period));
    }

    public ScheduledTask region(Location l, Runnable r) { return Bukkit.getRegionScheduler().run(pl, l, t -> r.run()); }
    public ScheduledTask regionLater(Location l, Runnable r, long delay) {
        return Bukkit.getRegionScheduler().runDelayed(pl, l, t -> r.run(), Math.max(1, delay));
    }
    public ScheduledTask regionTimer(Location l, Consumer<ScheduledTask> c, long delay, long period) {
        return Bukkit.getRegionScheduler().runAtFixedRate(pl, l, c, Math.max(1, delay), Math.max(1, period));
    }

    public ScheduledTask entity(Entity e, Runnable r) { return e.getScheduler().run(pl, t -> r.run(), null); }
    public ScheduledTask entityLater(Entity e, Runnable r, long delay) {
        return e.getScheduler().runDelayed(pl, t -> r.run(), null, Math.max(1, delay));
    }
    public ScheduledTask entityTimer(Entity e, Consumer<ScheduledTask> c, Runnable retired, long delay, long period) {
        return e.getScheduler().runAtFixedRate(pl, c, retired, Math.max(1, delay), Math.max(1, period));
    }

    public void async(Runnable r) { Bukkit.getAsyncScheduler().runNow(pl, t -> r.run()); }

    public static void cancel(ScheduledTask t) {
        if (t != null) { try { t.cancel(); } catch (Exception ignored) { } }
    }
}
