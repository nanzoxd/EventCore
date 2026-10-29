package com.eventcore.gui;

import com.eventcore.EventCorePlugin;
import com.eventcore.bounty.BountyManager;
import com.eventcore.hunt.HuntManager;
import com.eventcore.util.Mouse;
import com.eventcore.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Consumer;

/** Admin pages for Player Hunt and Bounty (opened from /eventcore gui). Built on the AdminGui page helpers. */
public final class HuntBountyGui {
    private final EventCorePlugin pl;

    public HuntBountyGui(EventCorePlugin pl) { this.pl = pl; }

    private AdminGui a() { return pl.adminGui; }

    private ItemStack head(UUID id, String name, String... lore) {
        ItemStack it = a().icon(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(Bukkit.getOfflinePlayer(id)); it.setItemMeta(sm); }
        return it;
    }

    /** A number in config.yml with left/right steppers (shift = x5). */
    private void stepper(AdminGui.Page pg, int slot, Material m, String label, String path, int step, int min, int max, String after) {
        int v = pl.cfg.config().getInt(path);
        a().set(pg, slot, a().icon(m, label, "Value: <color:%white%>" + v, "", Mouse.left(pl, "+" + step), Mouse.right(pl, "-" + step),
                Mouse.shiftLeft(pl, "+" + step * 5), Mouse.shiftRight(pl, "-" + step * 5)), (p, c) -> {
            int nv = v + (c.isLeftClick() ? 1 : -1) * step * (c.isShiftClick() ? 5 : 1);
            pl.cfg.set("config", path, Math.max(min, Math.min(max, nv)));
            pl.afterConfigChange("config");
            pg.fill.run();
        });
    }

    private void toggle(AdminGui.Page pg, int slot, Material m, String label, String path, boolean def) {
        boolean on = pl.cfg.config().getBoolean(path, def);
        a().set(pg, slot, a().icon(m, label, "Now: " + (on ? "<color:%good%>On" : "<color:%bad%>Off"), "", Mouse.left(pl, "turn " + (on ? "off" : "on"))), (p, c) -> {
            pl.cfg.set("config", path, !on);
            pl.afterConfigChange("config");
            pg.fill.run();
        });
    }

    // ---------- Player hunt ----------
    public void openHunt(Player p) {
        a().open(p, "Player hunt", 4, pg -> {
            int slot = 10;
            for (HuntManager.View v : pl.hunt.views()) {
                if (slot == 17) slot = 19;
                if (slot > 25) break;
                String where = v.online() ? v.world() + " [" + v.x() + ", " + v.y() + ", " + v.z() + "]" : "offline";
                a().set(pg, slot++, head(v.id(), v.name(), "Position: <color:%white%>" + where, "", Mouse.right(pl, "stop hunting")), (pl2, c) -> {
                    if (c.isRightClick()) { pl.hunt.stop(v.id()); pg.fill.run(); }
                });
            }
            a().set(pg, 28, a().icon(Material.COMPASS, "Hunt players", "Pick one or more online players", "", Mouse.left(pl, "choose")),
                    (pl2, c) -> picker(pl2, "Hunt players", true, list -> {
                        List<String> added = pl.hunt.start(list);
                        pl2.sendMessage(pl.msg(added.isEmpty() ? "hunt.nothing-added" : "hunt.started", "players", String.join(", ", added), "max", pl.hunt.max()));
                        openHunt(pl2);
                    }, () -> openHunt(pl2)));
            a().set(pg, 29, a().icon(Material.BARRIER, "Stop all", "End every hunt", "", Mouse.left(pl, "stop all")), (pl2, c) -> {
                pl2.sendMessage(pl.msg("hunt.stopped-all", "count", pl.hunt.stopAll()));
                pg.fill.run();
            });
            stepper(pg, 31, Material.CLOCK, "Chat update (seconds)", "playerhunt.broadcast-seconds", 5, 5, 3600, null);
            toggle(pg, 32, Material.TOTEM_OF_UNDYING, "Stop when target dies", "playerhunt.stop-on-death", false);
            a().back(pg, 34);
        });
    }

