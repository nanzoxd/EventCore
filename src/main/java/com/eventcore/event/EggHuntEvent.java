package com.eventcore.event;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Rewards;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.TurtleEgg;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Egg Hunt: eggs are placed as real blocks (turtle eggs, dragon eggs or textured heads, one look per rarity) at
 * random surface and cave spots of the configured area plus any fixed spawns. Players click an egg to collect it.
 * <p>
 * Safety: every placed egg remembers the block it replaced and is listed in egghunt-state.yml, so the area is
 * restored when the event ends, on /egghunt reset, on plugin disable and (after a crash) on the next start.
 * While eggs exist they cannot be broken, trampled, pushed, blown up, flooded or physics-popped.
 */
public final class EggHuntEvent extends GameEvent {
    private static final BlockFace[] ROT = {BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.EAST, BlockFace.SOUTH_EAST,
            BlockFace.SOUTH, BlockFace.SOUTH_WEST, BlockFace.WEST, BlockFace.NORTH_WEST};
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
    private static final Set<Material> SOFT = EnumSet.of(Material.SHORT_GRASS, Material.FERN, Material.DEAD_BUSH, Material.SNOW);

    private static final class Egg {
        final int id, x, y, z;
        final String rarity, original;
        final AtomicBoolean taken = new AtomicBoolean();
        Egg(int id, int x, int y, int z, String rarity, String original) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.rarity = rarity; this.original = original;
        }
    }

    private static final class Stat {
        final UUID id;
        volatile String name = "?";
        final Set<Integer> found = ConcurrentHashMap.newKeySet();
        volatile int count;
        volatile long reached;
        Stat(UUID id) { this.id = id; }
    }

    public record Entry(int rank, UUID id, String name, int eggs) { }

    private final Map<Long, Egg> eggs = new ConcurrentHashMap<>();
    private final Map<UUID, Stat> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger placedTotal = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger randomLeft = new AtomicInteger();
    private final AtomicInteger shortfall = new AtomicInteger();
    private final AtomicBoolean placing = new AtomicBoolean();
    private final AtomicBoolean firstFound = new AtomicBoolean();
    private final Queue<int[]> fixed = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private boolean closed;                       // guarded by lock: no egg may be placed once removal started
    private volatile World world;
    private volatile ScheduledTask placer;
    private volatile boolean dirty;
    private volatile boolean skipRewards;
    private volatile List<Entry> last = List.of();
    private final File stateFile, resultFile;

    public EggHuntEvent(EventCorePlugin pl) {
        super(pl, "egg_hunt");
        this.stateFile = new File(pl.getDataFolder(), "egghunt-state.yml");
        this.resultFile = new File(pl.getDataFolder(), "egghunt-results.yml");
        loadResults();
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }

    private Egg eggAt(Block b) {
        World w = world;
        if (w == null || eggs.isEmpty() || !b.getWorld().equals(w)) return null;
        return eggs.get(key(b.getX(), b.getY(), b.getZ()));
    }

    private void say(CommandSender to, String path, Object... kv) {
        if (pl.fx.msg(pl.cfg.messages(), path).tpl().isBlank()) return;
        to.sendMessage(pl.msg(path, kv));
    }

    private void broadcast(String path, Map<String, Object> ph) {
        pl.fx.broadcast(pl.cfg.messages(), path, ph, pl.cfg.messages().getString("prefix", ""));
    }

    private ConfigurationSection rarity(String k) {
        ConfigurationSection r = settings().getConfigurationSection("rarities." + k);
        return r != null ? r : new org.bukkit.configuration.MemoryConfiguration();
    }

    private String pickRarity() {
        ConfigurationSection all = settings().getConfigurationSection("rarities");
        if (all == null || all.getKeys(false).isEmpty()) return "common";
        double sum = 0;
        for (String k : all.getKeys(false)) sum += Math.max(0, all.getDouble(k + ".weight", 1));
        if (sum <= 0) return all.getKeys(false).iterator().next();
        double roll = ThreadLocalRandom.current().nextDouble() * sum;
        for (String k : all.getKeys(false)) {
            roll -= Math.max(0, all.getDouble(k + ".weight", 1));
            if (roll < 0) return k;
        }
        return all.getKeys(false).iterator().next();
    }

    private static Color color(String hex) {
        try { return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16)); }
        catch (Exception e) { return Color.WHITE; }
    }

    private World resolveWorld(ConfigurationSection s) {
        String name = s.getString("area.world", "");
        if (name == null || name.isBlank()) return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return Bukkit.getWorld(name);
    }

    private int[] center(ConfigurationSection s, World w) {
        if (s.getString("area.world", "").isBlank()) return new int[]{w.getSpawnLocation().getBlockX(), w.getSpawnLocation().getBlockZ()};
        return new int[]{(int) Math.floor(s.getDouble("area.center-x", 0)), (int) Math.floor(s.getDouble("area.center-z", 0))};
    }

    private boolean stillRunning(ActiveEvent ae) { return pl.events.active(id()) == ae; }

    private boolean isEggMaterial(Material m) {
        if (m == Material.TURTLE_EGG || m == Material.DRAGON_EGG || m == Material.PLAYER_HEAD || m == Material.SNIFFER_EGG) return true;
        ConfigurationSection r = settings().getConfigurationSection("rarities");
        if (r != null) for (String k : r.getKeys(false)) if (Material.matchMaterial(r.getString(k + ".material", "")) == m) return true;
        return false;
    }

    // =====================================================================================================
    // public API (commands)
    // =====================================================================================================
    /** True while a finished hunt is still removing its eggs (a new hunt must wait). */
    public boolean cleaning() { return !eggs.isEmpty() && !active(); }
    public int hidden() { return placedTotal.get(); }
    public boolean isCollector(UUID id) { return stats.containsKey(id); }

    /** Live standings while running, the last finished hunt otherwise. */
    public List<Entry> standings(int limit) {
        List<Entry> src = active() ? live() : last;
        return src.size() <= limit ? src : new ArrayList<>(src.subList(0, Math.max(0, limit)));
    }

    public boolean showingLive() { return active(); }

    private List<Entry> live() {
        List<Stat> l = new ArrayList<>(stats.values());
        l.removeIf(s -> s.count <= 0);
        l.sort(Comparator.<Stat>comparingInt(s -> -s.count).thenComparingLong(s -> s.reached));
        List<Entry> out = new ArrayList<>();
        int rank = 1;
        for (Stat s : l) out.add(new Entry(rank++, s.id, s.name, s.count));
        return out;
    }

    public int eggsOf(UUID id) { Stat s = stats.get(id); return s == null ? 0 : s.count; }

    /** /egghunt reset: ends a running hunt without rewards, removes every egg and forgets all results. */
    public void reset() {
        skipRewards = true;
        if (active()) pl.events.stop(id(), false);
        else if (!eggs.isEmpty()) removeAll(false);
        else if (stateFile.exists()) recover();
        last = List.of();
        stats.clear();
        try { Files.deleteIfExists(resultFile.toPath()); } catch (IOException ignored) { }
    }

    // =====================================================================================================
    // lifecycle
    // =====================================================================================================
    private void abort(String path) {
        Bukkit.getServer().sendMessage(pl.msg(path));
        pl.sched.global(() -> pl.events.stop(id(), false));
    }

    @Override
    public void onStart(ActiveEvent ae) {
        if (!eggs.isEmpty()) { abort("egg-hunt.cleaning"); return; }
        synchronized (lock) { closed = false; }
        stats.clear(); lastClick.clear();
        ids.set(0); placedTotal.set(0); pending.set(0); shortfall.set(0);
        firstFound.set(false); skipRewards = false; fixed.clear();
        ConfigurationSection s = settings();
        World w = resolveWorld(s);
        if (w == null) { pl.getLogger().warning("Egg Hunt: the area world does not exist (egg_hunt.settings.area.world)."); abort("egg-hunt.no-world"); return; }
        world = w;
        for (String f : s.getStringList("eggs.fixed-spawns")) {
            String[] p = f.trim().split("[,\\s]+");
            if (p.length < 3) continue;
            try { fixed.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])}); }
            catch (NumberFormatException ex) { pl.getLogger().warning("Egg Hunt: bad fixed spawn '" + f + "' (use x,y,z)"); }
        }
        int amount = Math.max(1, s.getInt("eggs.amount", 40));
        randomLeft.set(Math.max(0, amount - fixed.size()));
        placing.set(true);
        placer = pl.sched.globalTimer(t -> placeTick(ae, w), 5, 2);
    }

    @Override
    public void onStop(ActiveEvent ae) {
        Sched.cancel(placer);
        placing.set(false);
        boolean shutdown = !pl.isEnabled();
        List<Entry> board = live();
        last = board;
        if (!shutdown) saveResults(board);
        if (!shutdown && !skipRewards) finish(board);
        else if (!shutdown) broadcast("egg-hunt.cancelled", Text.ph());
        stats.clear();
        removeAll(shutdown);
    }

    @Override
    public Map<String, Object> extra(ActiveEvent ae) {
        List<Entry> top = live();
        int found = 0;
        for (Stat s : stats.values()) found += s.count;
        return Text.ph("total", placedTotal.get(), "found", found,
                "leader", top.isEmpty() ? "-" : top.get(0).name(), "leader_eggs", top.isEmpty() ? 0 : top.get(0).eggs());
    }

    // =====================================================================================================
    // placement
    // =====================================================================================================
    private void placeTick(ActiveEvent ae, World w) {
        if (!stillRunning(ae)) { Sched.cancel(placer); return; }
        int batch = Math.max(1, settings().getInt("eggs.per-tick-batch", 3));
        for (int i = 0; i < batch; i++) {
            int[] f = fixed.poll();
            if (f != null) { pending.incrementAndGet(); placeFixed(ae, w, f); continue; }
            if (randomLeft.getAndDecrement() > 0) { pending.incrementAndGet(); tryRandom(ae, w, 1); }
            else { randomLeft.set(0); break; }
        }
        if (dirty) { dirty = false; saveState(); }
        if (fixed.isEmpty() && randomLeft.get() <= 0 && pending.get() <= 0) finishPlacing(ae);
    }

    private void finishPlacing(ActiveEvent ae) {
        if (!placing.compareAndSet(true, false)) return;
        Sched.cancel(placer);
        saveState();
        if (shortfall.get() > 0) pl.getLogger().warning("Egg Hunt: could only hide " + placedTotal.get() + " eggs (" + shortfall.get() + " spots were not found; enlarge the area or lower min-spacing).");
        if (stillRunning(ae)) broadcast("egg-hunt.hidden", Text.ph("total", placedTotal.get(), "minutes", ae.totalSec / 60));
    }

    private void placeFixed(ActiveEvent ae, World w, int[] f) {
        w.getChunkAtAsync(f[0] >> 4, f[2] >> 4, true).thenAccept(ch -> pl.sched.region(new Location(w, f[0], f[1], f[2]), () -> {
            try { if (stillRunning(ae) && place(w, f[0], f[1], f[2], pickRarity()) == null) shortfall.incrementAndGet(); }
            catch (Throwable t) { shortfall.incrementAndGet(); pl.getLogger().warning("Egg Hunt: fixed spawn failed: " + t); }
            finally { pending.decrementAndGet(); }
        })).exceptionally(t -> { shortfall.incrementAndGet(); pending.decrementAndGet(); return null; });
    }

    private void tryRandom(ActiveEvent ae, World w, int attempt) {
        if (!stillRunning(ae)) { pending.decrementAndGet(); return; }
        ConfigurationSection s = settings();
        int[] c = center(s, w);
        int r = Math.max(5, s.getInt("area.radius", 100));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x = c[0] + rnd.nextInt(-r, r + 1), z = c[1] + rnd.nextInt(-r, r + 1);
        w.getChunkAtAsync(x >> 4, z >> 4, false).thenAccept(ch -> {
            if (ch == null) { retry(ae, w, attempt); return; }
            pl.sched.region(new Location(w, x, 64, z), () -> attemptAt(ae, w, x, z, attempt));
        }).exceptionally(t -> { retry(ae, w, attempt); return null; });
    }

    private void retry(ActiveEvent ae, World w, int attempt) {
        if (attempt >= Math.max(1, settings().getInt("eggs.max-attempts", 30))) { shortfall.incrementAndGet(); pending.decrementAndGet(); return; }
        tryRandom(ae, w, attempt + 1);
    }

    private void attemptAt(ActiveEvent ae, World w, int x, int z, int attempt) {
        boolean placed = false;
        try {
            if (!stillRunning(ae)) { pending.decrementAndGet(); return; }
            ConfigurationSection s = settings();
            boolean cave = ThreadLocalRandom.current().nextInt(100) < s.getInt("eggs.underground-percent", 20);
            int y = cave ? findCave(w, x, z, s) : findSurface(w, x, z, s);
            if (y != Integer.MIN_VALUE && acceptable(w, x, y, z, s, attempt)) placed = place(w, x, y, z, pickRarity()) != null;
        } catch (Throwable t) {
            pl.getLogger().warning("Egg Hunt: placement attempt failed: " + t);
        }
        if (placed) pending.decrementAndGet();
        else retry(ae, w, attempt);
    }

    private boolean spaceOk(Block b) { Material t = b.getType(); return t.isAir() || SOFT.contains(t); }

    private boolean floorOk(Block b, ConfigurationSection s) {
        Material t = b.getType();
        if (!t.isSolid() || t.isAir() || b.isLiquid() || !t.isOccluding()) return false;
        for (String n : s.getStringList("eggs.avoid-blocks")) if (t.name().equalsIgnoreCase(n.trim())) return false;
        return true;
    }

    private int findSurface(World w, int x, int z, ConfigurationSection s) {
        int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int y = top + 1;
        if (top < s.getInt("area.min-y", -64) || y > s.getInt("area.max-y", 320)) return Integer.MIN_VALUE;
        if (!floorOk(w.getBlockAt(x, top, z), s) || !spaceOk(w.getBlockAt(x, y, z))) return Integer.MIN_VALUE;
        return y;
    }

    private int findCave(World w, int x, int z, ConfigurationSection s) {
        int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int lo = Math.max(s.getInt("area.min-y", -64), w.getMinHeight() + 2);
        int hi = top - 6;
        if (hi <= lo) return Integer.MIN_VALUE;
        for (int y = ThreadLocalRandom.current().nextInt(lo, hi + 1); y > lo; y--) {
            if (spaceOk(w.getBlockAt(x, y, z)) && floorOk(w.getBlockAt(x, y - 1, z), s)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Hidden = walls or an overhang next to the egg; spacing keeps eggs from clumping. The last attempts relax the cover rule. */
    private boolean acceptable(World w, int x, int y, int z, ConfigurationSection s, int attempt) {
        int need = attempt >= Math.max(1, s.getInt("eggs.max-attempts", 30)) - 3 ? 0 : s.getInt("eggs.min-cover", 1);
        int cover = 0;
        for (int[] d : SIDES) if (w.getBlockAt(x + d[0], y + d[1], z + d[2]).getType().isSolid()) cover++;
        if (cover < need) return false;
        double sp = Math.max(0, s.getDouble("eggs.min-spacing", 8));
        if (sp > 0) {
            double sq = sp * sp;
            for (Egg e : eggs.values()) {
                double dx = e.x - x, dy = e.y - y, dz = e.z - z;
                if (dx * dx + dy * dy + dz * dz < sq) return false;
            }
        }
        return true;
    }

    /** Places one egg. Returns null when removal already started or the spot is taken. */
    private Egg place(World w, int x, int y, int z, String rarityKey) {
        synchronized (lock) {
            if (closed) return null;
            long k = key(x, y, z);
            if (eggs.containsKey(k)) return null;
            Block b = w.getBlockAt(x, y, z);
            Egg e = new Egg(ids.incrementAndGet(), x, y, z, rarityKey, b.getBlockData().getAsString());
            eggs.put(k, e);                      // registered before the block changes: a crash in between is still recoverable
            dirty = true;
            ConfigurationSection rs = rarity(rarityKey);
            Material mat = Material.matchMaterial(rs.getString("material", "TURTLE_EGG"));
            if (mat == null || !mat.isBlock()) mat = Material.TURTLE_EGG;
            BlockData data = mat.createBlockData();
            if (data instanceof TurtleEgg te) te.setEggs(Math.max(1, Math.min(te.getMaximumEggs(), rs.getInt("eggs", 1))));
            else if (data instanceof Rotatable rot) rot.setRotation(ROT[ThreadLocalRandom.current().nextInt(ROT.length)]);
            b.setBlockData(data, false);
            String tex = rs.getString("texture", "");
            if (mat == Material.PLAYER_HEAD && tex != null && !tex.isBlank() && b.getState() instanceof Skull sk) {
                PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(("egg:" + tex).getBytes()), "egg");
                prof.setProperty(new ProfileProperty("textures", tex.trim()));
                sk.setPlayerProfile(prof);
                sk.update(false, false);
            }
            placedTotal.incrementAndGet();
            return e;
        }
    }

    // =====================================================================================================
    // removal + crash recovery
    // =====================================================================================================
    private void restore(World w, Egg e) {
        Block b = w.getBlockAt(e.x, e.y, e.z);
        if (!isEggMaterial(b.getType())) return;          // somebody changed the block meanwhile: leave it alone
        BlockData d;
        try { d = Bukkit.createBlockData(e.original); } catch (IllegalArgumentException ex) { d = Material.AIR.createBlockData(); }
        b.setBlockData(d, false);
    }

    /** Restores the area. sync = on this thread (plugin disable); otherwise per egg on its region thread. */
    private void removeAll(boolean sync) {
        List<Egg> snap;
        synchronized (lock) { closed = true; snap = new ArrayList<>(eggs.values()); }
        World w = world;
        if (snap.isEmpty() || w == null) { if (snap.isEmpty()) { eggs.clear(); deleteState(); } return; }
        restoreAll(w, snap, sync);
    }

    private void restoreAll(World w, List<Egg> list, boolean sync) {
        AtomicInteger left = new AtomicInteger(list.size());
        AtomicInteger failed = new AtomicInteger();
        for (Egg e : list) {
            Runnable done = () -> {
                if (left.decrementAndGet() == 0 && failed.get() == 0 && eggs.isEmpty()) deleteState();
            };
            if (sync) {
                try { restore(w, e); eggs.remove(key(e.x, e.y, e.z), e); }
                catch (Throwable t) { failed.incrementAndGet(); pl.getLogger().warning("Egg Hunt: could not restore " + e.x + "," + e.y + "," + e.z + ": " + t); }
                done.run();
                continue;
            }
            w.getChunkAtAsync(e.x >> 4, e.z >> 4, false).thenAccept(ch -> pl.sched.region(new Location(w, e.x, e.y, e.z), () -> {
                try { if (ch != null) restore(w, e); eggs.remove(key(e.x, e.y, e.z), e); }
                catch (Throwable t) { failed.incrementAndGet(); pl.getLogger().warning("Egg Hunt: could not restore " + e.x + "," + e.y + "," + e.z + ": " + t); }
                done.run();
            })).exceptionally(t -> { failed.incrementAndGet(); done.run(); return null; });
        }
    }

    /** Called on plugin enable: removes eggs a crash or a failed shutdown left in the world. */
    public void recover() {
        if (!stateFile.exists() || active()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) { pl.getLogger().warning("Egg Hunt: leftover eggs are listed for a world that is not loaded; they stay in egghunt-state.yml."); return; }
        List<Egg> list = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("eggs")) {
            if (!(m.get("x") instanceof Number x) || !(m.get("y") instanceof Number yy) || !(m.get("z") instanceof Number z)) continue;
            Object data = m.get("data");
            list.add(new Egg(0, x.intValue(), yy.intValue(), z.intValue(), "", data == null ? "minecraft:air" : String.valueOf(data)));
        }
        if (list.isEmpty()) { deleteState(); return; }
        pl.getLogger().info("Egg Hunt: removing " + list.size() + " leftover eggs of an unfinished hunt.");
        world = w;
        synchronized (lock) { closed = true; for (Egg e : list) eggs.put(key(e.x, e.y, e.z), e); }
        restoreAll(w, list, false);
    }

    private synchronized void saveState() {
        World w = world;
        if (w == null || eggs.isEmpty()) { deleteState(); return; }
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", w.getName());
        List<Map<String, Object>> l = new ArrayList<>();
        for (Egg e : eggs.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", e.x); m.put("y", e.y); m.put("z", e.z); m.put("data", e.original);
            l.add(m);
        }
        y.set("eggs", l);
        try { y.save(stateFile); } catch (IOException ex) { pl.getLogger().warning("Egg Hunt: could not save egghunt-state.yml: " + ex.getMessage()); }
    }

    private synchronized void deleteState() {
        try { Files.deleteIfExists(stateFile.toPath()); } catch (IOException ignored) { }
    }

    private synchronized void saveResults(List<Entry> board) {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> l = new ArrayList<>();
        for (Entry e : board) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id().toString()); m.put("name", e.name()); m.put("eggs", e.eggs());
            l.add(m);
        }
        y.set("results", l);
        try { y.save(resultFile); } catch (IOException ex) { pl.getLogger().warning("Egg Hunt: could not save egghunt-results.yml: " + ex.getMessage()); }
    }

    private void loadResults() {
        if (!resultFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(resultFile);
        List<Entry> out = new ArrayList<>();
        int rank = 1;
        for (Map<?, ?> m : y.getMapList("results")) {
            try {
                out.add(new Entry(rank++, UUID.fromString(String.valueOf(m.get("id"))), String.valueOf(m.get("name")),
                        m.get("eggs") instanceof Number n ? n.intValue() : 0));
            } catch (IllegalArgumentException ignored) { }
        }
        last = out;
    }

    // =====================================================================================================
    // every second: action bar, hints, warnings, periodic board
    // =====================================================================================================
    private void actionBar(Player p) {
        if (pl.disaster != null && pl.disaster.isMember(p.getUniqueId())) return;
        String tpl = pl.fx.msg(pl.cfg.messages(), "egg-hunt.actionbar").tpl();
        if (tpl.isBlank()) return;
        p.sendActionBar(pl.text.render(tpl, Text.ph("eggs", eggsOf(p.getUniqueId()), "total", placedTotal.get())));
    }

    private void hint(Player p, double range) {
        World w = world;
        if (w == null || !p.isOnline() || !p.getWorld().equals(w)) return;
        Stat st = stats.get(p.getUniqueId());
        Location at = p.getLocation();
        double r2 = range * range;
        int shown = 0;
        for (Egg e : eggs.values()) {
            if (st != null && st.found.contains(e.id)) continue;
            double dx = e.x + 0.5 - at.getX(), dy = e.y + 0.5 - at.getY(), dz = e.z + 0.5 - at.getZ();
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            p.spawnParticle(Particle.DUST, e.x + 0.5, e.y + 0.7, e.z + 0.5, 3, 0.2, 0.2, 0.2, 0,
                    new Particle.DustOptions(color(rarity(e.rarity).getString("color", "#ffffff")), 1.0f));
            if (++shown >= 10) break;
        }
    }

    @Override
    public void onSecond(ActiveEvent ae, int left) {
        if (!stillRunning(ae)) return;
        ConfigurationSection s = settings();
        boolean hints = s.getBoolean("hints.enabled", true);
        double range = s.getDouble("hints.range", 7.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            actionBar(p);
            if (hints) pl.sched.entity(p, () -> hint(p, range));
        }
        if (s.getIntegerList("announce.time-warnings").contains(left))
            broadcast("egg-hunt.time-warning", Text.ph("time", com.eventcore.util.Util.mmss(left), "seconds", left));
        int every = s.getInt("leaderboard.broadcast-minutes", 0);
        int elapsed = ae.totalSec - left;
        if (every > 0 && elapsed > 0 && elapsed % (every * 60) == 0) announceBoard("egg-hunt.board-header", live());
        if (dirty && left % 5 == 0) { dirty = false; saveState(); }
    }

    // =====================================================================================================
    // collecting + protection
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (eggs.isEmpty()) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Egg egg = eggAt(b);
        if (egg == null) return;
        e.setCancelled(true);                                  // no stomping, no placing against it, no dragon-egg teleport
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;          // one collect per click, not one per hand
        if (active()) collect(e.getPlayer(), egg);
    }

    private void collect(Player p, Egg egg) {
        World w = world;
        if (w == null || p.getGameMode() == GameMode.SPECTATOR) return;
        if (pl.disaster != null && pl.disaster.isMember(p.getUniqueId())) return;      // disaster players stay out of the hunt
        ConfigurationSection s = settings();
        long now = System.currentTimeMillis();
        Long prev = lastClick.put(p.getUniqueId(), now);
        if (prev != null && now - prev < s.getLong("anti-exploit.click-cooldown-ms", 250)) return;
        double max = s.getDouble("anti-exploit.max-distance", 6.5);
        Location c = new Location(w, egg.x + 0.5, egg.y + 0.5, egg.z + 0.5);
        if (!p.getWorld().equals(w) || p.getLocation().distanceSquared(c) > max * max) return;

        boolean perPlayer = s.getBoolean("eggs.per-player", true);
        Stat st = stats.computeIfAbsent(p.getUniqueId(), Stat::new);
        st.name = p.getName();
        ConfigurationSection rs = rarity(egg.rarity);
        Map<String, Object> ph = Text.ph("player", p.getName(), "rarity", pl.text.sc(rs.getString("display", egg.rarity)),
                "total", placedTotal.get());
        if (perPlayer) {
            if (!st.found.add(egg.id)) {
                p.sendActionBar(pl.text.render(pl.fx.msg(pl.cfg.messages(), "egg-hunt.already").tpl(), ph));
                pl.fx.play(p, s.getConfigurationSection("sounds.already"));
                return;
            }
        } else {
            if (!egg.taken.compareAndSet(false, true)) return;
            st.found.add(egg.id);
            eggs.remove(key(egg.x, egg.y, egg.z), egg);
            restore(w, egg);
            dirty = true;
        }
        int count;
        synchronized (st) { count = ++st.count; st.reached = now; }
        ph.put("eggs", count);

        // feedback
        if (perPlayer) p.sendBlockChange(c, Bukkit.createBlockData(egg.original));       // this player no longer sees it
        pl.fx.play(p, rs.getConfigurationSection("sound"));
        w.spawnParticle(Particle.DUST, c, 14, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(color(rs.getString("color", "#ffffff")), 1.3f));
        actionBar(p);
        say(p, "egg-hunt.collected", "rarity", ph.get("rarity"), "eggs", count, "total", placedTotal.get());

        // rewards: per rarity, then per milestone
        Rewards.Entry instant = Rewards.section(rs.getConfigurationSection("reward"));
        if (!instant.empty()) Rewards.give(pl, p.getName(), p, instant, ph);
        for (Rewards.Entry m : Rewards.read(s.getMapList("rewards.milestones"), "eggs")) {
            if (m.at() != count) continue;
            Rewards.give(pl, p.getName(), p, m, ph);
            if (!m.message().isBlank()) p.sendMessage(pl.text.render(pl.cfg.messages().getString("prefix", "") + m.message(), ph));
        }

        // announcements
        if (firstFound.compareAndSet(false, true)) broadcast("egg-hunt.first-find", ph);
        if (s.getIntegerList("announce.milestones").contains(count)) broadcast("egg-hunt.milestone", ph);
        if (perPlayer && count >= placedTotal.get() && placedTotal.get() > 0) broadcast("egg-hunt.found-all", ph);
        if (!perPlayer && eggs.isEmpty() && s.getBoolean("eggs.end-when-all-found", true)) {
            pl.sched.global(() -> pl.events.stop(id(), true));
        }
    }

    // ---- protection: keyed on the egg map, so it holds until the last egg is restored ----
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) { if (eggAt(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { if (!eggs.isEmpty()) e.blockList().removeIf(b -> eggAt(b) != null); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (!eggs.isEmpty()) e.blockList().removeIf(b -> eggAt(b) != null); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (eggs.isEmpty()) return;
        for (Block b : e.getBlocks()) if (eggAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (eggs.isEmpty()) return;
        for (Block b : e.getBlocks()) if (eggAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) { if (eggAt(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (eggAt(e.getToBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { if (eggAt(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (eggAt(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (eggAt(e.getBlock()) != null) e.setCancelled(true); }

    // =====================================================================================================
    // end of the hunt: results + rewards
    // =====================================================================================================
    private void announceBoard(String headerPath, List<Entry> board) {
        int size = Math.max(1, settings().getInt("leaderboard.size", 10));
        Component header = pl.msg(headerPath);
        List<Component> lines = new ArrayList<>();
        for (Entry e : board) {
            if (lines.size() >= size) break;
            lines.add(pl.msg("egg-hunt.board-line", "rank", e.rank(), "player", e.name(), "eggs", e.eggs()));
        }
        if (lines.isEmpty()) { Bukkit.getServer().sendMessage(pl.msg("egg-hunt.board-empty")); return; }
        Bukkit.getServer().sendMessage(header);
        for (Component c : lines) Bukkit.getServer().sendMessage(c);
    }

    private void finish(List<Entry> board) {
        ConfigurationSection s = settings();
        if (board.isEmpty()) { broadcast("egg-hunt.end-none", Text.ph()); return; }
        announceBoard("egg-hunt.end-header", board);
        for (Rewards.Entry r : Rewards.read(s.getMapList("rewards.placements"), "place")) {
            if (r.at() < 1 || r.at() > board.size()) continue;
            Entry who = board.get(r.at() - 1);
            Map<String, Object> ph = Text.ph("place", r.at(), "eggs", who.eggs(), "uuid", who.id());
            Rewards.give(pl, who.name(), Bukkit.getPlayer(who.id()), r, ph);
            Player online = Bukkit.getPlayer(who.id());
            if (online != null && !r.message().isBlank())
                online.sendMessage(pl.text.render(pl.cfg.messages().getString("prefix", "") + r.message(), ph));
        }
        ConfigurationSection part = s.getConfigurationSection("rewards.participation");
        if (part != null) {
            int min = Math.max(1, part.getInt("min-eggs", 1));
            Rewards.Entry r = Rewards.section(part);
            if (!r.empty()) for (Entry who : board) {
                if (who.eggs() < min) continue;
                Rewards.give(pl, who.name(), Bukkit.getPlayer(who.id()), r, Text.ph("eggs", who.eggs(), "uuid", who.id()));
            }
        }
    }
}
