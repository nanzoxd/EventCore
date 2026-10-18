package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;

/**
 * Elo Buff: every Elo gain (PvP kills and playtime rewards) is multiplied while the event runs (default 1.5x).
 * The Elo lives in the ReliqueOnlineAPI plugin, so this talks to its StatsManager by reflection
 * (getEloGainMultiplier / setEloGainMultiplier, added by the patch shipped with EventCore). No compile dependency.
 */
public final class EloBuffEvent extends GameEvent {
    private double previous = 1.0;
    private boolean applied;

    public EloBuffEvent(EventCorePlugin pl) { super(pl, "elo_buff"); }

    private Object statsManager() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin(settings().getString("plugin", "ReliqueOnlineAPI"));
            if (p == null || !p.isEnabled()) return null;
            return p.getClass().getMethod("getStatsManager").invoke(p);
        } catch (Throwable t) { return null; }
    }

    private double mult() { return Math.max(0.0, settings().getDouble("multiplier", 1.5)); }

    @Override public Map<String, Object> extra(ActiveEvent ae) {
        double m = mult();
        return Map.of("multiplier", m == Math.rint(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m));
    }

    @Override public void onStart(ActiveEvent ae) {
        Object sm = statsManager();
        if (sm == null) {
            pl.getLogger().warning("Elo Buff: ReliqueOnlineAPI not found (or not the patched version). Nothing was changed.");
            return;
        }
        try {
            previous = ((Number) sm.getClass().getMethod("getEloGainMultiplier").invoke(sm)).doubleValue();
            sm.getClass().getMethod("setEloGainMultiplier", double.class).invoke(sm, mult());
            applied = true;
        } catch (Throwable t) {
            pl.getLogger().warning("Elo Buff: the Elo plugin has no setEloGainMultiplier (apply the patch): " + t);
        }
    }

    @Override public void onStop(ActiveEvent ae) {
        if (!applied) return;
        applied = false;
        Object sm = statsManager();
        if (sm == null) return;
        try { sm.getClass().getMethod("setEloGainMultiplier", double.class).invoke(sm, previous); }
        catch (Throwable t) { pl.getLogger().warning("Elo Buff: could not restore the multiplier: " + t); }
    }
}
