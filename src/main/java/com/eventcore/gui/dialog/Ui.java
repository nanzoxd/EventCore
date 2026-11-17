package com.eventcore.gui.dialog;

import com.eventcore.EventCorePlugin;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;

/**
 * Building blocks for dialogs: text helpers, symbols, inline heads and the button styles. Every button uses a customClick
 * callback (limited uses, 5 minute lifetime) that runs on the thread owning the player (the main thread on Paper).
 * Call {@link #bind(EventCorePlugin)} once in onEnable.
 */
public final class Ui {
    private Ui() { }

    public static final int CONTENT_WIDTH = 300, BUTTON_WIDTH = 200, HALF_WIDTH = 145;
    public static final String CHECK = "✔", CROSS = "✖", BACK = "←", NEXT = "→", CYCLE = "⇄", RELOAD = "⟳";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static volatile EventCorePlugin plugin;

    public static void bind(EventCorePlugin pl) { plugin = pl; }

    private static Theme t() { return Theme.get(); }

    /** A click handler: the player and the dialog's input values. */
    @FunctionalInterface public interface Click { void run(Player player, DialogResponseView view); }

    // ------------------------------------------------------------------ text
    /** MiniMessage ("<gold>text"), or legacy when the string contains '&' ("&#RRGGBB" and "&c" codes). */
    public static Component parse(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        return s.indexOf('&') >= 0 ? LEGACY.deserialize(s) : MM.deserialize(s);
    }

    private static Component colored(String s, TextColor c) { return parse(s).colorIfAbsent(c); }
    public static Component text(String s) { return colored(s, t().text); }
    public static Component secondary(String s) { return colored(s, t().secondary); }
    public static Component label(String s) { return colored(s, t().gold); }
    public static Component info(String s) { return colored(s, t().info); }
    public static Component adminInfo(String s) { return colored(s, t().adminInfo); }
    public static Component special(String s) { return colored(s, t().special); }
    public static Component header(String s) { return colored(s, t().gold).decorate(TextDecoration.BOLD); }

    public static DialogBody body(Component c) { return DialogBody.plainMessage(c, CONTENT_WIDTH); }
    public static DialogBody divider() { return body(Component.text("─".repeat(36), t().secondary)); }

    /** An inline player head (falls back to a diamond sign on servers without object components). */
    public static Component head(UUID id) {
        try { return Component.object(ObjectContents.playerHead(id)); }
        catch (LinkageError | RuntimeException e) { return Component.text("◆", t().gold); }
    }

    public static Component head(Player p) { return head(p.getUniqueId()); }

    /** "✔ label" in the enabled colour or "✖ label" in the disabled colour. */
    public static Component mark(boolean on, String label) {
        return Component.text((on ? CHECK : CROSS) + " ", on ? t().enabled : t().disabled).append(parse(label).colorIfAbsent(on ? t().enabled : t().disabled));
    }

    // ------------------------------------------------------------------ threading
    /** Runs on the main thread (or the player's region thread on Folia). */
    public static void sync(Player p, Runnable r) {
        EventCorePlugin pl = plugin;
        if (pl == null || Bukkit.isPrimaryThread()) r.run(); else pl.sched.entity(p, r);
    }

    // ------------------------------------------------------------------ buttons
    private static ClickCallback.Options options(int uses) { return ClickCallback.Options.builder().uses(Math.max(1, uses)).lifetime(LIFETIME).build(); }

    /** Generic button. click == null closes the dialog. uses = how often one rendering of the button may be clicked (screens are rebuilt after a click, so 1). */
    public static ActionButton button(Component label, Component tooltip, int width, int uses, Click click) {
        ActionButton.Builder b = ActionButton.builder(label).width(width);
        if (tooltip != null) b.tooltip(tooltip);
        if (click != null) b.action(DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) sync(p, () -> click.run(p, view));
        }, options(uses)));
        return b.build();
    }

    /** Gold action button, 200 px. */
    public static ActionButton gold(String label, String tooltip, Click click) {
        return button(label(label), tooltip == null ? null : secondary(tooltip), BUTTON_WIDTH, 1, click);
    }

    /** Green (on) / red (off) toggle. */
    public static ActionButton toggle(boolean on, String label, String tooltip, int width, Click click) {
        return button(mark(on, label), tooltip == null ? null : secondary(tooltip), width, 1, click);
    }

    /** Blue cycle button: "⇄ label: value". */
    public static ActionButton cycle(String label, String value, String tooltip, int width, Click click) {
        return button(Component.text(CYCLE + " " + label + ": ", t().info).append(Component.text(value, t().text)), tooltip == null ? null : secondary(tooltip), width, 1, click);
    }

    public static ActionButton back(Click click) { return button(Component.text(BACK + " Back", t().secondary), null, HALF_WIDTH, 1, click); }
    public static ActionButton previous(Click click) { return button(Component.text(BACK + " Previous", t().secondary), null, HALF_WIDTH, 1, click); }
    public static ActionButton next(Click click) { return button(Component.text("Next " + NEXT, t().secondary), null, HALF_WIDTH, 1, click); }
    /** Green Save button. */
    public static ActionButton save(Click click) { return button(Component.text(CHECK + " Save", t().enabled), null, HALF_WIDTH, 1, click); }
    /** Red Close button (no action: it just closes the dialog). */
    public static ActionButton close() { return button(Component.text(CROSS + " Close", t().disabled), null, HALF_WIDTH, 1, null); }
}
