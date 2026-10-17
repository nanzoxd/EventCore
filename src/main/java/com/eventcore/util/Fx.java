package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Map;

/** Sounds, titles and broadcasts, all driven by config sections. */
public final class Fx {
    private final EventCorePlugin pl;

    public Fx(EventCorePlugin pl) { this.pl = pl; }

    /** A message template plus an optional per-message sound section. */
    public record Msg(String tpl, ConfigurationSection sound) { }

    /** Reads a message that is either a plain string or a section {text, sound}. */
    public Msg msg(ConfigurationSection root, String path) {
        Object o = root == null ? null : root.get(path);
        if (o instanceof ConfigurationSection cs) return new Msg(cs.getString("text", ""), cs.getConfigurationSection("sound"));
        return new Msg(o == null ? path : String.valueOf(o), null);
    }

    public Sound sound(ConfigurationSection s) {
        if (s == null) return null;
        String n = s.getString("sound", "");
        if (n.isBlank() || n.equalsIgnoreCase("none")) return null;
        try {
            return Sound.sound(Key.key(n.toLowerCase(Locale.ROOT)), Sound.Source.MASTER,
                    (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0));
        } catch (Exception e) { return null; }
    }

    public void play(Audience a, ConfigurationSection s) {
        Sound so = sound(s);
        if (so != null) a.playSound(so);
    }

    public void playAll(ConfigurationSection s) { play(Bukkit.getServer(), s); }

    public Title.Times times(ConfigurationSection sec) {
        ConfigurationSection t = pl.cfg.config().getConfigurationSection("style.title-times");
        int in = sec != null && sec.contains("fade-in") ? sec.getInt("fade-in") : (t != null ? t.getInt("fade-in", 10) : 10);
        int stay = sec != null && sec.contains("stay") ? sec.getInt("stay") : (t != null ? t.getInt("stay", 70) : 70);
        int out = sec != null && sec.contains("fade-out") ? sec.getInt("fade-out") : (t != null ? t.getInt("fade-out", 20) : 20);
        return Title.Times.times(Ticks.duration(in), Ticks.duration(stay), Ticks.duration(out));
    }

    /** Shows a title section {title, subtitle, fade-in, stay, fade-out}. */
    public void title(Audience a, ConfigurationSection sec, Map<String, Object> ph) {
        if (sec == null) return;
        Component t = pl.text.render(sec.getString("title", ""), ph);
        Component s = pl.text.render(sec.getString("subtitle", ""), ph);
        a.showTitle(Title.title(t, s, times(sec)));
    }

    /** Broadcasts a message (string or section) to everyone and plays its sound (default: sounds.broadcast). */
    public void broadcast(ConfigurationSection root, String path, Map<String, Object> ph, String prefixTpl) {
        Msg m = msg(root, path);
        if (m.tpl().isBlank()) return;
        Bukkit.getServer().sendMessage(pl.text.render((prefixTpl == null ? "" : prefixTpl) + m.tpl(), ph));
        playAll(m.sound() != null ? m.sound() : pl.cfg.config().getConfigurationSection("sounds.broadcast"));
    }
}
