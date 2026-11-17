package com.eventcore.koth;

import com.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Placed KoTH arenas (build bounds, capture area, spawn, exit) and the gameplay flags / numbers edited in the dialogs. Saved in koth-data.yml. */
public final class KothData {
    public enum Flag {
        PLACE_BLOCKS("place-blocks", "Block placing", "Allow placing blocks around the build (never inside it)", false),
        PVP("pvp", "PvP", "Players can hurt each other while the event runs", true),
        EXPLOSIONS("explosions", "Explosions", "TNT, creepers and beds can explode in the arena", false),
        MOB_SPAWNING("mob-spawning", "Mob spawning", "Natural mobs spawn in the arena", false),
        FIRE_SPREAD("fire-spread", "Fire spread", "Fire can ignite and burn blocks", false),
        FLUID_FLOW("fluid-flow", "Fluid flow", "Water and lava can flow in the arena", false),
        ENDER_PEARLS("ender-pearls", "Ender pearls", "Players can teleport with ender pearls", true),
        ITEM_DROP("item-drop", "Item dropping", "Players can drop items while the event runs", true),
        RESPAWN_AT_SPAWN("respawn-at-spawn", "Respawn at arena", "Participants respawn at the arena spawn", true);
        public final String key, label, tip; public final boolean def;
        Flag(String key, String label, String tip, boolean def) { this.key = key; this.label = label; this.tip = tip; this.def = def; }
    }

    public static final List<String> BREAK_MODES = List.of("NONE", "PLACED_ONLY", "ALL");
    public record BlockKey(UUID world, int x, int y, int z) { }

    public static final class Arena {
        public String name, build, world, backup;
        public int[] min = new int[3], max = new int[3], pos1, pos2;
        public double[] spawn, exit;      // x y z yaw pitch
        public String exitWorld;

        public boolean hasArea() { return pos1 != null && pos2 != null; }
        public boolean inBuild(Location l) { return in(l, 0); }

        /** Inside the build bounds grown by margin blocks horizontally (and 8 vertically). */
        public boolean in(Location l, int margin) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ(), vm = margin > 0 ? 8 : 0;
            return x >= min[0] - margin && x <= max[0] + margin && y >= min[1] - vm && y <= max[1] + vm && z >= min[2] - margin && z <= max[2] + margin;
        }

        public boolean inArea(Location l) {
            if (!hasArea() || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double x = l.getX(), y = l.getY(), z = l.getZ();
            return x >= Math.min(pos1[0], pos2[0]) && x < Math.max(pos1[0], pos2[0]) + 1 && y >= Math.min(pos1[1], pos2[1]) - 0.5 && y < Math.max(pos1[1], pos2[1]) + 2
                    && z >= Math.min(pos1[2], pos2[2]) && z < Math.max(pos1[2], pos2[2]) + 1;
        }

        public double[] centre() {
            return new double[]{(Math.min(pos1[0], pos2[0]) + Math.max(pos1[0], pos2[0]) + 1) / 2.0, Math.min(pos1[1], pos2[1]), (Math.min(pos1[2], pos2[2]) + Math.max(pos1[2], pos2[2]) + 1) / 2.0};
        }
    }

    private final EventCorePlugin pl;
    private final File file;
    private YamlConfiguration yml;
    public final Map<String, Arena> arenas = new LinkedHashMap<>();
    /** Blocks players placed in the arena zone (the only ones they may break in PLACED_ONLY mode). */
    public final Set<BlockKey> placed = ConcurrentHashMap.newKeySet();
    public volatile String active, selectedBuild;

