package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Quest notices, without any custom advancement: a chat line, the same title on the action bar and a short sound.
 * (The old advancement toasts did not show on every server, so they are gone. The API is unchanged.)
 */
public final class QuestToast {
    public enum Frame { TASK, GOAL, CHALLENGE }

    private final EventCorePlugin pl;

    public QuestToast(EventCorePlugin pl) { this.pl = pl; }

    public void forget(UUID u) { }

    /** icon is unused now; kept so the callers do not change. */
    public void show(Player p, Material icon, Component title, Component description, Frame frame) {
        Component chat = Component.text("quest | ", TextColor.color(0x7F7F7F)).append(title)
                .append(Component.text(" - ", TextColor.color(0x7F7F7F))).append(description);
        pl.sched.entity(p, () -> {
            p.sendMessage(chat);
            p.sendActionBar(title);
            String key = switch (frame) { case CHALLENGE -> "ui.toast.challenge_complete"; case GOAL -> "entity.experience_orb.pickup"; default -> "ui.button.click"; };
            p.playSound(Sound.sound(net.kyori.adventure.key.Key.key(key), Sound.Source.MASTER, 0.7f, frame == Frame.TASK ? 1.4f : 1.0f));
        });
    }
}
