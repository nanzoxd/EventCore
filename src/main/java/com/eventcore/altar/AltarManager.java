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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom altar crafting. Recipes (3x3 grid + output) are edited in a GUI, altars are placed from presets with
 * /altar spawn, a hologram above each altar shows its recipe. Crafting starts a ritual (30 min by default) whose
 * coordinates are revealed one number at a time (X at the start, Y after 10 min, Z after 15 min); numbers that are
 * not revealed yet are sent as obfuscated filler digits, never as the real value.
 */
public final class AltarManager implements Listener {

    // ---------------- data ----------------
    /** One thing a recipe consumes. Matches by material (+ optional name / custom model data), or exactly (held item). */
    public static final class Ingredient {
        public final Material type;
        public final int amount;
        public final String rawName;     // MiniMessage name as written in the yaml (null = any)
        final String name;               // plain version used for matching
        public final Integer cmd;
        public final ItemStack exact;    // not null = must be similar to this item (name, lore, model, enchants...)

        Ingredient(Material type, int amount, String rawName, Integer cmd, ItemStack exact) {
            this.type = type; this.amount = Math.max(1, amount); this.rawName = rawName; this.cmd = cmd; this.exact = exact;
            this.name = rawName == null ? null : Text.plain(MiniMessage.miniMessage().deserialize(rawName));
        }

        public boolean matches(ItemStack s) {
            if (s == null || s.getType().isAir()) return false;
            if (exact != null) return s.isSimilar(exact);
            if (s.getType() != type) return false;
            ItemMeta m = s.getItemMeta();
            if (name != null && (m == null || !m.hasDisplayName() || !Text.plain(m.displayName()).equalsIgnoreCase(name))) return false;
            if (cmd != null && (m == null || !m.hasCustomModelData() || m.getCustomModelData() != cmd)) return false;
            return true;
        }

        public String label() {
            if (exact != null) return itemName(exact.asOne());
            return name != null ? name : Util.pretty(type);
        }

        public String line() { return (amount > 1 ? amount + "x " : "") + label(); }
    }

    /** Ingredients (items from the player's inventory) -> one output item, after a ritual of N minutes. */
    public static final class Recipe {
        public final String id;
        public final List<Ingredient> inputs = new ArrayList<>();
        public ItemStack output;
        public int minutes;
        public String name = "";     // hologram title (MiniMessage); empty = the output's name
        public String altar = "";    // restrict to one altar preset; empty = any altar
        Map<String, Object> outRaw;  // output as written in the yaml (kept so a re-save stays readable)

        Recipe(String id, int minutes) { this.id = id; this.minutes = minutes; }

        static int count(PlayerInventory inv, Ingredient in) {
            int n = 0;
            for (ItemStack s : inv.getStorageContents()) if (in.matches(s)) n += s.getAmount();
            return n;
        }

        public boolean craftable(PlayerInventory inv) {
            if (inputs.isEmpty()) return false;
            for (Ingredient in : inputs) if (count(inv, in) < in.amount) return false;
            return true;
        }

        public List<String> missing(PlayerInventory inv) {
            List<String> out = new ArrayList<>();
            for (Ingredient in : inputs) {
                int have = count(inv, in);
                if (have < in.amount) out.add("missing " + (in.amount - have) + "x " + in.label());
            }
            return out;
        }

        public void take(PlayerInventory inv) {
            for (Ingredient in : inputs) {
                int left = in.amount;
                ItemStack[] c = inv.getStorageContents();
                for (int i = 0; i < c.length && left > 0; i++) {
                    ItemStack s = c[i];
                    if (!in.matches(s)) continue;
                    int t = Math.min(left, s.getAmount());
                    if (t >= s.getAmount()) c[i] = null; else s.setAmount(s.getAmount() - t);
                    left -= t;
                }
                inv.setStorageContents(c);
            }
        }

        public String inputLine() {
            StringBuilder sb = new StringBuilder();
            for (Ingredient in : inputs) { if (sb.length() > 0) sb.append(" + "); sb.append(in.line()); }
            return sb.toString();
        }
    }

