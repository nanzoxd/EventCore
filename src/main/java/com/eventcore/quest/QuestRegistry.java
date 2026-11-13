package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** quests.yml: categories, settings and every quest. The admin GUI edits the same file. */
public final class QuestRegistry {
    public record Category(String id, String name, Material icon) { }

    private final EventCorePlugin pl;
    private final File file;
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private volatile Map<QuestType, List<Quest>> byType = new EnumMap<>(QuestType.class);
    private volatile Set<String> mineTargets = Set.of();
    public volatile boolean autoStart = true, allowAbandon = true, ignoreCreative = true;
    public volatile int maxActive = 12;

    public QuestRegistry(EventCorePlugin pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "quests.yml");
    }

    public synchronized void load() {
        if (!file.exists()) pl.saveResource("quests.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        autoStart = y.getBoolean("settings.auto-start", true);
        allowAbandon = y.getBoolean("settings.allow-abandon", true);
        ignoreCreative = y.getBoolean("settings.ignore-creative", true);
        maxActive = Math.max(1, y.getInt("settings.max-active", 12));
        categories.clear();
        ConfigurationSection cs = y.getConfigurationSection("categories");
        if (cs != null) for (String k : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(k);
            if (c == null) continue;
            Material m = Material.matchMaterial(c.getString("icon", "BOOK"));
            categories.put(k.toLowerCase(Locale.ROOT), new Category(k.toLowerCase(Locale.ROOT), c.getString("name", Quest.pretty(k)), m == null ? Material.BOOK : m));
        }
        quests.clear();
        ConfigurationSection qs = y.getConfigurationSection("quests");
        int order = 0;
        if (qs != null) for (String k : qs.getKeys(false)) {
            ConfigurationSection s = qs.getConfigurationSection(k);
            if (s == null) continue;
            QuestType t = QuestType.of(s.getString("type", ""));
            if (t == null) { pl.getLogger().warning("quests.yml: quest " + k + " has an unknown type"); continue; }
            Quest q = new Quest(k.toLowerCase(Locale.ROOT), s.getString("title", Quest.pretty(k)));
            q.type = t;
            q.category = s.getString("category", "misc").toLowerCase(Locale.ROOT);
            q.description = s.getString("description", "");
            Material m = Material.matchMaterial(s.getString("icon", "PAPER"));
            q.icon = m == null ? Material.PAPER : m;
            for (String x : s.getStringList("targets")) q.targets.add(x.toUpperCase(Locale.ROOT));
            q.amount = Math.max(1, s.getInt("amount", 1));
            q.requires = s.getString("requires", "").toLowerCase(Locale.ROOT);
            q.order = order++;
            quests.put(q.id, q);
        }
        reindex();
    }

    private void reindex() {
        Map<QuestType, List<Quest>> nb = new EnumMap<>(QuestType.class);
        Set<String> mt = new HashSet<>();
        for (Quest q : quests.values()) {
            nb.computeIfAbsent(q.type, k -> new ArrayList<>()).add(q);
            if (q.type == QuestType.MINE_BLOCK) mt.addAll(q.targets);
        }
        byType = nb;                       // swapped in one go, listeners never see a half built index
        mineTargets = mt;
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("settings.auto-start", autoStart);
        y.set("settings.allow-abandon", allowAbandon);
        y.set("settings.ignore-creative", ignoreCreative);
        y.set("settings.max-active", maxActive);
        for (Category c : categories.values()) { y.set("categories." + c.id() + ".name", c.name()); y.set("categories." + c.id() + ".icon", c.icon().name()); }
        for (Quest q : quests.values()) {
            String p = "quests." + q.id + ".";
            y.set(p + "category", q.category); y.set(p + "title", q.title); y.set(p + "description", q.description);
            y.set(p + "icon", q.icon.name()); y.set(p + "type", q.type.name()); y.set(p + "targets", new ArrayList<>(q.targets));
            y.set(p + "amount", q.amount); y.set(p + "requires", q.requires);
        }
        try { y.save(file); } catch (Exception e) { pl.getLogger().warning("quests.yml could not be saved: " + e); }
    }

    public synchronized void add(Quest q) { q.order = quests.size(); quests.put(q.id, q); reindex(); save(); }
    public synchronized void remove(String id) {
        quests.remove(id);
        for (Quest q : quests.values()) if (q.requires.equals(id)) q.requires = "";
        reindex(); save();
    }
    public synchronized void changed() { reindex(); save(); }

    public Quest get(String id) { return id == null ? null : quests.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Quest> all() { return new ArrayList<>(quests.values()); }
    public int size() { return quests.size(); }
    public List<Quest> byType(QuestType t) { List<Quest> l = byType.get(t); return l == null ? List.of() : l; }
    public Collection<Category> categories() { return categories.values(); }
    public Category category(String id) { Category c = categories.get(id); return c != null ? c : new Category(id, Quest.pretty(id), Material.BOOK); }
    public Set<String> mineTargets() { return mineTargets; }
}
