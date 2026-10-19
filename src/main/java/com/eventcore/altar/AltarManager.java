package com.eventcore.altar;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom altar crafting. Recipes (3x3 grid + output) are edited in a GUI, altars are placed from presets with
 * /altar spawn, a hologram above each altar shows its recipe. Crafting starts a ritual (30 min by default) whose
 * coordinates are revealed one number at a time (X at the start, Y after 10 min, Z after 15 min); numbers that are
 * not revealed yet are sent as obfuscated filler digits, never as the real value.
 */
public final class AltarManager implements Listener {

    // ---------------- data ----------------
    public static final class Recipe {
        public final String id;
        public final ItemStack[] grid = new ItemStack[9];
        public ItemStack output;
        public int minutes;

        Recipe(String id, int minutes) { this.id = id; this.minutes = minutes; }

        public boolean matches(ItemStack[] in) {
            for (int i = 0; i < 9; i++) {
                ItemStack need = grid[i], have = in[i];
                boolean haveEmpty = have == null || have.getType().isAir();
                if (need == null) { if (!haveEmpty) return false; continue; }
                if (haveEmpty || !need.isSimilar(have) || have.getAmount() < need.getAmount()) return false;
            }
            return true;
        }
    }

    public final class Altar {
        public final int id;
        public final String preset;
        public final Location center;
        final Set<String> blocks = ConcurrentHashMap.newKeySet();
        volatile TextDisplay holo;
        volatile boolean holoSpawning;
        String lastHolo = "";
        volatile Ritual ritual;

        Altar(int id, String preset, Location center) { this.id = id; this.preset = preset; this.center = center; }

        public boolean busy() { return ritual != null; }
    }

    final class Ritual {
        final Altar altar;
        final Recipe recipe;
        final String starter;
        final int total;
        volatile int remaining;
        volatile int stage;
        BossBar bar;

        Ritual(Altar altar, Recipe recipe, String starter, int total, int remaining) {
            this.altar = altar; this.recipe = recipe; this.starter = starter; this.total = total; this.remaining = remaining;
        }
    }

    private final EventCorePlugin pl;
    private final NamespacedKey KEY;
    private final File cfgFile, recipesFile, placedFile, ritualsFile;
    private YamlConfiguration cfg = new YamlConfiguration();
    private final Map<String, Recipe> recipes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Integer, Altar> altars = new ConcurrentHashMap<>();
    private final Map<String, Altar> byCenter = new ConcurrentHashMap<>();
    private final Map<String, Altar> byBlock = new ConcurrentHashMap<>();
    private int nextId = 1;
    private ScheduledTask ticker;
    private int saveTick;
    public AltarGui gui;

    public AltarManager(EventCorePlugin pl) {
        this.pl = pl;
        this.KEY = new NamespacedKey(pl, "altar_holo");
        File data = new File(pl.getDataFolder(), "data");
        this.cfgFile = new File(pl.getDataFolder(), "altars.yml");
        this.recipesFile = new File(data, "altar-recipes.yml");
        this.placedFile = new File(data, "altar-placed.yml");
        this.ritualsFile = new File(data, "altar-rituals.yml");
    }

    // ---------------- config ----------------
    public YamlConfiguration cfg() { return cfg; }

    public void loadConfig() {
        pl.getDataFolder().mkdirs();
        if (!cfgFile.exists()) pl.saveResource("altars.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(cfgFile);
        try (InputStream in = pl.getResource("altars.yml")) {
            if (in != null) {
                y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
                y.options().copyDefaults(true);
            }
        } catch (Exception ex) { pl.getLogger().warning("altars.yml defaults: " + ex); }
        cfg = y;
    }

    private void saveConfig() {
        try { cfg.save(cfgFile); } catch (Exception ex) { pl.getLogger().warning("altars.yml save: " + ex); }
    }

    public Component m(String path, Map<String, Object> ph) {
        return pl.text.render(pl.cfg.messages().getString("ritual-prefix", "") + cfg.getString("messages." + path, path), ph);
    }

    public int defaultMinutes() { return Math.max(1, cfg.getInt("ritual.default-minutes", 30)); }

    public Set<String> presetNames() {
        ConfigurationSection s = cfg.getConfigurationSection("presets");
        return s == null ? Set.of() : s.getKeys(false);
    }

    // ---------------- lifecycle ----------------
    public void load() {
        loadConfig();
        loadRecipes();
        loadAltars();
        ticker = pl.sched.globalTimer(t -> tick(), 20, 20);
        restoreRituals();
    }

    public void reload() {
        loadConfig();
        loadRecipes();
    }

    public void shutdown() {
        Sched.cancel(ticker);
        saveRitualsBlocking();
        for (Altar a : altars.values()) {
            Ritual r = a.ritual;
            if (r != null) pl.bars.hide(r.bar);
            TextDisplay h = a.holo;
            if (h != null) { try { h.remove(); } catch (Throwable ignored) { } }
        }
    }

    // ---------------- item (de)serialisation ----------------
    private static String enc(ItemStack it) {
        return it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack dec(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); } catch (Exception e) { return null; }
    }

