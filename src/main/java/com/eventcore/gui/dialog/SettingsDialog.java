package com.eventcore.gui.dialog;

import com.eventcore.EventCorePlugin;
import com.eventcore.gui.dialog.DialogSupport.Page;
import com.eventcore.gui.dialog.DialogSupport.Screen;
import com.eventcore.gui.dialog.DialogSupport.Values;
import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Reusable settings UI: a category menu (two columns) leading to settings screens (one column) with toggles, sliders, cycle
 * options and action buttons, paged after 8 entries, with Save / Back in the footer and a Reload button for config + theme.
 * {@link #example(EventCorePlugin)} shows every button style; the KoTH settings are built on the same class.
 */
public final class SettingsDialog {
    public sealed interface Entry permits Toggle, Slider, Cycle, Action { }
    /** Green/red button: flips the value at once. */
    public record Toggle(String label, String tip, BooleanSupplier get, Consumer<Boolean> set) implements Entry { }
    /** Slider input: applied when Save (or any button) is pressed. */
    public record Slider(String label, float min, float max, float step, DoubleSupplier get, DoubleConsumer set) implements Entry { }
    /** Blue button: moves to the next option. */
    public record Cycle(String label, String tip, List<String> options, Supplier<String> get, Consumer<String> set) implements Entry { }
    /** Gold button: runs an action. */
    public record Action(String label, String tip, Consumer<Player> run) implements Entry { }
    public record Category(String label, String hint, boolean admin, List<Entry> entries) { }

    private final EventCorePlugin pl;
    private final String title;
    private final Supplier<List<Category>> categories;
    private final Runnable onChange;

    /** @param categories evaluated each time a screen opens, so values are always current. @param onChange called after every change (persist here). */
    public SettingsDialog(EventCorePlugin pl, String title, Supplier<List<Category>> categories, Runnable onChange) {
        this.pl = pl; this.title = title; this.categories = categories; this.onChange = onChange;
    }

    public void open(Player p) { menu(p); }

    private void menu(Player p) {
        List<Category> cats = categories.get();
        Screen s = new Screen(title, "Settings").columns(2).content(Ui.text("Choose a category."));
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i); int idx = i;
            s.action(Ui.button((c.admin() ? Ui.adminInfo(c.label()) : Ui.label(c.label())), c.hint() == null ? null : Ui.secondary(c.hint()), Ui.HALF_WIDTH, 1, (pl2, v) -> screen(pl2, idx, 0)));
        }
        s.action(Ui.button(Ui.label(Ui.RELOAD + " Reload"), Ui.secondary("Reload config.yml and the theme"), Ui.HALF_WIDTH, 1, (pl2, v) -> { pl.cfg.reload(); DialogSupport.reloadTheme(pl); menu(pl2); }));
        s.hint("Changes are saved as you go.").exit(Ui.close()).show(p);
    }

    private void screen(Player p, int catIndex, int pageIndex) {
        List<Category> cats = categories.get();
        if (catIndex >= cats.size()) { menu(p); return; }
        Category c = cats.get(catIndex);
        Page<Entry> page = DialogSupport.paginate(c.entries(), pageIndex, DialogSupport.PAGE_SIZE);
        Screen s = new Screen(title, c.label()).columns(1);
        if (c.hint() != null) s.content(c.admin() ? Ui.adminInfo(c.hint()) : Ui.info(c.hint()));
        List<Slider> sliders = new ArrayList<>();
        for (int i = 0; i < page.items().size(); i++) {
            Entry e = page.items().get(i); String key = "s" + sliders.size();
            switch (e) {
                case Toggle t -> s.action(Ui.toggle(t.get().getAsBoolean(), t.label(), t.tip(), Ui.BUTTON_WIDTH, (pl2, v) -> {
                    applySliders(sliders, v); t.set().accept(!t.get().getAsBoolean()); onChange.run(); screen(pl2, catIndex, page.page()); }));
                case Cycle y -> s.action(Ui.cycle(y.label(), y.get().get(), y.tip(), Ui.BUTTON_WIDTH, (pl2, v) -> {
                    applySliders(sliders, v); y.set().accept(next(y)); onChange.run(); screen(pl2, catIndex, page.page()); }));
                case Action a -> s.action(Ui.gold(a.label(), a.tip(), (pl2, v) -> { applySliders(sliders, v); onChange.run(); a.run().accept(pl2); }));
                case Slider sl -> { sliders.add(sl); s.slider(key, sl.label(), sl.min(), sl.max(), sl.step(), (float) sl.get().getAsDouble()); }
            }
        }
        s.pager(page, (pl2, pg) -> screen(pl2, catIndex, pg));
        s.action(Ui.save((pl2, v) -> { applySliders(sliders, v); onChange.run(); pl2.sendMessage(Ui.text("Saved.")); screen(pl2, catIndex, page.page()); }));
        s.exit(Ui.back((pl2, v) -> { applySliders(sliders, v); onChange.run(); menu(pl2); }));
        s.hint(page.paged() ? "Page " + (page.page() + 1) + "/" + page.pages() : "Sliders apply when you press Save.").show(p);
    }

    private static void applySliders(List<Slider> sliders, DialogResponseView view) {
        Values vals = new Values(view);
        for (int i = 0; i < sliders.size(); i++) { Slider s = sliders.get(i); s.set().accept(vals.number("s" + i, (float) s.get().getAsDouble())); }
    }

    private static String next(Cycle c) {
        int i = c.options().indexOf(c.get().get());
        return c.options().get((i + 1) % c.options().size());
    }

    // ------------------------------------------------------------------ example
    /** Demo with every style: gold actions, green/red toggles (paged), blue cycle, slider, Save, Back, Close, Reload, a player head. */
    public static SettingsDialog example(EventCorePlugin pl) {
        boolean[] flags = new boolean[10]; String[] mode = {"Normal"}; double[] vol = {50};
        Supplier<List<Category>> cats = () -> {
            List<Entry> general = new ArrayList<>();
            for (int i = 0; i < 10; i++) { int n = i; general.add(new Toggle("Option " + (n + 1), "Toggle option " + (n + 1), () -> flags[n], b -> flags[n] = b)); }
            return List.of(
                new Category("General", "Ten toggles, paged after 8", false, general),
                new Category("Sound & mode", "A slider and a cycle button", false, List.of(
                    new Slider("Volume", 0, 100, 5, () -> vol[0], v -> vol[0] = v),
                    new Cycle("Mode", "Click to change", List.of("Easy", "Normal", "Hard"), () -> mode[0], m -> mode[0] = m))),
                new Category("Admin", "Gold action buttons", true, List.of(
                    new Action("Say hello", "Gold action button", pl2 -> pl2.sendMessage(Component.text("Hello, ").append(Ui.head(pl2)).append(Component.text(" " + pl2.getName())))),
                    new Action("Ping", "Another action", pl2 -> pl2.sendMessage(Ui.special("Pong!"))))));
        };
        return new SettingsDialog(pl, "Example", cats, () -> { });
    }
}
