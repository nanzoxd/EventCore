package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class of every event. Config is read live from events.yml (events.&lt;id&gt;), so edits apply instantly.
 * Effects applied to a player are tracked twice: in memory and with a PDC marker on the player, so
 * leftovers from a crash can still be swept away on the next join.
 */
public abstract class GameEvent implements Listener {
    protected final EventCorePlugin pl;
    private final String id;
    private final Set<UUID> applied = ConcurrentHashMap.newKeySet();
    private final NamespacedKey marker;

    protected GameEvent(EventCorePlugin pl, String id) {
        this.pl = pl;
        this.id = id;
        this.marker = new NamespacedKey(pl, "applied_" + id);
    }

    public String id() { return id; }

    public ConfigurationSection sec() {
        ConfigurationSection s = pl.cfg.events().getConfigurationSection("events." + id);
        return s != null ? s : new MemoryConfiguration();
    }

    public ConfigurationSection settings() {
        ConfigurationSection s = sec().getConfigurationSection("settings");
        return s != null ? s : new MemoryConfiguration();
    }

    public String displayName() { return sec().getString("display-name", id); }
    public boolean active() { return pl.events.isActive(id); }

    // ---- lifecycle hooks (all optional) ----
    /** Shortest run time this event needs (minutes). 0 = no minimum. */
    public int minMinutes() { return 0; }
    public void onStart(ActiveEvent ae) { }
    public void onStop(ActiveEvent ae) { }
    public void onSecond(ActiveEvent ae, int secondsLeft) { }
    /** True when the event animates its own bossbar title. */
    public boolean ownsBarTitle() { return false; }
    /** True when the bossbar must always exist, whatever bossbar.enabled / event-display say (lootdrop). */
    public boolean forceBar() { return false; }
    /** Custom bar/actionbar text for events that build their own (airdrop). Null = use bossbar.text. */
    public net.kyori.adventure.text.Component displayText(ActiveEvent ae) { return null; }
    /** Extra placeholders for this event's bossbar and messages. */
    public Map<String, Object> extra(ActiveEvent ae) { return Map.of(); }

    protected void doApply(Player p) { }
    protected void doStrip(Player p) { }
    /** Unconditional stale cleanup, run for inactive events on join. */
    public void sweep(Player p) { }

    public final void apply(Player p) {
        applied.add(p.getUniqueId());
        p.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        doApply(p);
    }

    public final void strip(Player p) {
        boolean had = applied.remove(p.getUniqueId()) | p.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
        if (had) {
            try { doStrip(p); } finally { p.getPersistentDataContainer().remove(marker); }
        }
    }
}
