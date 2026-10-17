package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Players cannot use shields: raising is blocked (interact cancelled + item cooldown, re-applied every second),
 * an already raised shield is lowered, and shield crafting can be blocked.
 */
public final class ShieldlessEvent extends GameEvent {
    public ShieldlessEvent(EventCorePlugin pl) { super(pl, "shieldless"); }

    private void enforce(Player p) {
        if (settings().getBoolean("block-raising", true))
            p.setCooldown(Material.SHIELD, Math.max(20, settings().getInt("cooldown-ticks", 60)));
        if (p.isBlocking()) p.clearActiveItem();
    }

    @Override protected void doApply(Player p) { enforce(p); }
    @Override protected void doStrip(Player p) { p.setCooldown(Material.SHIELD, 0); }

    @Override public void onSecond(ActiveEvent ae, int left) {
        for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entity(p, () -> enforce(p));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!active() || !settings().getBoolean("block-raising", true)) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getItem() != null && e.getItem().getType() == Material.SHIELD) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(pl.msg("events.shield-blocked"));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!active() || !settings().getBoolean("block-damage-blocking", true)) return;
        if (e.getEntity() instanceof Player p && p.isBlocking()) p.clearActiveItem();
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        if (!active() || !settings().getBoolean("block-crafting", true)) return;
        if (e.getInventory().getResult() != null && e.getInventory().getResult().getType() == Material.SHIELD)
            e.getInventory().setResult(null);
    }
}
