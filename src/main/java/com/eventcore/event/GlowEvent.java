package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Everyone glows. Outline colours come from the palette via scoreboard teams (see TeamService). */
public final class GlowEvent extends GameEvent {
    public GlowEvent(EventCorePlugin pl) { super(pl, "glow"); }

    @Override protected void doApply(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    @Override protected void doStrip(Player p) { p.removePotionEffect(PotionEffectType.GLOWING); }
}