    public final class Altar {
        public final int id;
        public final String preset;
        public final Location center;
        final Set<String> blocks = ConcurrentHashMap.newKeySet();
        volatile TextDisplay holo;
        volatile boolean holoSpawning;
        volatile ItemDisplay floating;
        volatile boolean floatSpawning;
        volatile int removeIn = -1;     // seconds until the finished altar vanishes (-1 = not closing)
        public volatile String recipeId = "";   // altar placed with /altar spawn <recipe>: crafts only this recipe
        volatile ItemStack floatSource; // the recipe output the floating item currently shows
        int tick;                       // seconds the floating item has been shown (bobbing / recipe cycling)
        float spin;
        String lastHolo = "";
        volatile Ritual ritual;

        Altar(int id, String preset, Location center) { this.id = id; this.preset = preset; this.center = center; }

        public boolean busy() { return ritual != null || removeIn >= 0; }
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
    private ScheduledTask fxTicker;
    private int saveTick;

    public AltarManager(EventCorePlugin pl) {
        this.pl = pl;
        this.KEY = new NamespacedKey(pl, "altar_holo");
        File data = new File(pl.getDataFolder(), "data");
        this.cfgFile = new File(pl.getDataFolder(), "altars.yml");
        this.recipesFile = new File(pl.getDataFolder(), "altar-recipes.yml");
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

    /** The structure used by /altar spawn <recipe>: preset "default" or the first preset. */
    public String defaultPreset() {
        Set<String> n = presetNames();
        return n.contains("default") ? "default" : n.isEmpty() ? null : n.iterator().next();
    }

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
        fxTicker = pl.sched.globalTimer(t -> fxTick(), 20, Math.max(1, cfg.getInt("ritual.particles.interval-ticks", 4)));
        restoreRituals();
    }

    public void reload() {
        loadConfig();
        loadRecipes();
    }