    public KothData(EventCorePlugin pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "koth-data.yml");
        load();
    }

    private ConfigurationSection settings() { ConfigurationSection s = pl.cfg.events().getConfigurationSection("events.koth.settings"); return s; }

    // ------------------------------------------------------------------ flags + numbers (dialog override > events.yml > default)
    public boolean flag(Flag f) {
        if (yml.isSet("flags." + f.key)) return yml.getBoolean("flags." + f.key);
        ConfigurationSection s = settings(); return s == null ? f.def : s.getBoolean("flags." + f.key, f.def);
    }

    public void flag(Flag f, boolean v) { yml.set("flags." + f.key, v); }

    public String breakMode() {
        String m = yml.isSet("settings.break-mode") ? yml.getString("settings.break-mode") : settings() == null ? null : settings().getString("flags.break-mode");
        return m != null && BREAK_MODES.contains(m.toUpperCase(Locale.ROOT)) ? m.toUpperCase(Locale.ROOT) : "PLACED_ONLY";
    }

    public void breakMode(String m) { yml.set("settings.break-mode", m); }

    public double num(String key, double def) {
        if (yml.isSet("settings." + key)) return yml.getDouble("settings." + key);
        ConfigurationSection s = settings(); return s == null ? def : s.getDouble(key, def);
    }

    public void setNum(String key, double v) { yml.set("settings." + key, v); }

    public boolean bool(String key, boolean def) {
        if (yml.isSet("settings." + key)) return yml.getBoolean("settings." + key);
        ConfigurationSection s = settings(); return s == null ? def : s.getBoolean(key, def);
    }

    public void setBool(String key, boolean v) { yml.set("settings." + key, v); }

    // ------------------------------------------------------------------ arenas
    public Arena active() { return active == null ? null : arenas.get(active); }

    public Arena arenaAt(Location l, int margin) {
        for (Arena a : arenas.values()) if (a.in(l, margin)) return a;
        return null;
    }

    public BlockKey key(Block b) { return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }

    private static int[] ints(List<Integer> l) { return l == null || l.size() < 3 ? null : new int[]{l.get(0), l.get(1), l.get(2)}; }
    private static List<Integer> list(int[] a) { return a == null ? null : List.of(a[0], a[1], a[2]); }

    private void load() {
        yml = YamlConfiguration.loadConfiguration(file);
        arenas.clear();
        active = yml.getString("active");
        ConfigurationSection sec = yml.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String n : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(n); if (c == null) continue;
            Arena a = new Arena(); a.name = n; a.build = c.getString("build", ""); a.world = c.getString("world", "world"); a.backup = c.getString("backup");
            a.min = Optional.ofNullable(ints(c.getIntegerList("min"))).orElse(new int[3]); a.max = Optional.ofNullable(ints(c.getIntegerList("max"))).orElse(new int[3]);
            a.pos1 = ints(c.getIntegerList("pos1")); a.pos2 = ints(c.getIntegerList("pos2"));
            a.spawn = c.isList("spawn") ? c.getDoubleList("spawn").stream().mapToDouble(Double::doubleValue).toArray() : null;
            a.exit = c.isList("exit") ? c.getDoubleList("exit").stream().mapToDouble(Double::doubleValue).toArray() : null;
            a.exitWorld = c.getString("exit-world");
            arenas.put(n, a);
        }
        if (active != null && !arenas.containsKey(active)) active = arenas.isEmpty() ? null : arenas.keySet().iterator().next();
    }

    public synchronized void save() {
        yml.set("active", active); yml.set("arenas", null);
        for (Arena a : arenas.values()) {
            String b = "arenas." + a.name + ".";
            yml.set(b + "build", a.build); yml.set(b + "world", a.world); yml.set(b + "backup", a.backup);
            yml.set(b + "min", list(a.min)); yml.set(b + "max", list(a.max)); yml.set(b + "pos1", list(a.pos1)); yml.set(b + "pos2", list(a.pos2));
            yml.set(b + "spawn", a.spawn == null ? null : Arrays.stream(a.spawn).boxed().toList());
            yml.set(b + "exit", a.exit == null ? null : Arrays.stream(a.exit).boxed().toList()); yml.set(b + "exit-world", a.exitWorld);
        }
        try { yml.save(file); } catch (IOException e) { pl.getLogger().warning("koth-data.yml: " + e); }
    }

    public File backupFile(Arena a) { File d = new File(pl.getDataFolder(), "koth-backups"); d.mkdirs(); return new File(d, a.name + ".schem"); }

    public World world(Arena a) { return Bukkit.getWorld(a.world); }
}
