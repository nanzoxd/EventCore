package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.*;

/**
 * Turns the locator bar gamerule on, bans invisibility (drinking, splash, clouds, tipped arrows, beacons ...),
 * removes existing invisibility and blocks configurable commands. eventcore.bypass skips the command block.
 * The gamerule is written through the string API so the plugin still loads on servers that predate it.
 */
public final class LocatorBarEvent extends GameEvent {
    private final Map<String, String> originals = new HashMap<>();
    private long lastSweep;

    public LocatorBarEvent(EventCorePlugin pl) { super(pl, "locator_bar"); }

    private boolean worldAllowed(World w) {
        List<String> l = settings().getStringList("worlds");
        return l.isEmpty() || l.contains(w.getName());
    }

    @Override public void onStart(ActiveEvent ae) {
        originals.clear();
        for (World w : Bukkit.getWorlds()) {
            if (!worldAllowed(w)) continue;
            try {
                String old = w.getGameRuleValue("locatorBar");
                if (old == null) { pl.getLogger().warning("This server has no locatorBar gamerule (needs 1.21.6+)."); return; }
                originals.put(w.getName(), old);
                w.setGameRuleValue("locatorBar", "true");
            } catch (Throwable t) { pl.getLogger().warning("locatorBar gamerule failed: " + t.getMessage()); }
        }
    }

    @Override public void onStop(ActiveEvent ae) {
        for (Map.Entry<String, String> e : originals.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w != null) { try { w.setGameRuleValue("locatorBar", e.getValue()); } catch (Throwable ignored) { } }
        }
        originals.clear();
    }

    @Override protected void doApply(Player p) { p.removePotionEffect(PotionEffectType.INVISIBILITY); }

    @Override public void onSecond(ActiveEvent ae, int left) {
        long now = System.currentTimeMillis();
        if (now - lastSweep < settings().getInt("sweep-seconds", 5) * 1000L) return;
        lastSweep = now;
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) pl.sched.entity(p, () -> p.removePotionEffect(PotionEffectType.INVISIBILITY));
    }

    private static boolean hasInvis(Collection<PotionEffect> effects) {
        for (PotionEffect e : effects) if (e.getType().equals(PotionEffectType.INVISIBILITY)) return true;
        return false;
    }

    private static boolean isInvisPotion(ItemStack it) {
        if (it == null || !(it.getItemMeta() instanceof PotionMeta pm)) return false;
        PotionType t = pm.getBasePotionType();
        if (t == PotionType.INVISIBILITY || t == PotionType.LONG_INVISIBILITY) return true;
        return hasInvis(pm.getCustomEffects());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        if (!active() || !settings().getBoolean("block-invisibility", true)) return;
        if (isInvisPotion(e.getItem())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(pl.msg("events.invisibility-blocked"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!active() || !settings().getBoolean("block-invisibility", true)) return;
        if (hasInvis(e.getPotion().getEffects())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        if (!active() || !settings().getBoolean("block-invisibility", true)) return;
        PotionType t = e.getEntity().getBasePotionType();
        if (t == PotionType.INVISIBILITY || t == PotionType.LONG_INVISIBILITY || hasInvis(e.getEntity().getCustomEffects()))
            e.setCancelled(true);
    }

    /** Catches every remaining source (tipped arrows, beacons, commands ...) by cause. */
    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!active() || !settings().getBoolean("block-invisibility", true)) return;
        PotionEffect n = e.getNewEffect();
        if (n == null || !n.getType().equals(PotionEffectType.INVISIBILITY)) return;
        List<String> causes = settings().getStringList("blocked-causes");
        if (causes.stream().anyMatch(c -> c.equalsIgnoreCase(e.getCause().name()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!active() || e.getPlayer().hasPermission("eventcore.bypass")) return;
        String msg = e.getMessage().trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String[] tok = msg.toLowerCase(Locale.ROOT).split("\\s+");
        if (tok.length == 0 || tok[0].isEmpty()) return;
        String root = tok[0].contains(":") ? tok[0].substring(tok[0].indexOf(':') + 1) : tok[0];
        for (String blocked : settings().getStringList("blocked-commands")) {
            String[] bt = blocked.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "").split("\\s+");
            if (bt.length == 0 || bt[0].isEmpty()) continue;
            if (!names(bt[0]).contains(root)) continue;
            boolean ok = tok.length - 1 >= bt.length - 1;
            for (int i = 1; ok && i < bt.length; i++) ok = tok[i].equals(bt[i]);
            if (ok) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(pl.msg("events.command-blocked", "command", "/" + blocked));
                return;
            }
        }
    }

    /** The command name plus config aliases plus whatever aliases the server knows for it. */
    private Set<String> names(String root) {
        Set<String> out = new HashSet<>();
        out.add(root);
        ConfigurationSection al = settings().getConfigurationSection("command-aliases");
        if (al != null) for (String a : al.getStringList(root)) out.add(a.toLowerCase(Locale.ROOT));
        try {
            Command c = Bukkit.getCommandMap().getCommand(root);
            if (c != null) {
                out.add(c.getName().toLowerCase(Locale.ROOT));
                for (String a : c.getAliases()) out.add(a.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
