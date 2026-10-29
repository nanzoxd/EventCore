package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Mouse;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * /ritual schedule: pick the date and time (IST), place the ritual item, set the location (your current spot),
 * choose the length and confirm. The item is only taken when the schedule is saved; every other way out returns it.
 */
public final class RitualScheduleGui implements Listener {
    private static final int ITEM = 13, DATE = 10, HOUR = 11, MIN = 12, LEN = 14, LOC = 15, OK = 22, NO = 18, LIST = 26;

    static final class Holder implements InventoryHolder {
        Inventory inv;
        int day, hour, minute, length;
        Location loc;
        boolean done;
        @Override public Inventory getInventory() { return inv; }
    }

    private final EventCorePlugin pl;

    public RitualScheduleGui(EventCorePlugin pl) { this.pl = pl; }

    private ZoneId zone() { return pl.ritualSchedule.zone(); }
    private String label() { return pl.ritualSchedule.zoneLabel(); }
    private static String esc(String s) { return MiniMessage.miniMessage().escapeTags(s); }

    public void open(Player p) {
        Holder h = new Holder();
        ZonedDateTime t = ZonedDateTime.now(zone()).plusMinutes(30);
        h.day = 0;
        h.hour = t.getHour();
        h.minute = (t.getMinute() / 5) * 5;
        h.length = pl.ritual.minutes();
        h.loc = p.getLocation().clone();
        h.inv = Bukkit.createInventory(h, 27, pl.text.render("<gradient:#d8b04a:#fff0cc:#ffd7ca>ritual schedule | " + label() + "</gradient>", Map.of()));
        render(h);
        p.openInventory(h.inv);
    }

    private ZonedDateTime when(Holder h) {
        return LocalDate.now(zone()).plusDays(h.day).atTime(LocalTime.of(h.hour, h.minute)).atZone(zone());
    }

