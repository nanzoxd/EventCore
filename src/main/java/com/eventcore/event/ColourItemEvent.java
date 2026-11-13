package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Colour Item: the plugin announces a random COLOUR (for example "Black") in chat and keeps its name on the action bar (no
 * bossbar). The first player who picks up or takes ANY item of that colour (wool, dye, concrete, banners, beds, glass...)
 * wins a random stack of golden apples (1 to 64) and the event ends right away.
 * An item counts as a colour when its name starts with the colour (BLACK_WOOL, LIGHT_BLUE_DYE, ...).
 */
public final class ColourItemEvent extends GameEvent {
    private final AtomicBoolean won = new AtomicBoolean();
    private volatile DyeColor colour;

    public ColourItemEvent(EventCorePlugin pl) { super(pl, "colour_item"); }

    // ---------------------------------------------------------------------------------------------
    // colours
    // ---------------------------------------------------------------------------------------------
    private List<DyeColor> pool() {
        List<DyeColor> out = new ArrayList<>();
        for (String n : settings().getStringList("colours")) {
            try { out.add(DyeColor.valueOf(n.trim().toUpperCase(Locale.ROOT).replace(' ', '_'))); } catch (IllegalArgumentException ignored) { }
        }
        if (out.isEmpty()) out.addAll(List.of(DyeColor.values()));
        return out;
    }

    private static boolean isColour(Material m, DyeColor c) { return m != null && m.name().startsWith(c.name() + "_"); }

    private static String pretty(DyeColor c) { return Util.pretty(Material.matchMaterial(c.name() + "_WOOL")).replace(" Wool", ""); }

    /** The text colour: the dye colour, lightened when it would be unreadable on a dark action bar (black, gray, blue...). */
    private static String hex(DyeColor c) {
        Color k = c.getColor();
        double lum = 0.299 * k.getRed() + 0.587 * k.getGreen() + 0.114 * k.getBlue();
        double mix = lum < 90 ? 0.55 : lum < 140 ? 0.25 : 0;
        return String.format("#%02X%02X%02X", (int) (k.getRed() + (255 - k.getRed()) * mix), (int) (k.getGreen() + (255 - k.getGreen()) * mix), (int) (k.getBlue() + (255 - k.getBlue()) * mix));
    }

    @Override
    public Map<String, Object> extra(ActiveEvent ae) {
        DyeColor c = colour;
        return c == null ? Map.of() : Text.ph("colour", pretty(c), "colour_raw", hex(c));
    }

    private String prefix() { return pl.cfg.messages().getString("prefix", ""); }

    // ---------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------
    @Override
    public void onStart(ActiveEvent ae) {
        won.set(false);
        List<DyeColor> all = pool();
        colour = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        pl.fx.broadcast(sec(), "messages.announce", new HashMap<>(pl.events.placeholders(ae)), prefix());   // the colour, in chat
    }

    @Override
    public void onStop(ActiveEvent ae) { colour = null; }

    /** Action bar, every second (no bossbar): the colour name in its own colour. */
    @Override
    public Component displayText(ActiveEvent ae) {
        if (colour == null) return null;
        return pl.text.render(settings().getString("actionbar", "<#A5B8FF>pick up any <{colour_raw}>{colour} <#A5B8FF>item"), new HashMap<>(pl.events.placeholders(ae)));
    }

    // ---------------------------------------------------------------------------------------------
    // pick up / take
    // ---------------------------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        check(p, e.getItem().getItemStack());
    }

    /** Taking an item out of a chest, barrel, furnace... (not from the player's own inventory). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null || clicked.equals(p.getInventory())) return;
        InventoryAction a = e.getAction();
        boolean takes = a == InventoryAction.PICKUP_ALL || a == InventoryAction.PICKUP_HALF || a == InventoryAction.PICKUP_ONE
                || a == InventoryAction.PICKUP_SOME || a == InventoryAction.MOVE_TO_OTHER_INVENTORY || a == InventoryAction.HOTBAR_SWAP;
        if (takes) check(p, e.getCurrentItem());
    }

    private void check(Player p, ItemStack item) {
        DyeColor c = colour;
        if (c == null || !active() || item == null || !isColour(item.getType(), c)) return;
        if (!won.compareAndSet(false, true)) return;                       // somebody else was faster
        Material m = item.getType();
        pl.sched.global(() -> win(p, c, m));
    }

    private void win(Player p, DyeColor c, Material got) {
        ConfigurationSection s = settings();
        int min = Math.max(1, s.getInt("reward.min", 1)), max = Math.max(min, Math.min(64, s.getInt("reward.max", 64)));
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        Material mat = Material.matchMaterial(s.getString("reward.item", "GOLDEN_APPLE"));
        if (mat == null || !mat.isItem()) mat = Material.GOLDEN_APPLE;
        ItemStack reward = new ItemStack(mat, amount);
        if (p.isOnline()) pl.sched.entity(p, () -> Util.give(p, reward));
        Map<String, Object> ph = Text.ph("player", p.getName(), "amount", amount, "reward", Util.pretty(mat), "item", Util.pretty(got), "colour", pretty(c),
                "colour_raw", hex(c), "event", pl.text.sc(displayName()), "gradient_raw", gradientRaw());
        pl.fx.title(Bukkit.getServer(), sec().getConfigurationSection("titles.winner"), ph);
        pl.fx.broadcast(sec(), "messages.winner", ph, prefix());
        pl.events.stop(id(), false);                                       // the event ends as soon as somebody picked it up
    }
}
