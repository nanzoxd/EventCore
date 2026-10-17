package com.eventcore.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads and saves the six EventCore YAML files. Missing keys are filled from the bundled defaults. */
public final class Cfg {
    public static final List<String> NAMES = List.of("config", "messages", "events", "rituals", "loot", "gui");
    private final JavaPlugin pl;
    private final Map<String, YamlConfiguration> map = new ConcurrentHashMap<>();

    public Cfg(JavaPlugin pl) { this.pl = pl; reload(); }

    public synchronized void reload() {
        pl.getDataFolder().mkdirs();
        for (String n : NAMES) {
            File f = new File(pl.getDataFolder(), n + ".yml");
            if (!f.exists()) pl.saveResource(n + ".yml", false);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            // loot.yml is fully admin-managed: never re-add deleted entries from the defaults
            if (!n.equals("loot")) {
                try (InputStream in = pl.getResource(n + ".yml")) {
                    if (in != null) {
                        y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
                        y.options().copyDefaults(true);
                    }
                } catch (IOException ex) {
                    pl.getLogger().warning("Could not read defaults for " + n + ".yml: " + ex.getMessage());
                }
            }
            map.put(n, y);
        }
    }

    public synchronized void save(String name) {
        YamlConfiguration y = map.get(name);
        if (y == null) return;
        try { y.save(new File(pl.getDataFolder(), name + ".yml")); }
        catch (IOException ex) { pl.getLogger().warning("Could not save " + name + ".yml: " + ex.getMessage()); }
    }

    public synchronized void set(String file, String path, Object value) {
        YamlConfiguration y = map.get(file);
        if (y != null) { y.set(path, value); save(file); }
    }

    public YamlConfiguration get(String name) { return map.get(name); }
    public YamlConfiguration config() { return map.get("config"); }
    public YamlConfiguration messages() { return map.get("messages"); }
    public YamlConfiguration events() { return map.get("events"); }
    public YamlConfiguration rituals() { return map.get("rituals"); }
    public YamlConfiguration loot() { return map.get("loot"); }
    public YamlConfiguration gui() { return map.get("gui"); }
}
