package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Locale;
import java.util.Map;

/**
 * Healthy Feeder: a golden apple gives 4 absorption hearts (8 hp) instead of the vanilla 2 hearts (4 hp).
 * The vanilla effects are applied first; one tick later the absorption is topped up to the configured amount.
 * Enchanted golden apples are left alone unless settings.include-enchanted is true.
 */
public final class HealthyFeederEvent extends GameEvent {

    public HealthyFeederEvent(EventCorePlugin pl) { super(pl, "healthy_feeder"); }

    private double hearts() { return Math.max(0.5, settings().getDouble("absorption-hearts", 4.0)); }

    @Override public Map<String, Object> extra(EventManager.ActiveEvent ae) {
        double h = hearts();
        return Map.of("hearts", h == Math.rint(h) ? String.valueOf((long) h) : String.format(Locale.ROOT, "%.1f", h));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (!active()) return;
        Material m = e.getItem().getType();
        boolean ok = m == Material.GOLDEN_APPLE || (m == Material.ENCHANTED_GOLDEN_APPLE && settings().getBoolean("include-enchanted", false));
        if (!ok) return;
        Player p = e.getPlayer();
        pl.sched.entityLater(p, () -> {
            // the absorption effect is already on the player; the hearts themselves are the absorption amount
            double target = hearts() * 2.0;
            if (p.getAbsorptionAmount() < target) p.setAbsorptionAmount(target);
        }, 1);
    }
}
