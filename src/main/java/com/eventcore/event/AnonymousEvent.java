package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;

/**
 * Everyone shares one configurable name. Chat names, join/quit/death/advancement messages, tab list entries and
 * nametags (scoreboard team, see TeamService) are hidden. Skin hiding needs a packet library and is not implemented.
 */
public final class AnonymousEvent extends GameEvent {
    public AnonymousEvent(EventCorePlugin pl) { super(pl, "anonymous"); }

    private String anonName() { return settings().getString("name", "Anonymous"); }

    @Override protected void doApply(Player p) {
        String mode = settings().getString("tablist-mode", "ANON").toUpperCase();
        if (mode.equals("ANON")) p.playerListName(Component.text(anonName()));
        else if (mode.equals("BLANK")) p.playerListName(Component.text(" "));
    }

    @Override protected void doStrip(Player p) { p.playerListName(null); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        if (!active()) return;
        boolean reveal = settings().getBoolean("reveal-to-admins", true);
        String fmt = settings().getString("chat-format", "<gray>{name}</gray> <dark_gray>»</dark_gray> ");
        Map<String, Object> ph = Text.ph("name", anonName());
        e.renderer((ChatRenderer) (source, sourceDisplayName, message, viewer) -> {
            Component out = pl.text.render(fmt, ph);
            if (reveal && viewer instanceof Player v && v.hasPermission("eventcore.bypass"))
                out = out.append(Component.text("(" + source.getName() + ") "));
            return out.append(message);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) { if (active() && settings().getBoolean("hide-join-quit", true)) e.joinMessage(null); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) { if (active() && settings().getBoolean("hide-join-quit", true)) e.quitMessage(null); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) { if (active() && settings().getBoolean("hide-death-messages", true)) e.deathMessage(null); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent e) { if (active() && settings().getBoolean("hide-advancements", true)) e.message(null); }
}