    // ---------------- recipes ----------------
    public Collection<Recipe> recipes() { synchronized (recipes) { return new ArrayList<>(recipes.values()); } }
    public Recipe recipe(String id) { return id == null ? null : recipes.get(id); }

    private void loadRecipes() {
        recipes.clear();
        if (!recipesFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(recipesFile);
        ConfigurationSection root = y.getConfigurationSection("recipes");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Recipe r = new Recipe(id, Math.max(1, s.getInt("minutes", defaultMinutes())));
            for (int i = 0; i < 9; i++) r.grid[i] = dec(s.getString("grid." + i, ""));
            r.output = dec(s.getString("output", ""));
            if (r.output != null) recipes.put(id, r);
        }
    }

    public void saveRecipes() {
        YamlConfiguration y = new YamlConfiguration();
        for (Recipe r : recipes()) {
            String p = "recipes." + r.id + ".";
            y.set(p + "minutes", r.minutes);
            y.set(p + "output", enc(r.output));
            for (int i = 0; i < 9; i++) y.set(p + "grid." + i, enc(r.grid[i]));
        }
        pl.sched.async(() -> {
            try { recipesFile.getParentFile().mkdirs(); y.save(recipesFile); } catch (Exception ex) { pl.getLogger().warning("altar recipes save: " + ex); }
        });
    }

    /** Creates or replaces a recipe. */
    public Recipe putRecipe(String id, ItemStack[] grid, ItemStack output, int minutes) {
        if (id == null) {
            int n = 1;
            while (recipes.containsKey("recipe_" + n)) n++;
            id = "recipe_" + n;
        }
        Recipe r = new Recipe(id, Math.max(1, minutes));
        for (int i = 0; i < 9; i++) r.grid[i] = grid[i] == null || grid[i].getType().isAir() ? null : grid[i].clone();
        r.output = output.clone();
        recipes.put(id, r);
        saveRecipes();
        return r;
    }

    public void deleteRecipe(String id) {
        recipes.remove(id);
        saveRecipes();
    }

    /** Recipe for the items in the grid on this altar (the preset's own recipe, or any when the preset has none). */
    public Recipe find(Altar a, ItemStack[] in) {
        String bound = cfg.getString("presets." + a.preset + ".recipe", "");
        if (bound != null && !bound.isBlank()) {
            Recipe r = recipes.get(bound);
            return r != null && r.matches(in) ? r : null;
        }
        for (Recipe r : recipes()) if (r.matches(in)) return r;
        return null;
    }

    public boolean bind(String preset, String recipe) {
        if (!presetNames().contains(preset)) return false;
        cfg.set("presets." + preset + ".recipe", recipe == null ? "" : recipe);
        saveConfig();
        return true;
    }

    public static String itemName(ItemStack it) {
        if (it == null) return "-";
        var meta = it.getItemMeta();
        String n = meta != null && meta.hasDisplayName() ? Text.plain(meta.displayName()) : Util.pretty(it.getType());
        return it.getAmount() > 1 ? n + " x" + it.getAmount() : n;
    }

    // ---------------- altars ----------------
    public Collection<Altar> altars() { return altars.values(); }
    public Altar altar(int id) { return altars.get(id); }

