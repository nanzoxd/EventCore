package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps every plugin bossbar shown to all players, including players who join later. */
public final class BossBars implements Listener {
    private final EventCorePlugin pl;
    private final Set<BossBar> bars = ConcurrentHashMap.newKeySet();

    public BossBars(EventCorePlugin pl) { this.pl = pl; }

    public BossBar create(ConfigurationSection s, Map<String, Object> ph, float progress) {
        BossBar.Color color = enumOf(BossBar.Color.class, s.getString("color", "YELLOW"), BossBar.Color.YELLOW);
        BossBar.Overlay style = enumOf(BossBar.Overlay.class, s.getString("style", "PROGRESS"), BossBar.Overlay.PROGRESS);
        return BossBar.bossBar(pl.text.render(s.getString("text", ""), ph), clamp(progress), color, style);
    }

    public void show(BossBar bar) {
        bars.add(bar);
        showAll(bar);
    }

    /** Shows the bar to everyone online on each player's own thread (Folia-safe). Safe to call repeatedly. */
    public void showAll(BossBar bar) {
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { pl.sched.entity(p, () -> p.showBossBar(bar)); }
            catch (Throwable t) { try { p.showBossBar(bar); } catch (Throwable ignored) { } }
        }
    }

    public void hide(BossBar bar) {
        if (bar == null) return;
        bars.remove(bar);
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { pl.sched.entity(p, () -> p.hideBossBar(bar)); }
            catch (Throwable t) { try { p.hideBossBar(bar); } catch (Throwable ignored) { } }
        }
    }

    public void clear() { for (BossBar b : bars.toArray(new BossBar[0])) hide(b); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { for (BossBar b : bars) e.getPlayer().showBossBar(b); }

    public static float clamp(double v) { return (float) Math.max(0.0, Math.min(1.0, v)); }

    public static <E extends Enum<E>> E enumOf(Class<E> c, String name, E def) {
        try { return Enum.valueOf(c, name.toUpperCase(java.util.Locale.ROOT)); } catch (Exception e) { return def; }
    }
}