    public void shutdown() {
        Sched.cancel(ticker);
        Sched.cancel(fxTicker);
        saveRitualsBlocking();
        for (Altar a : altars.values()) {
            Ritual r = a.ritual;
            if (r != null) pl.bars.hide(r.bar);
            TextDisplay h = a.holo;
            if (h != null) { try { h.remove(); } catch (Throwable ignored) { } }
            ItemDisplay fl = a.floating;
            if (fl != null) { try { fl.remove(); } catch (Throwable ignored) { } }
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

    private static Component noItalic(Component c) { return c.decoration(TextDecoration.ITALIC, false); }

    /** Builds an item from a yaml section: `item` (base64 from /altar recipe output) or material/amount/name/lore/custom-model-data/enchants. */
    private ItemStack buildItem(ConfigurationSection s) {
        ItemStack held = dec(s.getString("item", ""));
        if (held != null) { if (s.contains("amount")) held.setAmount(Math.max(1, s.getInt("amount"))); return held; }
        Material m = Material.matchMaterial(s.getString("material", ""));
        if (m == null || m.isAir()) return null;
        ItemStack it = new ItemStack(m, Math.max(1, s.getInt("amount", 1)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            MiniMessage mm = MiniMessage.miniMessage();
            if (s.contains("name")) meta.displayName(noItalic(mm.deserialize(s.getString("name", ""))));
            List<String> lore = s.getStringList("lore");
            if (!lore.isEmpty()) { List<Component> l = new ArrayList<>(); for (String x : lore) l.add(noItalic(mm.deserialize(x))); meta.lore(l); }
            if (s.contains("custom-model-data")) meta.setCustomModelData(s.getInt("custom-model-data"));
            if (s.getBoolean("unbreakable", false)) meta.setUnbreakable(true);
            ConfigurationSection en = s.getConfigurationSection("enchants");
            if (en != null) for (String k : en.getKeys(false)) {
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase(Locale.ROOT)));
                if (e != null) meta.addEnchant(e, Math.max(1, en.getInt(k)), true);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private Ingredient buildIngredient(ConfigurationSection s) {
        int amt = Math.max(1, s.getInt("amount", 1));
        ItemStack held = dec(s.getString("item", ""));
        if (held != null) { ItemStack one = held.clone(); one.setAmount(1); return new Ingredient(one.getType(), amt, null, null, one); }
        Material m = Material.matchMaterial(s.getString("material", ""));
        if (m == null || m.isAir()) return null;
        return new Ingredient(m, amt, s.contains("name") ? s.getString("name") : null,
                s.contains("custom-model-data") ? s.getInt("custom-model-data") : null, null);
    }

    private void loadRecipes() {
        recipes.clear();
        if (!recipesFile.exists()) pl.saveResource("altar-recipes.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(recipesFile);
        ConfigurationSection root = y.getConfigurationSection("recipes");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Recipe r = new Recipe(id, Math.max(1, s.getInt("minutes", defaultMinutes())));
            r.name = s.getString("name", "");
            r.altar = s.getString("altar", "");
            ConfigurationSection out = s.getConfigurationSection("output");
            if (out != null) {
                r.output = buildItem(out);
                Map<String, Object> raw = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : out.getValues(true).entrySet()) if (!(e.getValue() instanceof ConfigurationSection)) raw.put(e.getKey(), e.getValue());
                r.outRaw = raw;
            }
            for (Object o : s.getList("inputs", List.of())) {
                if (!(o instanceof Map<?, ?> mp)) continue;
                YamlConfiguration t = new YamlConfiguration();
                for (Map.Entry<?, ?> e : mp.entrySet()) t.set(String.valueOf(e.getKey()), e.getValue());
                Ingredient in = buildIngredient(t);
                if (in != null) r.inputs.add(in);
            }
            if (r.output != null && !r.inputs.isEmpty()) recipes.put(id, r);
            else pl.getLogger().warning("altar recipe '" + id + "' needs an output and at least one valid input - skipped");
        }
    }

    public void saveRecipes() {
        YamlConfiguration y = new YamlConfiguration();
        for (Recipe r : recipes()) {
            String p = "recipes." + r.id + ".";
            y.set(p + "name", r.name);
            y.set(p + "altar", r.altar);
            y.set(p + "minutes", r.minutes);
            List<Map<String, Object>> ins = new ArrayList<>();
            for (Ingredient in : r.inputs) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (in.exact != null) m.put("item", enc(in.exact));
                else {
                    m.put("material", in.type.name());
                    if (in.rawName != null) m.put("name", in.rawName);
                    if (in.cmd != null) m.put("custom-model-data", in.cmd);
                }
                m.put("amount", in.amount);
                ins.add(m);
            }
            y.set(p + "inputs", ins);
            if (r.outRaw != null) for (Map.Entry<String, Object> e : r.outRaw.entrySet()) y.set(p + "output." + e.getKey(), e.getValue());
            else if (r.output != null) { y.set(p + "output.item", enc(r.output)); y.set(p + "output.amount", r.output.getAmount()); }
        }
        pl.sched.async(() -> {
            try { recipesFile.getParentFile().mkdirs(); y.save(recipesFile); } catch (Exception ex) { pl.getLogger().warning("altar recipes save: " + ex); }
        });
    }

    public Recipe createRecipe(String id, int minutes) {
        Recipe r = new Recipe(id, Math.max(1, minutes));
        recipes.put(id, r);
        return r;
    }

    /** Output = the item in the admin's hand (kept exactly: name, lore, model data, enchants). */
    public void setOutput(Recipe r, ItemStack held) { r.output = held.clone(); r.outRaw = null; saveRecipes(); }
    public void addInput(Recipe r, Ingredient in) { r.inputs.add(in); saveRecipes(); }
    public Ingredient inputFromHeld(ItemStack held, int amount) { ItemStack one = held.clone(); one.setAmount(1); return new Ingredient(one.getType(), amount, null, null, one); }
    public Ingredient inputFromMaterial(Material m, int amount) { return new Ingredient(m, amount, null, null, null); }
    public void clearInputs(Recipe r) { r.inputs.clear(); saveRecipes(); }
    public void deleteRecipe(String id) { recipes.remove(id); saveRecipes(); }

    // ---- helpers for the recipe GUI (AltarGui) ----
    /** Plain items (no custom data) match by material, anything with custom data must match exactly. */
    public Ingredient fromStack(ItemStack s) {
        int amt = Math.max(1, s.getAmount());
        if (!s.hasItemMeta()) return new Ingredient(s.getType(), amt, null, null, null);
        ItemStack one = s.clone(); one.setAmount(1);
        return new Ingredient(s.getType(), amt, null, null, one);
    }

    public Ingredient withAmount(Ingredient in, int amount) { return new Ingredient(in.type, amount, in.rawName, in.cmd, in.exact); }

    /** What an ingredient looks like in the editor grid (amount capped at 64 for display). */
    public ItemStack stackOf(Ingredient in) {
        int amt = Math.max(1, Math.min(64, in.amount));
        if (in.exact != null) { ItemStack s = in.exact.clone(); s.setAmount(amt); return s; }
        ItemStack s = new ItemStack(in.type.isItem() ? in.type : Material.BARRIER, amt);
        if (in.rawName != null || in.cmd != null) {
            ItemMeta meta = s.getItemMeta();
            if (meta != null) {
                if (in.rawName != null) meta.displayName(noItalic(MiniMessage.miniMessage().deserialize(in.rawName)));
                if (in.cmd != null) meta.setCustomModelData(in.cmd);
                s.setItemMeta(meta);
            }
        }
        return s;
    }

    /** Creates or overwrites a recipe from the editor. */
    public Recipe saveRecipe(String id, List<Ingredient> ins, ItemStack out, int minutes) {
        Recipe r = recipes.get(id);
        if (r == null) r = createRecipe(id, minutes);
        r.minutes = Math.max(1, minutes);
        r.inputs.clear();
        r.inputs.addAll(ins);
        r.output = out.clone();
        r.outRaw = null;
        saveRecipes();
        return r;
    }

    /** Recipes this altar offers: the preset's bound recipe, or every recipe not restricted to another preset. */
    public List<Recipe> recipesFor(Altar a) {
        List<Recipe> out = new ArrayList<>();
        if (a.recipeId != null && !a.recipeId.isBlank()) {   // /altar spawn <recipe>: this altar = this one recipe
            Recipe own = recipes.get(a.recipeId);
            if (own != null) out.add(own);
            return out;
        }
        String bound = cfg.getString("presets." + a.preset + ".recipe", "");
        if (bound != null && !bound.isBlank()) {
            Recipe r = recipes.get(bound);
            if (r != null) out.add(r);
            return out;
        }
        for (Recipe r : recipes()) if (r.output != null && (r.altar == null || r.altar.isBlank() || r.altar.equalsIgnoreCase(a.preset))) out.add(r);
        return out;
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

    /** Item name without the amount. */
    public static String plainName(ItemStack it) {
        var meta = it.getItemMeta();
        return meta != null && meta.hasDisplayName() ? Text.plain(meta.displayName()) : Util.pretty(it.getType());
    }

    /** "[64] Diamond, [2] Netherite Ingot" - the outputs an altar crafts (shown in /altar spawn). */
    public String outputNames(Altar a) {
        List<String> n = new ArrayList<>();
        for (Recipe r : recipesFor(a)) if (r.output != null) n.add("[" + Math.max(1, r.output.getAmount()) + "] " + plainName(r.output));
        return n.isEmpty() ? "no recipe set" : String.join(", ", n);
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
        Material m = Material.matchMaterial(cfg.getString("presets." + preset + ".center", "STRUCTURE_BLOCK"));
        return m == null || !m.isBlock() ? Material.STRUCTURE_BLOCK : m;
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
    public Altar spawn(Player p, String arg) {
        String preset = null, rid = "";
        if (arg == null || arg.isBlank()) preset = defaultPreset();
        else {
            for (String n : presetNames()) if (n.equalsIgnoreCase(arg)) preset = n;
            if (preset == null) {                       // /altar spawn <recipe id>: default structure, crafts only that recipe
                for (Recipe r : recipes()) if (r.id.equalsIgnoreCase(arg)) rid = r.id;
                if (!rid.isEmpty()) preset = defaultPreset();
            }
        }
        if (preset == null || !presetNames().contains(preset)) return null;
        Location c = p.getLocation().getBlock().getLocation();
        Altar a = new Altar(nextId++, preset, c);
        a.recipeId = rid;
        index(a);
        saveAltars();
        pl.sched.region(c, () -> build(a));
        return a;
    }

    private void build(Altar a) {
        for (Object[] o : structure(a.preset))
            a.center.clone().add((int) o[0], (int) o[1], (int) o[2]).getBlock().setType((Material) o[3], false);
        Block cb = a.center.getBlock();
        cb.setType(centerMaterial(a.preset), false);
        // structure block: LOAD mode is the one the resource pack turns into the ice altar
        if (cb.getBlockData() instanceof org.bukkit.block.data.type.StructureBlock sb) {
            sb.setMode(org.bukkit.block.data.type.StructureBlock.Mode.LOAD);
            cb.setBlockData(sb, false);
        }
    }

    public void remove(Altar a) {
        stopRitual(a, false);
        altars.remove(a.id);
        byCenter.remove(key(a.center));
        for (String k : a.blocks) byBlock.remove(k);
        TextDisplay h = a.holo;
        if (h != null) pl.sched.entity(h, h::remove);
        ItemDisplay fl = a.floating;
        if (fl != null) pl.sched.entity(fl, fl::remove);
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
            y.set(p + "recipe", a.recipeId == null ? "" : a.recipeId);
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
                a.recipeId = s.getString("recipe", "");
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
        int rm = cfg.getInt("ritual.remove-after-seconds", 60);
        a.removeIn = cfg.getBoolean("ritual.remove-altar", true) && rm >= 0 ? rm : -1;
        ItemDisplay fl = a.floating;
        a.floating = null;
        if (fl != null) pl.sched.entity(fl, fl::remove);
        Location drop = a.center.clone().add(0.5, cfg.getDouble("ritual.drop-height", 1.6), 0.5);
        pl.sched.region(drop, () -> {
            World w = drop.getWorld();
            w.dropItem(drop, r.recipe.output.clone(), it -> { it.setVelocity(new Vector(0, 0.08, 0)); it.setGlowing(true); });
            w.spawnParticle(Particle.END_ROD, drop, 40, 0.4, 0.4, 0.4, 0.05);
        });
        pl.fx.playAll(cfg.getConfigurationSection("sounds.complete"));
        Bukkit.getServer().sendMessage(m("complete-broadcast", ph(r)));
        pl.discord.ritualComplete(itemName(r.recipe.output));
        saveRituals();
    }

    private void closeAltar(Altar a) {
        Location l = a.center.clone().add(0.5, 1.0, 0.5);
        pl.sched.region(l, () -> {
            l.getWorld().spawnParticle(Particle.SNOWFLAKE, l, 60, 1, 1, 1, 0.02);
            l.getWorld().playSound(l, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 0.6f);
        });
        remove(a);
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
                } else if (a.removeIn >= 0) {
                    if (a.removeIn == 0) { closeAltar(a); continue; }
                    a.removeIn--;
                }
                updateHolo(a);
                updateFloating(a);
            } catch (Throwable ex) {
                pl.getLogger().warning("Altar #" + a.id + ": " + ex);
            }
        }
        if (save) saveRituals();
    }

    // ---------------- ritual particles ----------------
    /** While a ritual runs: snow falls over the whole ritual area and enchanting glyphs swirl around the floating item. */
    private void fxTick() {
        if (!cfg.getBoolean("ritual.particles.enabled", true)) return;
        for (Altar a : altars.values()) {
            if (a.ritual == null) continue;
            pl.sched.region(a.center, () -> {
                try { ritualParticles(a); } catch (Throwable ignored) { }
            });
        }
    }

    private void ritualParticles(Altar a) {
        World w = a.center.getWorld();
        if (w == null || !w.isChunkLoaded(a.center.getBlockX() >> 4, a.center.getBlockZ() >> 4)) return;
        double radius = Math.max(1, cfg.getDouble("ritual.particles.radius", 6.0));
        double height = cfg.getDouble("ritual.particles.height", 4.0);
        int snow = cfg.getInt("ritual.particles.snow", 14), ench = cfg.getInt("ritual.particles.enchant", 6);
        double cx = a.center.getX() + 0.5, cy = a.center.getY(), cz = a.center.getZ() + 0.5;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < snow; i++) {   // random spots inside the circle, drifting down
            double ang = r.nextDouble() * Math.PI * 2, d = radius * Math.sqrt(r.nextDouble());
            w.spawnParticle(Particle.SNOWFLAKE, cx + Math.cos(ang) * d, cy + height + r.nextDouble() * 1.5, cz + Math.sin(ang) * d,
                    0, (r.nextDouble() - 0.5) * 0.2, -0.6, (r.nextDouble() - 0.5) * 0.2, 0.15);
        }
        if (ench > 0)
            w.spawnParticle(Particle.ENCHANT, cx, cy + cfg.getDouble("display.height", 1.5) + 0.3, cz, ench, 1.5, 0.8, 1.5, 0.5);
    }