    private void render(Holder h) {
        Inventory inv = h.inv;
        ItemStack pane = pl.adminGui.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) if (i != ITEM) inv.setItem(i, pane);
        AdminGui a = pl.adminGui;
        ZonedDateTime w = when(h);
        String day = h.day == 0 ? "today" : h.day == 1 ? "tomorrow" : "in " + h.day + " days";
        inv.setItem(DATE, a.icon(Material.PAPER, "Date", "<color:%white%>" + DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).format(w) + "</color> (" + day + ")", "",
                Mouse.left(pl, "+1 day"), Mouse.right(pl, "-1 day"), Mouse.shiftLeft(pl, "+7 days"), Mouse.shiftRight(pl, "-7 days")));
        inv.setItem(HOUR, a.icon(Material.CLOCK, "Hour", "<color:%white%>" + String.format("%02d", h.hour) + ":00</color> (" + DateTimeFormatter.ofPattern("hh a", Locale.ENGLISH).format(w) + ")", "",
                Mouse.left(pl, "+1 hour"), Mouse.right(pl, "-1 hour"), Mouse.shiftLeft(pl, "+6 hours"), Mouse.shiftRight(pl, "-6 hours")));
        inv.setItem(MIN, a.icon(Material.REPEATER, "Minute", "<color:%white%>:" + String.format("%02d", h.minute) + "</color>", "",
                Mouse.left(pl, "+5 minutes"), Mouse.right(pl, "-5 minutes"), Mouse.shiftLeft(pl, "+15 minutes"), Mouse.shiftRight(pl, "-15 minutes"), Mouse.drop(pl, "set to the next full hour")));
        inv.setItem(LEN, a.icon(Material.CLOCK, "Ritual length", "<color:%white%>" + h.length + " minutes</color>", "",
                Mouse.left(pl, "+1 minute"), Mouse.right(pl, "-1 minute"), Mouse.shiftLeft(pl, "+5 minutes"), Mouse.shiftRight(pl, "-5 minutes")));
        String where = h.loc == null ? "not set" : esc(h.loc.getWorld().getName()) + " [" + h.loc.getBlockX() + ", " + h.loc.getBlockY() + ", " + h.loc.getBlockZ() + "]";
        inv.setItem(LOC, a.icon(Material.COMPASS, "Location", "<color:%white%>" + where + "</color>", "", Mouse.left(pl, "set to where you stand now")));
        inv.setItem(OK, a.icon(Material.LIME_CONCRETE, "Schedule ritual", "Starts " + esc(pl.ritualSchedule.fmt(w.toInstant().toEpochMilli())), "Place the item in the middle slot", "", Mouse.left(pl, "save the schedule")));
        inv.setItem(NO, a.icon(Material.RED_CONCRETE, "Cancel", "The item is returned to you", "", Mouse.left(pl, "close")));
        inv.setItem(LIST, a.icon(Material.BOOK, "Scheduled rituals", "Count: " + pl.ritualSchedule.list().size(), "", Mouse.left(pl, "list them in chat")));
    }

    private static boolean has(ItemStack i) { return i != null && !i.getType().isAir(); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("eventcore.ritual")) { e.setCancelled(true); return; }
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (raw >= top.getSize() || raw == ITEM) return;
        e.setCancelled(true);
        ClickType c = e.getClick();
        int dir = c.isLeftClick() ? 1 : c.isRightClick() ? -1 : 0;
        boolean sh = c.isShiftClick();
        switch (raw) {
            case DATE -> { if (dir != 0) { h.day = Math.max(0, Math.min(60, h.day + dir * (sh ? 7 : 1))); render(h); } }
            case HOUR -> { if (dir != 0) { h.hour = Math.floorMod(h.hour + dir * (sh ? 6 : 1), 24); render(h); } }
            case MIN -> {
                if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) { h.minute = 0; h.hour = (h.hour + 1) % 24; render(h); }
                else if (dir != 0) { h.minute = Math.floorMod(h.minute + dir * (sh ? 15 : 5), 60); render(h); }
            }
            case LEN -> { if (dir != 0) { h.length = Math.max(pl.ritual.minMinutes(), Math.min(pl.ritual.maxMinutes(), h.length + dir * (sh ? 5 : 1))); render(h); } }
            case LOC -> { if (c.isLeftClick()) { h.loc = p.getLocation().clone(); render(h); } }
            case OK -> confirm(p, h, top);
            case NO -> pl.sched.entity(p, p::closeInventory);
            case LIST -> { pl.sched.entity(p, p::closeInventory); list(p); }
            default -> { }
        }
    }

    private void confirm(Player p, Holder h, Inventory top) {
        ItemStack item = top.getItem(ITEM);
        if (!has(item)) { p.sendMessage(pl.msg("ritual-schedule.need-item")); return; }
        if (h.loc == null) { p.sendMessage(pl.msg("ritual-schedule.need-location")); return; }
        ZonedDateTime w = when(h);
        if (!w.toInstant().isAfter(java.time.Instant.now())) { p.sendMessage(pl.msg("ritual-schedule.in-the-past", "zone", label())); return; }
        String id = pl.ritualSchedule.add(p, item, w.toInstant().toEpochMilli(), h.loc, h.length);
        top.setItem(ITEM, null);          // the schedule owns the item now
        h.done = true;
        p.sendMessage(pl.msg("ritual-schedule.scheduled", "id", id, "when", pl.ritualSchedule.fmt(w.toInstant().toEpochMilli()), "world", h.loc.getWorld().getName(),
                "x", h.loc.getBlockX(), "y", h.loc.getBlockY(), "z", h.loc.getBlockZ(), "minutes", h.length,
                "item", com.eventcore.bounty.BountyManager.nameOf(item)));
        pl.sched.entity(p, p::closeInventory);
    }

    /** Chat list used by the book button and /ritual list. */
    public void list(Player p) { list((org.bukkit.command.CommandSender) p); }

    public void list(org.bukkit.command.CommandSender s) {
        var l = pl.ritualSchedule.list();
        if (l.isEmpty()) { s.sendMessage(pl.msg("ritual-schedule.nothing")); return; }
        s.sendMessage(pl.msg("ritual-schedule.list-header", "count", l.size()));
        for (var e : l)
            s.sendMessage(pl.text.render(pl.fx.msg(pl.cfg.messages(), "ritual-schedule.list-line").tpl(),
                    Text.ph("id", e.id, "when", pl.ritualSchedule.fmt(e.at), "world", e.world, "x", (int) e.x, "y", (int) e.y, "z", (int) e.z,
                            "minutes", e.minutes, "item", com.eventcore.bounty.BountyManager.nameOf(e.item), "creator", e.creator)));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        for (int s : e.getRawSlots()) if (s < top.getSize() && s != ITEM) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || h.done || !(e.getPlayer() instanceof Player p)) return;
        ItemStack it = h.inv.getItem(ITEM);
        if (has(it)) { h.inv.setItem(ITEM, null); Util.give(p, it); }
    }
}