    private static String key(Location l) { return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }
    private static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private List<Object[]> structure(String preset) {
        List<Object[]> out = new ArrayList<>();
        for (String line : cfg.getStringList("presets." + preset + ".blocks")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 4) continue;
            Material mat = Material.matchMaterial(p[3]);
            if (mat == null || !mat.isBlock()) continue;
            try { out.add(new Object[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), mat}); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private Material centerMaterial(String preset) {
        Material m = Material.matchMaterial(cfg.getString("presets." + preset + ".center", "LODESTONE"));
        return m == null || !m.isBlock() ? Material.LODESTONE : m;
    }

    private void index(Altar a) {
        altars.put(a.id, a);
        byCenter.put(key(a.center), a);
        a.blocks.add(key(a.center));
        byBlock.put(key(a.center), a);
        for (Object[] o : structure(a.preset)) {
            Location l = a.center.clone().add((int) o[0], (int) o[1], (int) o[2]);
            a.blocks.add(key(l));
            byBlock.put(key(l), a);
        }
    }

    /** Places the altar of a preset on the block the admin stands in. Returns null for an unknown preset. */
    public Altar spawn(Player p, String preset) {
        if (!presetNames().contains(preset)) return null;
        Location c = p.getLocation().getBlock().getLocation();
        Altar a = new Altar(nextId++, preset, c);
        index(a);
        saveAltars();
        pl.sched.region(c, () -> build(a));
        return a;
    }

    private void build(Altar a) {
        for (Object[] o : structure(a.preset))
            a.center.clone().add((int) o[0], (int) o[1], (int) o[2]).getBlock().setType((Material) o[3], false);
        a.center.getBlock().setType(centerMaterial(a.preset), false);
    }

    public void remove(Altar a) {
        stopRitual(a, false);
        altars.remove(a.id);
        byCenter.remove(key(a.center));
        for (String k : a.blocks) byBlock.remove(k);
        TextDisplay h = a.holo;
        if (h != null) pl.sched.entity(h, h::remove);
        List<Object[]> st = structure(a.preset);
        pl.sched.region(a.center, () -> {
            for (Object[] o : st) a.center.clone().add((int) o[0], (int) o[1], (int) o[2]).getBlock().setType(Material.AIR, false);
            a.center.getBlock().setType(Material.AIR, false);
        });
        saveAltars();
    }

    public Altar nearest(Location l, double radius) {
        Altar best = null;
        double bd = radius * radius;
        for (Altar a : altars.values()) {
            if (a.center.getWorld() != l.getWorld()) continue;
            double d = a.center.distanceSquared(l);
            if (d <= bd) { bd = d; best = a; }
        }
        return best;
    }

    private void saveAltars() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Altar a : altars.values()) {
            String p = "altars." + a.id + ".";
            y.set(p + "preset", a.preset);
            y.set(p + "world", a.center.getWorld().getName());
            y.set(p + "x", a.center.getBlockX()); y.set(p + "y", a.center.getBlockY()); y.set(p + "z", a.center.getBlockZ());
        }
        pl.sched.async(() -> {
            try { placedFile.getParentFile().mkdirs(); y.save(placedFile); } catch (Exception ex) { pl.getLogger().warning("altars save: " + ex); }
        });
    }

    private void loadAltars() {
        altars.clear(); byCenter.clear(); byBlock.clear();
        if (!placedFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(placedFile);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection root = y.getConfigurationSection("altars");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            World w = s == null ? null : Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            try {
                Altar a = new Altar(Integer.parseInt(k), s.getString("preset", "default"), new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z")));
                index(a);
                nextId = Math.max(nextId, a.id + 1);
            } catch (NumberFormatException ignored) { }
        }
    }

    // ---------------- ritual ----------------
    /** Consumes nothing itself: the GUI already took the ingredients. False when this altar is busy. */
    public boolean startRitual(Altar a, Recipe r, Player starter) {
        if (a.ritual != null) return false;
        int total = Math.max(1, r.minutes) * 60;
        Ritual rit = new Ritual(a, r, starter.getName(), total, total);
        a.ritual = rit;
        begin(rit, true);
        saveRituals();
        return true;
    }

    private int revealAt(Ritual r, int i) {
        List<Integer> l = cfg.getIntegerList("ritual.reveal-seconds");
        int base = i < l.size() ? l.get(i) : 0;
        return (int) Math.round(base * (r.total / (double) (defaultMinutes() * 60)));
    }

    private int stageFor(Ritual r) {
        int elapsed = r.total - r.remaining, st = 0;
        for (int i = 0; i < 3; i++) if (elapsed >= revealAt(r, i)) st = i + 1;
        return st;
    }

    /** "1000 0000 0000" where unrevealed numbers are OBFUSCATED zeros (never the real digits). */
    public Component coords(Altar a, int stage) {
        int[] v = {a.center.getBlockX(), a.center.getBlockY(), a.center.getBlockZ()};
        Component out = Component.empty();
        for (int i = 0; i < 3; i++) {
            if (i > 0) out = out.append(Component.text(" "));
            String real = String.valueOf(v[i]);
            out = out.append(i < stage
                    ? Component.text(real, TextColor.color(0xf1d58f))
                    : Component.text("0".repeat(real.length()), NamedTextColor.GRAY).decoration(TextDecoration.OBFUSCATED, true));
        }
        return out.decoration(TextDecoration.ITALIC, false);
    }

    private Map<String, Object> ph(Ritual r) {
        return Text.ph("item", itemName(r.recipe.output), "player", r.starter, "minutes", r.total / 60,
                "time", Util.mmss(r.remaining), "coords", coords(r.altar, r.stage), "id", r.altar.id);
    }

    private void begin(Ritual r, boolean announce) {
        r.stage = announce ? 0 : stageFor(r);
        ConfigurationSection bb = cfg.getConfigurationSection("ritual.bossbar");
        BossBar.Color col = com.eventcore.util.BossBars.enumOf(BossBar.Color.class, bb == null ? "PURPLE" : bb.getString("color", "PURPLE"), BossBar.Color.PURPLE);
        BossBar.Overlay ov = com.eventcore.util.BossBars.enumOf(BossBar.Overlay.class, bb == null ? "PROGRESS" : bb.getString("style", "PROGRESS"), BossBar.Overlay.PROGRESS);
        r.bar = BossBar.bossBar(Component.empty(), com.eventcore.util.BossBars.clamp(r.remaining / (double) r.total), col, ov);
        updateBar(r);
        pl.bars.show(r.bar);
        if (announce) {
            pl.fx.playAll(cfg.getConfigurationSection("sounds.start"));
            Bukkit.getServer().sendMessage(m("started-broadcast", ph(r)));
            checkReveal(r);
        }
    }

    private void updateBar(Ritual r) {
        r.bar.name(pl.text.render(cfg.getString("messages.bossbar", ""), ph(r)));
        r.bar.progress(com.eventcore.util.BossBars.clamp(r.remaining / (double) r.total));
    }

    private void checkReveal(Ritual r) {
        int st = stageFor(r);
        if (st <= r.stage) return;
        r.stage = st;
        Map<String, Object> ph = ph(r);
        Bukkit.getServer().sendMessage(m("reveal-broadcast", ph));
        pl.fx.title(Bukkit.getServer(), cfg.getConfigurationSection("messages.reveal-title"), ph);
        pl.fx.playAll(cfg.getConfigurationSection("sounds.reveal"));
    }

    private void complete(Ritual r) {
        Altar a = r.altar;
        a.ritual = null;
        pl.bars.hide(r.bar);
        r.stage = 3;
        Location drop = a.center.clone().add(0.5, 1.2, 0.5);
        pl.sched.region(drop, () -> {
            drop.getWorld().dropItem(drop, r.recipe.output.clone());
            drop.getWorld().spawnParticle(Particle.END_ROD, drop, 40, 0.4, 0.4, 0.4, 0.05);
        });
        pl.fx.playAll(cfg.getConfigurationSection("sounds.complete"));
        Bukkit.getServer().sendMessage(m("complete-broadcast", ph(r)));
        pl.discord.ritualComplete(itemName(r.recipe.output));
        saveRituals();
    }

    /** Cancels the ritual of an altar (ingredients are not returned). */
    public boolean stopRitual(Altar a, boolean announce) {
        Ritual r = a.ritual;
        if (r == null) return false;
        a.ritual = null;
        pl.bars.hide(r.bar);
        if (announce) {
            pl.fx.playAll(cfg.getConfigurationSection("sounds.stop"));
            Bukkit.getServer().sendMessage(m("stopped-broadcast", Map.of()));
        }
        saveRituals();
        return true;
    }

    public int stopAll() {
        int n = 0;
        for (Altar a : altars.values()) if (stopRitual(a, true)) n++;
        return n;
    }

    // ---------------- ticking ----------------
    private void tick() {
        boolean save = ++saveTick % Math.max(1, cfg.getInt("ritual.save-interval-seconds", 10)) == 0;
        for (Altar a : altars.values()) {
            try {
                Ritual r = a.ritual;
                if (r != null) {
                    r.remaining--;
                    checkReveal(r);
                    if (r.remaining <= 0) complete(r);
                    else updateBar(r);
                }
                updateHolo(a);
            } catch (Throwable ex) {
                pl.getLogger().warning("Altar #" + a.id + ": " + ex);
            }
        }
        if (save) saveRituals();
    }

    // ---------------- hologram ----------------
    private Component holoText(Altar a) {
        ConfigurationSection ps = cfg.getConfigurationSection("presets." + a.preset);
        Component c = pl.text.render(ps == null ? "altar" : ps.getString("title", "altar"), Map.of());
        String bound = ps == null ? "" : ps.getString("recipe", "");
        Recipe rec = bound == null || bound.isBlank() ? null : recipes.get(bound);
        if (rec != null) {
            for (int row = 0; row < 3; row++) {
                StringBuilder sb = new StringBuilder();
                for (int col = 0; col < 3; col++) {
                    if (col > 0) sb.append("  |  ");
                    ItemStack it = rec.grid[row * 3 + col];
                    sb.append(it == null ? "-" : itemName(it));
                }
                c = c.append(Component.newline()).append(Component.text(sb.toString(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            c = c.append(Component.newline()).append(Component.text("-> " + itemName(rec.output), TextColor.color(0xf1d58f)).decoration(TextDecoration.ITALIC, false));
        }
        Ritual r = a.ritual;
        Component foot = r != null
                ? pl.text.render(cfg.getString("messages.hologram-busy", ""), Text.ph("time", Util.mmss(r.remaining)))
                : pl.text.render(cfg.getString(rec != null ? "messages.hologram-idle" : "messages.hologram-any", ""), Map.of());
        return c.append(Component.newline()).append(foot);
    }

    private void updateHolo(Altar a) {
        if (!cfg.getBoolean("hologram.enabled", true)) return;
        TextDisplay h = a.holo;
        if (h == null || !h.isValid()) {
            if (a.holoSpawning) return;
            a.holoSpawning = true;
            pl.sched.region(a.center, () -> {
                try {
                    if (!a.center.getWorld().isChunkLoaded(a.center.getBlockX() >> 4, a.center.getBlockZ() >> 4)) return;
                    double height = cfg.getDouble("hologram.height", 2.3);
                    float sc = (float) cfg.getDouble("hologram.scale", 1.0);
                    a.holo = a.center.getWorld().spawn(a.center.clone().add(0.5, height, 0.5), TextDisplay.class, d -> {
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setPersistent(false);
                        d.setSeeThrough(false);
                        d.setShadowed(true);
                        d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                        d.setAlignment(TextDisplay.TextAlignment.CENTER);
                        d.setLineWidth(300);
                        d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
                        d.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
                    });
                    a.lastHolo = "";
                } catch (Throwable ex) {
                    pl.getLogger().warning("Altar hologram: " + ex);
                } finally { a.holoSpawning = false; }
            });
            return;
        }
        Component text = holoText(a);
        String sig = Text.plain(text);
        if (sig.equals(a.lastHolo)) return;
        a.lastHolo = sig;
        pl.sched.entity(h, () -> h.text(text));
    }

    // ---------------- persistence of running rituals ----------------
    private YamlConfiguration ritualsSnapshot() {
        YamlConfiguration y = new YamlConfiguration();
        for (Altar a : altars.values()) {
            Ritual r = a.ritual;
            if (r == null) continue;
            String p = "rituals." + a.id + ".";
            y.set(p + "recipe", r.recipe.id);
            y.set(p + "remaining", r.remaining);
            y.set(p + "total", r.total);
            y.set(p + "starter", r.starter);
        }
        return y;
    }

    private void saveRituals() {
        YamlConfiguration y = ritualsSnapshot();
        pl.sched.async(() -> {
            try { ritualsFile.getParentFile().mkdirs(); y.save(ritualsFile); } catch (Exception ex) { pl.getLogger().warning("altar rituals save: " + ex); }
        });
    }

    private void saveRitualsBlocking() {
        try { ritualsFile.getParentFile().mkdirs(); ritualsSnapshot().save(ritualsFile); } catch (Exception ex) { pl.getLogger().warning("altar rituals save: " + ex); }
    }

    private void restoreRituals() {
        if (!ritualsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(ritualsFile);
        ConfigurationSection root = y.getConfigurationSection("rituals");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            try {
                Altar a = altars.get(Integer.parseInt(k));
                Recipe rec = recipes.get(root.getString(k + ".recipe", ""));
                if (a == null || rec == null) continue;
                int total = Math.max(1, root.getInt(k + ".total", rec.minutes * 60));
                Ritual r = new Ritual(a, rec, root.getString(k + ".starter", "?"), total, Math.max(1, root.getInt(k + ".remaining", total)));
                a.ritual = r;
                begin(r, false);
            } catch (NumberFormatException ignored) { }
        }
    }

    // ---------------- listeners ----------------
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Altar a = byCenter.get(key(b));
        if (a == null) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("eventcore.altar.use")) return;
        gui.openCraft(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (byBlock.containsKey(key(e.getBlock()))) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(m("protected", Map.of()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> byBlock.containsKey(key(b))); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> byBlock.containsKey(key(b))); }
}