    // ---------------- floating output item ----------------
    /** The recipe whose output floats above the altar: the running ritual's, otherwise the altar's recipe (cycling if there are several). */
    private Recipe showcase(Altar a) {
        Ritual r = a.ritual;
        if (r != null) return r.recipe;
        if (a.removeIn >= 0 || !cfg.getBoolean("display.idle", true)) return null;
        List<Recipe> l = recipesFor(a);
        if (l.isEmpty()) return null;
        int every = Math.max(1, cfg.getInt("display.cycle-seconds", 4));
        return l.get((a.tick / every) % l.size());
    }

    private void updateFloating(Altar a) {
        Recipe rec = showcase(a);
        ItemDisplay d = a.floating;
        if (rec == null || rec.output == null) {
            if (d != null) { a.floating = null; a.floatSource = null; pl.sched.entity(d, d::remove); }
            return;
        }
        a.tick++;
        final ItemStack out = rec.output;
        float sc = (float) cfg.getDouble("display.scale", 1.0);
        if (d == null || !d.isValid()) {
            if (a.floatSpawning) return;
            a.floatSpawning = true;
            pl.sched.region(a.center, () -> {
                try {
                    if (!a.center.getWorld().isChunkLoaded(a.center.getBlockX() >> 4, a.center.getBlockZ() >> 4)) return;
                    double h = cfg.getDouble("display.height", 1.5);
                    a.floating = a.center.getWorld().spawn(a.center.clone().add(0.5, h, 0.5), ItemDisplay.class, e -> {
                        e.setItemStack(out.clone());
                        e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                        e.setBillboard(Display.Billboard.FIXED);
                        e.setPersistent(false);
                        e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
                        e.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
                    });
                    a.floatSource = out;
                } catch (Throwable ex) {
                    pl.getLogger().warning("Altar floating item: " + ex);
                } finally { a.floatSpawning = false; }
            });
            return;
        }
        final boolean swap = a.floatSource != out;   // recipe changed (cycle, ritual start, GUI edit)
        if (swap) a.floatSource = out;
        a.spin += (float) (Math.PI / 3);
        final float spin = a.spin;
        final float bob = (float) (Math.sin(a.tick * 0.6) * 0.08);
        pl.sched.entity(d, () -> {
            if (swap) d.setItemStack(out.clone());
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(20);
            d.setTransformation(new Transformation(new Vector3f(0, bob, 0), new AxisAngle4f(spin, 0, 1, 0), new Vector3f(sc, sc, sc), new AxisAngle4f()));
        });
    }

