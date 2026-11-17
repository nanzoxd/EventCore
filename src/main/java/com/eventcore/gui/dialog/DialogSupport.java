package com.eventcore.gui.dialog;

import com.eventcore.EventCorePlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the dialog framework (Paper 1.21.6+). {@link #available()} checks the API, {@link #enabled} also honours
 * "gui.dialogs" in config.yml. Screens are built with {@link Screen}: Header, Divider, Content, Hint, then inputs and buttons.
 */
public final class DialogSupport {
    private DialogSupport() { }

    public static final int PAGE_SIZE = 8;

    /** True when this server has Paper's dialog API. */
    public static boolean available() {
        try { Class.forName("io.papermc.paper.dialog.Dialog"); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    /** available() and gui.dialogs: true in config.yml. */
    public static boolean enabled(EventCorePlugin pl) { return available() && pl.cfg.config().getBoolean("gui.dialogs", true); }

    /** Re-reads the theme from config.yml (the Reload button and /eventcore reload). */
    public static void reloadTheme(EventCorePlugin pl) { Theme.reload(pl.cfg.config().getConfigurationSection("theme")); }

    // ------------------------------------------------------------------ pagination
    public record Page<T>(List<T> items, int page, int pages) { public boolean paged() { return pages > 1; } }

    public static <T> Page<T> paginate(List<T> all, int page, int perPage) {
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage), p = Math.max(0, Math.min(page, pages - 1));
        return new Page<>(all.subList(p * perPage, Math.min(all.size(), (p + 1) * perPage)), p, pages);
    }

    // ------------------------------------------------------------------ input values
    /** Typed access to the values the player entered. Missing keys give the default. */
    public record Values(DialogResponseView view) {
        public boolean bool(String key, boolean def) { Boolean b = view == null ? null : view.getBoolean(key); return b == null ? def : b; }
        public float number(String key, float def) { Float f = view == null ? null : view.getFloat(key); return f == null ? def : f; }
        public String text(String key, String def) { String s = view == null ? null : view.getText(key); return s == null ? def : s; }
    }

    // ------------------------------------------------------------------ screen builder
    public static final class Screen {
        private final Component title;
        private final List<DialogBody> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<ActionButton> actions = new ArrayList<>();
        private ActionButton exit = Ui.close();
        private DialogBody hint;
        private int columns = 1;

        /** Header, then a divider. */
        public Screen(String title, String header) {
            this.title = Ui.header(title);
            body.add(Ui.body(Ui.header(header)));
            body.add(Ui.divider());
        }

        /** Content paragraphs (300 px wide). */
        public Screen content(Component... lines) { for (Component c : lines) body.add(Ui.body(c)); return this; }
        public Screen hint(String text) { hint = Ui.body(Ui.secondary(text)); return this; }
        public Screen columns(int n) { columns = Math.max(1, n); return this; }
        public Screen action(ActionButton b) { actions.add(b); return this; }
        /** The footer button below the grid: Back or Close. */
        public Screen exit(ActionButton b) { exit = b; return this; }

        public Screen bool(String key, String label, boolean initial) {
            inputs.add(DialogInput.bool(key, Ui.text(label)).initial(initial).build()); return this;
        }

        public Screen slider(String key, String label, float min, float max, float step, float initial) {
            inputs.add(DialogInput.numberRange(key, Ui.text(label), min, max).width(Ui.CONTENT_WIDTH).step(step).initial(Math.max(min, Math.min(max, initial))).build()); return this;
        }

        /** Single-option selector. ids are the values returned, the first one matching selected is preselected. */
        public Screen choice(String key, String label, List<String> ids, String selected) {
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            for (String id : ids) entries.add(SingleOptionDialogInput.OptionEntry.create(id, Ui.text(id), id.equals(selected)));
            inputs.add(DialogInput.singleOption(key, Ui.text(label), entries).width(Ui.CONTENT_WIDTH).build()); return this;
        }

        /** Previous / Next row when there is more than one page. */
        public Screen pager(Page<?> page, IntConsumerPlayer open) {
            if (!page.paged()) return this;
            if (page.page() > 0) actions.add(Ui.previous((p, v) -> open.open(p, page.page() - 1)));
            if (page.page() < page.pages() - 1) actions.add(Ui.next((p, v) -> open.open(p, page.page() + 1)));
            return this;
        }

        public Dialog build() {
            List<DialogBody> all = new ArrayList<>(body);
            if (hint != null) all.add(hint);
            DialogBase base = DialogBase.builder(title).canCloseWithEscape(true).body(all).inputs(inputs).build();
            DialogType type = DialogType.multiAction(List.copyOf(actions), exit, columns);
            return Dialog.create(f -> f.empty().base(base).type(type));
        }

        public void show(Player p) { p.showDialog(build()); }
    }

    /** Opens a paged screen at the given page. */
    @FunctionalInterface public interface IntConsumerPlayer { void open(Player p, int page); }
}
