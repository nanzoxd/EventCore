package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.ArrayList;

/** Reduces everyone's max health to settings.hearts hearts using a keyed attribute modifier (so it is always removable). */
public final class VulnerableEvent extends GameEvent {
    private final NamespacedKey modKey;

    public VulnerableEvent(EventCorePlugin pl) {
        super(pl, "vulnerable");
        modKey = new NamespacedKey(pl, "vulnerable_hearts");
    }

    @Override public java.util.Map<String, Object> extra(ActiveEvent ae) {
        double h = settings().getDouble("hearts", 7.5);
        return java.util.Map.of("hearts", h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(h));
    }

    @Override protected void doApply(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        removeMod(a);
        double target = Math.max(1.0, settings().getDouble("hearts", 7.5) * 2.0);
        double delta = target - a.getBaseValue();
        a.addModifier(new AttributeModifier(modKey, delta, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    @Override protected void doStrip(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) removeMod(a);
    }

    @Override public void sweep(Player p) {
        if (active()) return;
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) removeMod(a);
    }

    private void removeMod(AttributeInstance a) {
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().equals(modKey)) a.removeModifier(m);
    }
}