    // ---------------- hologram ----------------
    /**
     * "[64] Diamond": the amount in brackets, then the item's own name exactly as it is on the item (gradient, colours,
     * bold...). Items without a custom name (or with a plain uncoloured one) get a bold name.
     */
    private Component nameOf(ItemStack out) {
        ItemMeta m = out.getItemMeta();
        Component name;
        if (m != null && m.hasDisplayName() && m.displayName() != null) {
            name = m.displayName().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            if (name.color() == null && name.children().isEmpty() && name.decoration(TextDecoration.BOLD) == TextDecoration.State.NOT_SET)
                name = name.decoration(TextDecoration.BOLD, true);
        } else {
            name = Component.text(Util.pretty(out.getType()), NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false);
        }
        return name;
    }

    /** Ingredient line: "[4] Netherite Ingot". */
    private Component itemLine(ItemStack out, int count) {
        Component name = nameOf(out);
        Component amount = Component.text("[" + Math.max(1, count) + "] ", TextColor.color(0xf1d58f))
                .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
        return Component.empty().append(amount).append(name);
    }

    /** The output name (no amount) with a line under it. */
    private Component outputBlock(Component c, ItemStack out) {
        c = c.append(Component.newline()).append(nameOf(out));
        if (cfg.getBoolean("hologram.underline", true)) {
            // spaces with strikethrough draw a solid line, sized to the name
            int n = Math.max(8, Math.min(30, (int) Math.ceil(plainName(out).length() * 1.4) + 2));
            c = c.append(Component.newline()).append(Component.text(" ".repeat(n), TextColor.color(0xf1d58f))
                    .decoration(TextDecoration.STRIKETHROUGH, true).decoration(TextDecoration.ITALIC, false));
        }
        return c;
    }