    // ---------- Bounty ----------
    public void openBounty(Player p) {
        a().open(p, "Bounty", 4, pg -> {
            int slot = 10;
            for (BountyManager.View v : pl.bounty.views()) {
                if (slot == 17) slot = 19;
                if (slot > 25) break;
                String st = v.state() == BountyManager.State.ACTIVE ? "<color:%good%>Hunted" : "<color:%bad%>Offline (" + Util.mmss(v.grace()) + " to return)";
                a().set(pg, slot++, head(v.target(), v.targetName(), "Reward: <color:%white%>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(v.rewardName()),
                        "Time left: <color:%white%>" + Util.mmss(v.remaining()), "State: " + st, "Set by: <color:%white%>" + v.creator(), "",
                        Mouse.right(pl, "cancel, reward goes back")), (pl2, c) -> {
                    if (c.isRightClick()) { pl.bounty.cancel(v.target()); pg.fill.run(); }
                });
            }
            a().set(pg, 28, a().icon(Material.GOLDEN_SWORD, "New bounty", "Pick the target, then set reward and timer", "", Mouse.left(pl, "choose target")),
                    (pl2, c) -> picker(pl2, "Bounty target", false, list -> {
                        if (!list.isEmpty()) pl.bountyGui.open(pl2, list.get(0));
                    }, () -> openBounty(pl2)));
            a().set(pg, 29, a().icon(Material.BARRIER, "Cancel all", "Ends every bounty, rewards go back", "", Mouse.left(pl, "cancel all")), (pl2, c) -> {
                pl.bounty.cancelAll();
                pg.fill.run();
            });
            stepper(pg, 30, Material.CLOCK, "Default timer (minutes)", "bounty.timer.default-minutes", 5, 1, 240, null);
            stepper(pg, 31, Material.REPEATER, "Reconnect time (seconds)", "bounty.disconnect.grace-seconds", 30, 5, 3600, null);
            stepper(pg, 32, Material.HOPPER, "Chat update (seconds)", "bounty.broadcast-seconds", 5, 5, 3600, null);
            toggle(pg, 33, Material.ENDER_PEARL, "Pause timer while offline", "bounty.disconnect.pause-timer", true);
            a().back(pg, 34);
        });
    }

    // ---------- player picker ----------
    private void picker(Player p, String title, boolean multi, Consumer<List<Player>> done, Runnable cancel) {
        pickerPage(p, title, multi, done, cancel, new LinkedHashSet<>(), 0);
    }

    private void pickerPage(Player p, String title, boolean multi, Consumer<List<Player>> done, Runnable cancel, Set<UUID> chosen, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (online.size() + 44) / 45);
        int pg0 = Math.max(0, Math.min(pages - 1, page));
        a().open(p, title, 6, pg -> {
            for (int i = 0; i < 45; i++) {
                int idx = pg0 * 45 + i;
                if (idx >= online.size()) break;
                Player t = online.get(idx);
                boolean sel = chosen.contains(t.getUniqueId());
                a().set(pg, i, head(t.getUniqueId(), t.getName(), multi ? (sel ? "<color:%good%>Selected" : "Not selected") : "Online", "",
                        Mouse.left(pl, multi ? (sel ? "unselect" : "select") : "choose")), (pl2, c) -> {
                    if (!multi) { Player now = Bukkit.getPlayer(t.getUniqueId()); done.accept(now == null ? List.of() : List.of(now)); return; }
                    if (!chosen.remove(t.getUniqueId())) chosen.add(t.getUniqueId());
                    pickerPage(pl2, title, true, done, cancel, chosen, pg0);
                });
            }
            a().set(pg, 45, a().icon(Material.ARROW, "Back", Mouse.left(pl, "go back")), (pl2, c) -> cancel.run());
            if (pg0 > 0) a().set(pg, 48, a().icon(Material.ARROW, "Previous page", Mouse.left(pl, "previous")), (pl2, c) -> pickerPage(pl2, title, multi, done, cancel, chosen, pg0 - 1));
            a().set(pg, 49, a().icon(Material.PAPER, "Page " + (pg0 + 1) + " / " + pages, multi ? "Selected: " + chosen.size() : "Online: " + online.size()), null);
            if (pg0 < pages - 1) a().set(pg, 50, a().icon(Material.ARROW, "Next page", Mouse.left(pl, "next")), (pl2, c) -> pickerPage(pl2, title, multi, done, cancel, chosen, pg0 + 1));
            if (multi) a().set(pg, 53, a().icon(Material.LIME_CONCRETE, "Start", "Selected: " + chosen.size(), "", Mouse.left(pl, "start")), (pl2, c) -> {
                List<Player> list = new ArrayList<>();
                for (UUID id : chosen) { Player t = Bukkit.getPlayer(id); if (t != null) list.add(t); }
                if (list.isEmpty()) return;
                done.accept(list);
            });
        });
    }
}
