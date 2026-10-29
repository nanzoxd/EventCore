package com.eventcore.bounty;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.BossBars;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Bounty Hunt. An admin picks a target, puts any item in the reward slot and sets a survival timer.
 *  - target survives the timer      -> the target gets the reward
 *  - another player kills the target -> the killer gets the reward
 *  - target offline for longer than bounty.disconnect.grace-seconds -> the bounty moves to another eligible online player
 * Rewards are only ever handed out through finish(), which flips an atomic "done" flag, removes the bounty and parks the item
 * in a persistent pending list in ONE file write. The item then moves to the winner's inventory and only afterwards leaves the
 * pending list, so it can neither be paid twice nor vanish if the winner is offline or the server stops. State lives in plugins/EventCore/bounties.yml.
 */
public final class BountyManager implements Listener {
    public enum State { ACTIVE, OFFLINE, TRANSFERRING }

    static final class Bounty {
        final UUID id;
        volatile UUID target;
        volatile String targetName;
        final UUID creator;
        final String creatorName;
        final ItemStack reward;
        final String rewardName;
        final int total;
        volatile int remaining;
        volatile int grace = -1;
        volatile State state = State.OFFLINE;
        volatile boolean done;
        final BossBar bar;
        volatile ScheduledTask coords;
        volatile String world = "-";
        volatile int x, y, z;
        int sinceBroadcast;

        Bounty(UUID id, UUID target, String targetName, UUID creator, String creatorName, ItemStack reward, int total, int remaining, BossBar bar) {
            this.id = id; this.target = target; this.targetName = targetName; this.creator = creator; this.creatorName = creatorName;
            this.reward = reward; this.rewardName = nameOf(reward); this.total = total; this.remaining = remaining; this.bar = bar;
        }
    }

    /** Read-only snapshot for the GUI and commands. */
    public record View(UUID id, UUID target, String targetName, String rewardName, ItemStack reward, int remaining, int total, State state, int grace, String creator) { }

    private final EventCorePlugin pl;
    private final Object lock = new Object();
    private final Map<UUID, Bounty> byTarget = new HashMap<>();        // guarded by lock
    private final Map<UUID, List<String>> pending = new HashMap<>();   // guarded by lock: recipient -> serialized reward items
    private final File file;
    private volatile ScheduledTask ticker;
    private int saveTicks;