    private Component holoText(Altar a) {
        ConfigurationSection ps = cfg.getConfigurationSection("presets." + a.preset);
        Component c = pl.text.render(ps == null ? "altar" : ps.getString("title", "altar"), Map.of());
        Ritual r = a.ritual;
        if (r != null) {
            return outputBlock(c, r.recipe.output)
                    .append(Component.newline()).append(pl.text.render(cfg.getString("messages.hologram-busy", ""), Text.ph("time", Util.mmss(r.remaining))));
        }
        if (a.removeIn >= 0) return c.append(Component.newline()).append(pl.text.render(cfg.getString("messages.hologram-closing", ""), Map.of()));
        int shown = 0, max = Math.max(1, cfg.getInt("hologram.max-recipes", 6));
        List<Recipe> list = recipesFor(a);
        for (Recipe rec : list) {
            if (rec.output == null) continue;
            if (shown++ >= max) break;
            if (shown > 1) c = c.append(Component.newline());   // blank line between recipes
            c = outputBlock(c, rec.output);
            // one ingredient per line, same "[amount] Name" format: [4] Netherite Ingot / [1] Nether Star
            for (Ingredient in : rec.inputs) c = c.append(Component.newline()).append(itemLine(stackOf(in), in.amount));
        }
        String foot = list.isEmpty() ? "messages.hologram-empty" : "messages.hologram-idle";
        return c.append(Component.newline()).append(pl.text.render(cfg.getString(foot, ""), Map.of()));
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
        String sig = MiniMessage.miniMessage().serialize(text);   // includes colours/gradients, so a restyled name refreshes too
        if (sig.equals(a.lastHolo)) return;
        a.lastHolo = sig;
        pl.sched.entity(h, () -> h.text(text));
    }

