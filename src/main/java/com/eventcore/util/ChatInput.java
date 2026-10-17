package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Chat prompts for the admin GUIs. The handler returns null on success or an error message (then the prompt repeats). */
public final class ChatInput implements Listener {
    private record Pending(Function<String, String> handler, Runnable after) { }

    private final EventCorePlugin pl;
    private final Map<UUID, Pending> map = new ConcurrentHashMap<>();

    public ChatInput(EventCorePlugin pl) { this.pl = pl; }

    public void ask(Player p, Component prompt, Function<String, String> handler, Runnable after) {
        map.put(p.getUniqueId(), new Pending(handler, after));
        p.sendMessage(prompt);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending pd = map.get(p.getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        String msg = Text.plain(e.message()).trim();
        pl.sched.global(() -> {
            if (msg.equalsIgnoreCase("cancel")) {
                map.remove(p.getUniqueId());
                pl.sched.entity(p, pd.after());
                return;
            }
            String err;
            try { err = pd.handler().apply(msg); }
            catch (Exception ex) { err = ex.getMessage() == null ? "invalid value" : ex.getMessage(); }
            if (err == null) {
                map.remove(p.getUniqueId());
                pl.sched.entity(p, pd.after());
            } else {
                String er = err;
                pl.sched.entity(p, () -> p.sendMessage(pl.msg("gui.invalid", "reason", er)));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { map.remove(e.getPlayer().getUniqueId()); }
}