    public BountyManager(EventCorePlugin pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "bounties.yml");
    }

    // ---------- config ----------
    private YamlConfiguration cfg() { return pl.cfg.config(); }
    public boolean enabled() { return cfg().getBoolean("bounty.enabled", true); }
    public int max() { return Math.max(1, cfg().getInt("bounty.max-active", 5)); }
    public int graceSeconds() { return Math.max(5, cfg().getInt("bounty.disconnect.grace-seconds", 120)); }
    public int defaultMinutes() { return Math.max(minMinutes(), Math.min(maxMinutes(), cfg().getInt("bounty.timer.default-minutes", 30))); }
    public int minMinutes() { return Math.max(1, cfg().getInt("bounty.timer.min-minutes", 1)); }
    public int maxMinutes() { return Math.max(minMinutes(), cfg().getInt("bounty.timer.max-minutes", 240)); }
    public int stepMinutes() { return Math.max(1, cfg().getInt("bounty.timer.step-minutes", 1)); }
    private ConfigurationSection msgSec(String path) { return pl.cfg.messages().getConfigurationSection(path); }
    private ConfigurationSection soundSec(String key) { return cfg().getConfigurationSection("bounty.sounds." + key); }
    private String tpl(String path) { return pl.fx.msg(pl.cfg.messages(), path).tpl(); }

    // ---------- lifecycle ----------
    public void start() {
        load();
        ticker = pl.sched.globalTimer(t -> tick(), 20, 20);
        for (Player p : Bukkit.getOnlinePlayers()) pl.sched.entityLater(p, () -> flush(p), 20);
    }

    public void shutdown() {
        Sched.cancel(ticker);
        ticker = null;
        synchronized (lock) {
            for (Bounty b : byTarget.values()) { Sched.cancel(b.coords); b.coords = null; pl.bars.hide(b.bar); }
            save();
        }
    }

    // ---------- public API ----------
    public int count() { synchronized (lock) { return byTarget.size(); } }
    public boolean hasBounty(UUID target) { synchronized (lock) { return byTarget.containsKey(target); } }

    public List<View> views() {
        List<View> l = new ArrayList<>();
        synchronized (lock) {
            for (Bounty b : byTarget.values())
                l.add(new View(b.id, b.target, b.targetName, b.rewardName, b.reward.clone(), b.remaining, b.total, b.state, b.grace, b.creatorName));
        }
        l.sort(Comparator.comparing(View::targetName, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    /** Creates a bounty. Returns null on success or an error key (bounty.error-&lt;key&gt; in messages.yml). */
    public String create(Player admin, Player target, ItemStack reward, int minutes) {
        if (!enabled()) return "disabled";
        if (reward == null || reward.getType().isAir()) return "need-reward";
        minutes = Math.max(minMinutes(), Math.min(maxMinutes(), minutes));
        Bounty b;
        synchronized (lock) {
            if (byTarget.containsKey(target.getUniqueId())) return "already";
            if (byTarget.size() >= max()) return "limit";
            b = new Bounty(UUID.randomUUID(), target.getUniqueId(), target.getName(), admin.getUniqueId(), admin.getName(),
                    reward.clone(), minutes * 60, minutes * 60, newBar());
            b.state = State.ACTIVE;
            byTarget.put(b.target, b);
            save();
        }
        pl.bars.show(b.bar);
        updateBar(b);
        pl.sched.entity(target, () -> attach(b, target));
        Map<String, Object> ph = ph(b);
        ph.put("minutes", minutes);
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.started", ph));
        pl.fx.playAll(soundSec("start"));
        pl.fx.title(target, msgSec("bounty.titles.target-start"), ph);
        pl.discord.bountyStarted(target.getName(), b.rewardName, minutes);
        return null;
    }

    /** Cancels one bounty (by target); the reward goes back to the admin who created it. */
    public boolean cancel(UUID target) {
        Bounty b;
        synchronized (lock) { b = byTarget.get(target); }
        return b != null && expire(b, "bounty.cancelled");
    }

    public String cancel(String targetName) {
        Bounty found = null;
        synchronized (lock) { for (Bounty b : byTarget.values()) if (b.targetName.equalsIgnoreCase(targetName)) found = b; }
        if (found == null) return null;
        String n = found.targetName;
        return expire(found, "bounty.cancelled") ? n : null;
    }

    public int cancelAll() {
        List<Bounty> l;
        synchronized (lock) { l = new ArrayList<>(byTarget.values()); }
        int n = 0;
        for (Bounty b : l) if (expire(b, "bounty.cancelled")) n++;
        return n;
    }

    // ---------- tick ----------
    private void tick() {
        List<Bounty> list;
        synchronized (lock) { list = new ArrayList<>(byTarget.values()); }
        int bc = Math.max(5, cfg().getInt("bounty.broadcast-seconds", 30));
        boolean pause = cfg().getBoolean("bounty.disconnect.pause-timer", true);
        for (Bounty b : list) {
            if (b.done) continue;
            State s = b.state;
            if (s == State.ACTIVE) {
                if (--b.remaining <= 0) { survive(b); continue; }
                if (++b.sinceBroadcast >= bc) { b.sinceBroadcast = 0; broadcastCoords(b); }
            } else if (s == State.OFFLINE) {
                if (!pause && --b.remaining <= 0) { survive(b); continue; }
                if (--b.grace <= 0) { transfer(b); continue; }
            } else continue;
            updateBar(b);
        }
        if (++saveTicks >= Math.max(1, cfg().getInt("bounty.save-interval-seconds", 10))) {
            saveTicks = 0;
            synchronized (lock) { if (!byTarget.isEmpty()) save(); }
        }
    }

    // ---------- outcomes ----------
    private void survive(Bounty b) {
        UUID target = b.target;
        Map<String, Object> ph = ph(b);
        if (!finish(b, target)) return;
        Player p = Bukkit.getPlayer(target);
        if (p != null) { pl.fx.title(p, msgSec("bounty.titles.survived"), ph); pl.fx.play(p, soundSec("victory")); }
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.survived", ph));
        deliver(target);
    }

    private void claim(Bounty b, Player killer, Player victim) {
        Map<String, Object> ph = ph(b);
        ph.put("killer", killer.getName());
        if (!finish(b, killer.getUniqueId())) return;
        pl.fx.title(killer, msgSec("bounty.titles.claimed"), ph);
        pl.fx.title(victim, msgSec("bounty.titles.killed"), ph);
        pl.fx.play(killer, soundSec("victory"));
        pl.fx.play(victim, soundSec("defeat"));
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.claimed", ph));
        deliver(killer.getUniqueId());
    }

    /** Bounty ends without a winner: the reward goes back to its creator. */
    private boolean expire(Bounty b, String msgKey) {
        UUID creator = b.creator;
        Map<String, Object> ph = ph(b);
        if (!finish(b, creator)) return false;
        Bukkit.getServer().sendMessage(pl.msgMap(msgKey, ph));
        deliver(creator);
        return true;
    }

    /**
     * The ONLY place a reward leaves a bounty. Exactly one caller wins the done flag; the bounty is removed and the item is
     * parked in the pending list of {@code recipient} within one synchronized block and one atomic file write.
     */
    private boolean finish(Bounty b, UUID recipient) {
        synchronized (lock) {
            if (b.done) return false;
            b.done = true;
            byTarget.remove(b.target, b);
            pending.computeIfAbsent(recipient, k -> new ArrayList<>()).add(encode(b.reward));
            save();
        }
        Sched.cancel(b.coords);
        b.coords = null;
        pl.bars.hide(b.bar);
        return true;
    }

    // ---------- reward delivery ----------
    /** Hands over everything parked for this player, if they are online. Otherwise it stays pending until they join. */
    private void deliver(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        p.getScheduler().run(pl, t -> flush(p), null);
    }

    /** Runs on the player's own thread. */
    private void flush(Player p) {
        if (!p.isOnline()) return;
        List<String> tokens;
        synchronized (lock) {
            List<String> l = pending.get(p.getUniqueId());
            if (l == null || l.isEmpty()) return;
            tokens = new ArrayList<>(l);
        }
        boolean dropped = false;
        for (String tk : tokens) {
            ItemStack it = decode(tk);
            if (it == null) { pl.getLogger().warning("A pending bounty reward for " + p.getName() + " could not be read and was skipped."); continue; }
            dropped |= giveSafe(p, it);
        }
        try { p.saveData(); } catch (Throwable ignored) { }      // write the inventory before the pending entry is released
        synchronized (lock) {
            List<String> l = pending.get(p.getUniqueId());
            if (l != null) {
                l.subList(0, Math.min(tokens.size(), l.size())).clear();   // entries are only ever appended, so the first n are the ones just given
                if (l.isEmpty()) pending.remove(p.getUniqueId());
            }
            save();
        }
        p.sendMessage(pl.msg(dropped ? "bounty.reward-dropped" : "bounty.reward-received"));
    }

    /** Puts the item in the inventory; what does not fit is dropped at the feet, owned by the player, never despawning. */
    private boolean giveSafe(Player p, ItemStack it) {
        boolean dropped = false;
        for (ItemStack left : p.getInventory().addItem(it).values()) {
            Item d = p.getWorld().dropItem(p.getLocation(), left);
            try { d.setOwner(p.getUniqueId()); d.setUnlimitedLifetime(true); d.setInvulnerable(true); } catch (Throwable ignored) { }
            dropped = true;
        }
        return dropped;
    }

    // ---------- disconnect handling ----------
    private void transfer(Bounty b) {
        synchronized (lock) {
            if (b.done || b.state == State.TRANSFERRING) return;
            b.state = State.TRANSFERRING;
        }
        if (!"TRANSFER".equalsIgnoreCase(cfg().getString("bounty.disconnect.action", "TRANSFER"))) { expire(b, "bounty.cancelled-offline"); return; }
        String perm = cfg().getString("bounty.transfer.exclude-permission", "eventcore.bounty.exempt");
        List<Player> cands = new ArrayList<>();
        synchronized (lock) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().equals(b.target) || byTarget.containsKey(p.getUniqueId())) continue;
                if (!perm.isBlank() && p.hasPermission(perm)) continue;
                cands.add(p);
            }
        }
        Collections.shuffle(cands);
        tryCandidate(b, cands, 0);
    }

    /** Each candidate is checked on its own thread (game mode), one after the other, until somebody fits. */
    private void tryCandidate(Bounty b, List<Player> c, int i) {
        if (b.done) return;
        if (i >= c.size()) { expire(b, "bounty.no-candidate"); return; }
        Player p = c.get(i);
        p.getScheduler().run(pl, t -> {
            if (!(eligibleHere(p) && assign(b, p))) tryCandidate(b, c, i + 1);
        }, () -> tryCandidate(b, c, i + 1));
    }

    private boolean eligibleHere(Player p) {
        if (!p.isOnline() || p.isDead()) return false;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR && cfg().getBoolean("bounty.transfer.exclude-spectator", true)) return false;
        return !(gm == GameMode.CREATIVE && cfg().getBoolean("bounty.transfer.exclude-creative", true));
    }

    /** On the new target's thread. Returns false when this player cannot take it (then the next candidate is tried). */
    private boolean assign(Bounty b, Player p) {
        synchronized (lock) {
            if (b.done) return true;
            if (byTarget.containsKey(p.getUniqueId())) return false;
            byTarget.remove(b.target, b);
            b.target = p.getUniqueId();
            b.targetName = p.getName();
            b.state = State.ACTIVE;
            b.grace = -1;
            if (!cfg().getBoolean("bounty.transfer.keep-remaining-time", true)) b.remaining = b.total;
            b.sinceBroadcast = 0;
            byTarget.put(b.target, b);
            save();
        }
        attach(b, p);
        updateBar(b);
        Map<String, Object> ph = ph(b);
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.transferred", ph));
        pl.fx.title(p, msgSec("bounty.titles.new-target"), ph);
        pl.fx.play(p, soundSec("start"));
        return true;
    }

    // ---------- events ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        Bounty b;
        synchronized (lock) { b = byTarget.get(victim.getUniqueId()); }
        if (b == null || b.done) return;
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) claim(b, killer, victim);
        else if ("RETURN".equalsIgnoreCase(cfg().getString("bounty.environment-death", "CONTINUE"))) expire(b, "bounty.cancelled-death");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Bounty b;
        synchronized (lock) { b = byTarget.get(e.getPlayer().getUniqueId()); }
        if (b == null || b.done || b.state != State.ACTIVE) return;
        b.state = State.OFFLINE;
        b.grace = graceSeconds();
        Sched.cancel(b.coords);
        b.coords = null;
        updateBar(b);
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.target-left", ph(b)));
        synchronized (lock) { save(); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bounty b;
        synchronized (lock) { b = byTarget.get(p.getUniqueId()); }
        if (b != null && !b.done && b.state == State.OFFLINE) {
            b.state = State.ACTIVE;
            b.grace = -1;
            b.targetName = p.getName();
            attach(b, p);
            updateBar(b);
            Bukkit.getServer().sendMessage(pl.msgMap("bounty.target-back", ph(b)));
        }
        pl.sched.entityLater(p, () -> flush(p), 20);
    }

    // ---------- coordinates, bossbar, broadcast ----------
    private void attach(Bounty b, Player p) {
        Sched.cancel(b.coords);
        refresh(b, p);
        b.coords = pl.sched.entityTimer(p, t -> {
            if (b.done || !b.target.equals(p.getUniqueId())) { t.cancel(); return; }
            refresh(b, p);
        }, () -> { }, 20, 20);
    }

    private void refresh(Bounty b, Player p) {
        Location l = p.getLocation();
        b.world = l.getWorld().getName();
        b.x = l.getBlockX(); b.y = l.getBlockY(); b.z = l.getBlockZ();
    }

    private BossBar newBar() {
        ConfigurationSection s = cfg().getConfigurationSection("bounty.bossbar");
        BossBar.Color color = BossBars.enumOf(BossBar.Color.class, s == null ? "PINK" : s.getString("color", "PINK"), BossBar.Color.PINK);
        BossBar.Overlay style = BossBars.enumOf(BossBar.Overlay.class, s == null ? "PROGRESS" : s.getString("style", "PROGRESS"), BossBar.Overlay.PROGRESS);
        return BossBar.bossBar(Component.text(" "), 1f, color, style);
    }

    private void updateBar(Bounty b) {
        String key = b.state == State.ACTIVE ? "bounty.bossbar" : "bounty.bossbar-offline";
        b.bar.name(pl.text.render(tpl(key), ph(b)));
        b.bar.progress(BossBars.clamp(b.total <= 0 ? 0 : (double) b.remaining / b.total));
    }

    private void broadcastCoords(Bounty b) {
        Bukkit.getServer().sendMessage(pl.msgMap("bounty.coords", ph(b)));
        pl.fx.playAll(cfg().getConfigurationSection("bounty.sounds.coords"));
    }

    private Map<String, Object> ph(Bounty b) {
        return Text.ph("player", b.targetName, "reward", b.rewardName, "world", b.world, "x", b.x, "y", b.y, "z", b.z,
                "time", Util.mmss(b.remaining), "grace", Util.mmss(Math.max(0, b.grace)), "creator", b.creatorName, "minutes", b.total / 60);
    }

    /** "Netherite Sword" or the custom name; "x16" appended for stacks. */
    public static String nameOf(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        String n = m != null && m.hasDisplayName() && m.displayName() != null ? Text.plain(m.displayName()) : Util.pretty(it.getType());
        return it.getAmount() > 1 ? n + " x" + it.getAmount() : n;
    }

    // ---------- persistence ----------
    private static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

    private static ItemStack decode(String s) {
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
        catch (Exception e) { return null; }
    }

    /** Must be called with the lock held. Writes to a temp file first, then swaps it in, so a crash never leaves half a file. */
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Bounty b : byTarget.values()) {
            String k = "bounties." + b.id;
            y.set(k + ".target", b.target.toString());
            y.set(k + ".target-name", b.targetName);
            y.set(k + ".creator", b.creator.toString());
            y.set(k + ".creator-name", b.creatorName);
            y.set(k + ".total", b.total);
            y.set(k + ".remaining", b.remaining);
            y.set(k + ".reward", encode(b.reward));
        }
        for (Map.Entry<UUID, List<String>> e : pending.entrySet())
            if (!e.getValue().isEmpty()) y.set("pending." + e.getKey(), new ArrayList<>(e.getValue()));
        try {
            pl.getDataFolder().mkdirs();
            Path tmp = file.toPath().resolveSibling("bounties.yml.tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save bounties.yml: " + ex.getMessage());
        }
    }

    /** Active bounties come back with the time they had left. A target who is not online starts the reconnect grace period. */
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        List<Bounty> restored = new ArrayList<>();
        synchronized (lock) {
            ConfigurationSection pend = y.getConfigurationSection("pending");
            if (pend != null) for (String k : pend.getKeys(false)) {
                try { pending.put(UUID.fromString(k), new ArrayList<>(pend.getStringList(k))); } catch (IllegalArgumentException ignored) { }
            }
            ConfigurationSection bs = y.getConfigurationSection("bounties");
            if (bs != null) for (String k : bs.getKeys(false)) {
                ConfigurationSection s = bs.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    ItemStack reward = decode(s.getString("reward", ""));
                    if (reward == null) { pl.getLogger().warning("Bounty " + k + " has an unreadable reward and was skipped."); continue; }
                    UUID target = UUID.fromString(s.getString("target", ""));
                    UUID creator = UUID.fromString(s.getString("creator", ""));
                    int total = Math.max(1, s.getInt("total", 60));
                    Bounty b = new Bounty(UUID.fromString(k), target, s.getString("target-name", "?"), creator, s.getString("creator-name", "?"),
                            reward, total, Math.max(1, s.getInt("remaining", total)), newBar());
                    b.state = State.OFFLINE;
                    b.grace = graceSeconds();
                    if (byTarget.putIfAbsent(target, b) == null) restored.add(b);
                } catch (IllegalArgumentException ex) {
                    pl.getLogger().warning("Bounty " + k + " is invalid and was skipped: " + ex.getMessage());
                }
            }
        }
        for (Bounty b : restored) {
            pl.bars.show(b.bar);
            Player p = Bukkit.getPlayer(b.target);
            if (p != null) {
                b.state = State.ACTIVE;
                b.grace = -1;
                pl.sched.entity(p, () -> attach(b, p));
            }
            updateBar(b);
        }
        if (!restored.isEmpty()) pl.getLogger().info("Restored " + restored.size() + " active bounty(ies).");
    }
}
