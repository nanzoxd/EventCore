package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The vanilla advancement toast (the slide-in box at the top right of the screen).
 * A throw-away advancement is loaded, granted to the player (the client slides the toast in) and removed again,
 * so nothing is saved on the player and nothing shows up in the advancement screen.
 * If the server cannot load advancements the toast falls back to an action bar line + the toast sound.
 */
public final class QuestToast {
    public enum Frame { TASK, GOAL, CHALLENGE }

    private final EventCorePlugin pl;
    private final AtomicLong counter = new AtomicLong();
    private final Map<UUID, Long> next = new ConcurrentHashMap<>();
    private volatile boolean advancementOk = true;

    public QuestToast(EventCorePlugin pl) { this.pl = pl; }

    public void forget(UUID u) { next.remove(u); }

    /** Toasts queue per player: one every 1.6 s, so a burst of completions never overlaps. */
    public void show(Player p, Material icon, Component title, Component description, Frame frame) {
        if (!p.isOnline()) return;
        long now = System.currentTimeMillis();
        long at = Math.max(now, next.getOrDefault(p.getUniqueId(), 0L));
        next.put(p.getUniqueId(), at + 1600);
        long delay = Math.max(1, (at - now) / 50);
        pl.sched.globalLater(() -> fire(p, icon, title, description, frame), delay);
    }

    private void fire(Player p, Material icon, Component title, Component desc, Frame frame) {
        if (!p.isOnline()) return;
        if (!advancementOk) { fallback(p, title, frame); return; }
        NamespacedKey key = new NamespacedKey(pl, "quest_toast_" + counter.incrementAndGet());
        try {
            String json = "{\"display\":{\"icon\":{\"id\":\"minecraft:" + icon.getKey().getKey() + "\"},"
                    + "\"title\":" + GsonComponentSerializer.gson().serialize(title) + ","
                    + "\"description\":" + GsonComponentSerializer.gson().serialize(desc) + ","
                    + "\"frame\":\"" + frame.name().toLowerCase() + "\",\"show_toast\":true,\"announce_to_chat\":false,\"hidden\":true},"
                    + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
            Advancement adv = Bukkit.getUnsafe().loadAdvancement(key, json);
            if (adv == null) { fallback(p, title, frame); return; }
            pl.sched.entity(p, () -> {
                AdvancementProgress ap = p.getAdvancementProgress(adv);
                ap.awardCriteria("done");                                              // the client shows the toast right now
                pl.sched.entityLater(p, () -> { try { p.getAdvancementProgress(adv).revokeCriteria("done"); } catch (Throwable ignored) { } }, 10);
            });
            pl.sched.globalLater(() -> { try { Bukkit.getUnsafe().removeAdvancement(key); } catch (Throwable ignored) { } }, 100);
        } catch (Throwable t) {
            advancementOk = false;                                                      // unsupported here: use the fallback from now on
            pl.getLogger().warning("Advancement toasts are not available on this server (" + t + "), using the action bar fallback.");
            fallback(p, title, frame);
        }
    }

    private void fallback(Player p, Component title, Frame frame) {
        pl.sched.entity(p, () -> {
            p.sendActionBar(title);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, frame == Frame.CHALLENGE ? 1.0f : 1.4f);
        });
    }
}