    // ---------------- persistence of running rituals ----------------
    private YamlConfiguration ritualsSnapshot() {
        YamlConfiguration y = new YamlConfiguration();
        for (Altar a : altars.values()) {
            Ritual r = a.ritual;
            if (r == null) { if (a.removeIn >= 0) y.set("cleanup." + a.id, a.removeIn); continue; }
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
        ConfigurationSection cl = y.getConfigurationSection("cleanup");
        if (cl != null) for (String k : cl.getKeys(false)) {
            try { Altar a = altars.get(Integer.parseInt(k)); if (a != null) a.removeIn = Math.max(0, cl.getInt(k)); } catch (NumberFormatException ignored) { }
        }
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
        craftAt(e.getPlayer(), a);
    }

    /** Right-click on the altar: uses the first recipe whose items are all in the player's inventory. */
    private void craftAt(Player p, Altar a) {
        if (a.busy()) { p.sendMessage(m("busy", Map.of())); return; }
        List<Recipe> list = recipesFor(a);
        if (list.isEmpty()) { p.sendMessage(m("no-recipes", Map.of())); return; }
        PlayerInventory inv = p.getInventory();
        Recipe use = null;
        for (Recipe r : list) if (r.craftable(inv)) { use = r; break; }
        if (use == null) {
            p.sendMessage(m("missing", Map.of()));
            if (list.size() == 1) for (String line : list.get(0).missing(inv)) p.sendMessage(Component.text(" - " + line, NamedTextColor.GRAY));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        use.take(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.2f);
        startRitual(a, use, p);
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
