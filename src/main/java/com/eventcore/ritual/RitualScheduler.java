package com.eventcore.ritual;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Sched;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scheduled rituals (/ritual schedule). All times are in the server's EventCore timezone, IST (Asia/Kolkata) by default.
 * A schedule holds the item, the spot and the length; when its time comes the ritual starts there. If another ritual is
 * running at that moment the schedule simply waits until it ends. Saved in data/ritual-schedule.yml.
 */
public final class RitualScheduler {

    public static final class Entry {
        public final String id;
        public final long at;                 // epoch millis
        public final String world;
        public final double x, y, z;
        public final float yaw, pitch;
        public final int minutes;
        public final ItemStack item;
        public final String creator;
        volatile boolean starting;

        Entry(String id, long at, String world, double x, double y, double z, float yaw, float pitch, int minutes, ItemStack item, String creator) {
            this.id = id; this.at = at; this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
            this.minutes = minutes; this.item = item; this.creator = creator;
        }
    }

    private final EventCorePlugin pl;
    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final File file;
    private volatile ScheduledTask task;

    public RitualScheduler(EventCorePlugin pl) {
        this.pl = pl;
        this.file = new File(new File(pl.getDataFolder(), "data"), "ritual-schedule.yml");
    }

    // ---------- time (IST) ----------
    public ZoneId zone() {
        try { return ZoneId.of(pl.cfg.config().getString("timezone", "Asia/Kolkata")); }
        catch (Exception e) { return ZoneId.of("Asia/Kolkata"); }
    }

    public String zoneLabel() {
        ZoneId z = zone();
        return z.getId().equals("Asia/Kolkata") || z.getId().equals("Asia/Calcutta") ? "IST" : z.getId();
    }

    public String fmt(long millis) {
        return DateTimeFormatter.ofPattern("EEE d MMM, hh:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(zone())) + " " + zoneLabel();
    }

    // ---------- lifecycle ----------
    public void start() {
        load();
        task = pl.sched.globalTimer(t -> tick(), 20, 20);
    }

    public void shutdown() {
        Sched.cancel(task);
        task = null;
        synchronized (lock) { save(); }
    }

    // ---------- API ----------
    public String add(Player creator, ItemStack item, long at, Location loc, int minutes) {
        String id = Integer.toString(new Random().nextInt(0x7fffff), 36);
        synchronized (lock) {
            while (entries.containsKey(id)) id = id + "x";
            entries.put(id, new Entry(id, at, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), minutes, item.clone(), creator.getName()));
            save();
        }
        return id;
    }

    /** Puts a cancelled entry back (console cancel cannot receive the item). */
    public void add0(Entry e) {
        synchronized (lock) { entries.put(e.id, e); save(); }
    }

    public List<Entry> list() {
        synchronized (lock) {
            List<Entry> l = new ArrayList<>(entries.values());
            l.sort(Comparator.comparingLong(e -> e.at));
            return l;
        }
    }

    /** Removes a schedule that has not started. The caller hands the item back. Returns null if unknown or already starting. */
    public Entry cancel(String id) {
        synchronized (lock) {
            Entry e = entries.get(id);
            if (e == null || e.starting) return null;
            entries.remove(id);
            save();
            return e;
        }
    }

    public List<String> ids() { synchronized (lock) { return new ArrayList<>(entries.keySet()); } }

    // ---------- tick ----------
    private void tick() {
        if (pl.ritual.active()) return;           // wait until the running ritual is over
        long now = System.currentTimeMillis();
        Entry due = null;
        synchronized (lock) {
            for (Entry e : entries.values()) if (!e.starting && e.at <= now && (due == null || e.at < due.at)) due = e;
            if (due == null) return;
            due.starting = true;
        }
        launch(due);
    }

    private void launch(Entry e) {
        World w = Bukkit.getWorld(e.world);
        if (w == null) { e.starting = false; return; }      // world not loaded (yet): keep waiting
        Location loc = new Location(w, e.x, e.y, e.z, e.yaw, e.pitch);
        w.getChunkAtAsync(loc).thenAccept(ch -> pl.sched.region(loc, () -> {
            if (pl.ritual.startScheduled(loc, e.item, e.minutes, e.creator)) {
                synchronized (lock) { entries.remove(e.id); save(); }
            } else e.starting = false;
        })).exceptionally(ex -> { e.starting = false; return null; });
    }

    // ---------- persistence ----------
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Entry e : entries.values()) {
            String k = "schedules." + e.id;
            y.set(k + ".at", e.at);
            y.set(k + ".world", e.world);
            y.set(k + ".x", e.x); y.set(k + ".y", e.y); y.set(k + ".z", e.z);
            y.set(k + ".yaw", (double) e.yaw); y.set(k + ".pitch", (double) e.pitch);
            y.set(k + ".minutes", e.minutes);
            y.set(k + ".creator", e.creator);
            y.set(k + ".item", Base64.getEncoder().encodeToString(e.item.serializeAsBytes()));
        }
        try {
            file.getParentFile().mkdirs();
            Path tmp = file.toPath().resolveSibling("ritual-schedule.yml.tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save ritual-schedule.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("schedules");
        if (s == null) return;
        synchronized (lock) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(c.getString("item", "")));
                    entries.put(id, new Entry(id, c.getLong("at"), c.getString("world", "world"), c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                            (float) c.getDouble("yaw"), (float) c.getDouble("pitch"), Math.max(1, c.getInt("minutes", 15)), item, c.getString("creator", "?")));
                } catch (Exception ex) {
                    pl.getLogger().warning("Scheduled ritual " + id + " could not be read and was skipped.");
                }
            }
        }
        if (!entries.isEmpty()) pl.getLogger().info("Loaded " + entries.size() + " scheduled ritual(s).");
    }
}
