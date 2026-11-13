package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The whole Quest Event: state, progress, completion, leaderboard, rewards and persistence. */
public final class QuestManager {
    public enum Status { LOCKED, AVAILABLE, ACTIVE, COMPLETED }

    public static final int TOP = 5;
    private static final long RECENT_MS = 30_000;

    public final EventCorePlugin pl;
    public final QuestRegistry reg;
    public final QuestStore store;
    public final QuestToast toast;

    final Map<UUID, PlayerQuestData> data = new ConcurrentHashMap<>();
    private volatile Map<Integer, List<ItemStack>> rewards = new HashMap<>();
    private final Map<UUID, List<QuestStore.Pending>> pending = new ConcurrentHashMap<>();

    public volatile boolean running, paused;
    private volatile boolean shuttingDown, restoring;
    private volatile ActiveEvent current;

    private volatile List<PlayerQuestData> ranking = List.of();
    private volatile boolean rankDirty = true;
    private final Map<UUID, Integer> prevRank = new ConcurrentHashMap<>();

    private static final class Bar { BossBar bar; volatile long hideAt; }
    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastShown = new ConcurrentHashMap<>();
    final Map<UUID, double[]> travel = new ConcurrentHashMap<>();
    /** short lived marks: kill cooldowns, "who hit whom", chest cooldowns. key -> expiry time */
    final Map<String, Long> recent = new ConcurrentHashMap<>();
    /** blocks a player placed (only ore-like targets), so placing + breaking does not count as mining */
    final Map<Long, Long> placed = new ConcurrentHashMap<>();

    private ScheduledTask timer;
    private int ticks;

    public QuestManager(EventCorePlugin pl) {
        this.pl = pl;
        this.reg = new QuestRegistry(pl);
        this.store = new QuestStore(pl);
        this.toast = new QuestToast(pl);
    }

    // =====================================================================================================
    // lifecycle
    // =====================================================================================================
    public void enable() {
        reg.load();
        store.open(new File(pl.getDataFolder(), "quests.db"));
        data.putAll(store.loadPlayers());
        rewards = store.loadRewards();
        pending.clear();
        store.loadPending().forEach((u, l) -> pending.put(u, new CopyOnWriteArrayList<>(l)));
        timer = pl.sched.globalTimer(t -> tick(), 20, 20);
        if ("1".equals(store.meta("running", "0")) && !"1".equals(store.meta("distributed", "0")))
            pl.sched.globalLater(this::restore, 100);                      // the server stopped during an event: carry on where it was
    }

    private void restore() {
        long left = 0;
        try { left = Long.parseLong(store.meta("left_ms", "0")); } catch (Exception ignored) { }
        int minutes = (int) Math.ceil(left / 60000.0);
        if (minutes <= 0) { store.setMeta("running", "0"); recomputeRanks(); distribute(); return; }
        restoring = true;
        String err = pl.events.start("quest", minutes, true);
        if (err != null) { restoring = false; pl.getLogger().warning("Quest event could not be restored: " + err); }
        else pl.getLogger().info("Quest event restored with " + minutes + " minutes left.");
    }

    public void reload() { reg.load(); rankDirty = true; }

    public void beginShutdown() { shuttingDown = true; }

    public void finishShutdown() {
        if (timer != null) timer.cancel();
        flushAll();
        for (Player p : Bukkit.getOnlinePlayers()) hideBar(p);
        bars.clear();
        store.close();
    }

    public void onEventStart(ActiveEvent ae) {
        current = ae;
        paused = false;
        if (!restoring) {                                                  // a fresh event: everybody starts from zero
            resetAll();
            store.setMeta("distributed", "0");
            placed.clear(); recent.clear();
        }
        restoring = false;
        running = true;
        store.setMeta("running", "1");
        rankDirty = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerQuestData d = dataFor(p);
            autoStart(p, d, true);
            toast.show(p, Material.WRITTEN_BOOK, Component.text("Quest Event started", TextColor.color(0xFFD54A)),
                    Component.text(reg.size() + " quests are waiting. Use /quest to see them.", TextColor.color(0xAAAAAA)), QuestToast.Frame.GOAL);
        }
    }

    public void onEventStop(ActiveEvent ae) {
        current = null;
        running = false;
        paused = false;
        if (shuttingDown) {                                                // server stop: keep everything, finish after the restart
            store.setMeta("running", "1");
            store.setMeta("left_ms", String.valueOf(Math.max(0, ae.end - System.currentTimeMillis())));
            flushAll();
            return;
        }
        store.setMeta("running", "0");
        flushAll();
        recomputeRanks();
        distribute();
    }

    /** Called every second by the event ticker: while paused the timer is frozen. */
    public void onEventSecond(ActiveEvent ae) {
        if (paused) ae.end += 1000;
    }

    public boolean pause() { if (!running || paused) return false; paused = true; return true; }
    public boolean resume() { if (!running || !paused) return false; paused = false; return true; }

    public String timeLeft() {
        ActiveEvent ae = current;
        if (ae == null) return "-";
        int s = ae.secondsLeft();
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String.format("%02d:%02d", s / 60, s % 60);
    }

    // =====================================================================================================
    // 1 second tick
    // =====================================================================================================
    private void tick() {
        ticks++;
        try {
            ActiveEvent ae = current;
            if (running && ae != null && ticks % 10 == 0) store.setMeta("left_ms", String.valueOf(Math.max(0, ae.end - System.currentTimeMillis())));   // crash recovery
            if (rankDirty || ticks % 5 == 0) recomputeRanks();
            if (ticks % 20 == 0) flushDirty();
            if (ticks % 60 == 0) cleanup();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Bar> e : bars.entrySet()) {
                if (now < e.getValue().hideAt) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                bars.remove(e.getKey());
                if (p != null) { Bar b = e.getValue(); pl.sched.entity(p, () -> p.hideBossBar(b.bar)); }
            }
            if (ticks % 2 == 0 && pl.questGui != null) pl.questGui.refreshOpen();
        } catch (Throwable t) { pl.getLogger().warning("quest tick: " + t); }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        recent.values().removeIf(t -> t < now);
        placed.values().removeIf(t -> t < now);
        if (placed.size() > 40_000) placed.clear();
        if (recent.size() > 20_000) recent.clear();
    }

    private void flushDirty() { for (PlayerQuestData d : data.values()) if (d.dirty) store.savePlayer(d); }
    private void flushAll() { for (PlayerQuestData d : data.values()) if (d.dirty) store.savePlayer(d); }

    // =====================================================================================================
    // player data
    // =====================================================================================================
    public PlayerQuestData dataFor(Player p) {
        PlayerQuestData d = data.computeIfAbsent(p.getUniqueId(), u -> new PlayerQuestData(u, p.getName()));
        if (!d.name.equals(p.getName())) { d.name = p.getName(); d.dirty = true; }
        return d;
    }

    public PlayerQuestData dataOf(UUID u) { return data.get(u); }

    /** Data for any player that ever joined (offline players included); created on demand. */
    public PlayerQuestData dataForOffline(UUID u, String name) { return data.computeIfAbsent(u, k -> new PlayerQuestData(k, name == null ? "unknown" : name)); }

    public void onJoin(Player p) {
        PlayerQuestData d = dataFor(p);
        if (running) autoStart(p, d, true);
        List<QuestStore.Pending> l = pending.get(p.getUniqueId());
        if (l != null && !l.isEmpty()) pl.sched.entityLater(p, () -> p.sendMessage(msg("rewards-waiting", "count", l.size())), 60);
    }

    public void onQuit(Player p) {
        PlayerQuestData d = data.get(p.getUniqueId());
        if (d != null && d.dirty) store.savePlayer(d);
        UUID u = p.getUniqueId();
        travel.remove(u); lastShown.remove(u); toast.forget(u);
        Bar b = bars.remove(u);
        if (b != null) p.hideBossBar(b.bar);
        prevRank.remove(u);
    }

    public boolean eligible(Player p) {
        if (!reg.ignoreCreative) return p.getGameMode() != GameMode.SPECTATOR;
        GameMode g = p.getGameMode();
        return g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    public boolean tracking() { return running && !paused; }

    /** /quest: the main menu (always opens, even when no event is running). */
    public void questGuiOpen(Player p) { pl.sched.entity(p, () -> pl.questGui.open(p)); }

    // =====================================================================================================
    // quest states
    // =====================================================================================================
    public boolean locked(PlayerQuestData d, Quest q) {
        return !q.requires.isEmpty() && reg.get(q.requires) != null && d.state(q.requires) != PlayerQuestData.DONE;
    }

    public Status status(PlayerQuestData d, Quest q) {
        if (d.state(q.id) == PlayerQuestData.DONE) return Status.COMPLETED;
        if (locked(d, q)) return Status.LOCKED;
        return d.state(q.id) == PlayerQuestData.ACTIVE ? Status.ACTIVE : Status.AVAILABLE;
    }

    /** Unlocked quests become active on their own (quests.yml settings.auto-start). */
    public void autoStart(Player p, PlayerQuestData d, boolean silent) {
        if (!reg.autoStart) return;
        int active = d.activeCount();
        List<Quest> started = new ArrayList<>();
        synchronized (d) {
            for (Quest q : reg.all()) {
                if (active >= reg.maxActive) break;
                if (d.state(q.id) == PlayerQuestData.AVAILABLE && !locked(d, q)) { d.state.put(q.id, PlayerQuestData.ACTIVE); active++; started.add(q); }
            }
        }
        if (started.isEmpty()) return;
        d.dirty = true;
        if (!silent) for (Quest q : started) toastStart(p, q);
    }

    /** error message key, or null when started */
    public String startQuest(Player p, Quest q) {
        PlayerQuestData d = dataFor(p);
        if (!running) return "not-running";
        synchronized (d) {
            if (d.state(q.id) == PlayerQuestData.DONE) return "already-completed";
            if (locked(d, q)) return "locked";
            if (d.state(q.id) == PlayerQuestData.ACTIVE) return "already-active";
            if (d.activeCount() >= reg.maxActive) return "too-many-active";
            d.state.put(q.id, PlayerQuestData.ACTIVE);
        }
        d.dirty = true;
        toastStart(p, q);
        return null;
    }

    public String abandon(Player p, Quest q) {
        PlayerQuestData d = dataFor(p);
        if (!reg.allowAbandon) return "abandon-disabled";
        synchronized (d) {
            if (d.state(q.id) != PlayerQuestData.ACTIVE) return "not-active";
            d.state.put(q.id, PlayerQuestData.ABANDONED);
            d.progress.remove(q.id);
        }
        d.dirty = true;
        return null;
    }

    private void toastStart(Player p, Quest q) {
        toast.show(p, q.icon, Component.text(q.title, TextColor.color(0xFFFFFF)), Component.text(q.objective() + " (+1 point)", TextColor.color(0xAAAAAA)), QuestToast.Frame.TASK);
    }

    // =====================================================================================================
    // progress
    // =====================================================================================================
    public void progress(Player p, QuestType type, String key, int amount) {
        if (!tracking() || amount <= 0) return;
        List<Quest> list = reg.byType(type);
        if (list.isEmpty() || !eligible(p)) return;
        PlayerQuestData d = dataFor(p);
        Quest shown = null;
        int shownCur = 0;
        List<Quest> done = null;
        synchronized (d) {
            for (Quest q : list) {
                if (!q.matches(key) || d.state(q.id) != PlayerQuestData.ACTIVE) continue;
                int cur = Math.min(q.amount, d.progress(q.id) + amount);
                d.progress.put(q.id, cur);
                d.dirty = true;
                shown = q; shownCur = cur;
                if (cur >= q.amount) {                                         // state flips exactly once: no double reward
                    d.state.put(q.id, PlayerQuestData.DONE);
                    d.points += q.points;
                    d.completed++;
                    d.lastGain = System.currentTimeMillis();
                    if (done == null) done = new ArrayList<>();
                    done.add(q);
                }
            }
        }
        if (shown == null) return;
        if (done != null) {
            rankDirty = true;
            for (Quest q : done) celebrate(p, q);
            autoStart(p, d, false);                                            // newly unlocked quests start (with their own toast)
        } else showProgress(p, d, shown, shownCur);
    }

    private void celebrate(Player p, Quest q) {
        toast.show(p, q.icon, Component.text(q.title, TextColor.color(0xFFD54A)).append(Component.text("  +1 point", TextColor.color(0x00FF55))),
                Component.text(q.objective() + " - quest completed", TextColor.color(0xAAAAAA)), QuestToast.Frame.CHALLENGE);
        Component bar = raw("completed-bar", "title", q.title, "max", q.amount, "points", q.points);
        pl.sched.entity(p, () -> p.sendActionBar(bar));
    }

    private void showProgress(Player p, PlayerQuestData d, Quest q, int cur) {
        String mode = d.display;
        if (mode.equals("OFF")) return;
        long now = System.currentTimeMillis();
        Long last = lastShown.get(p.getUniqueId());
        if (last != null && now - last < 250 && cur < q.amount) return;     // many events per second: show the newest only
        lastShown.put(p.getUniqueId(), now);
        float frac = Math.min(1f, (float) cur / q.amount);
        if (mode.equals("ACTIONBAR") || mode.equals("BOTH")) {
            Component c = raw("progress-bar", "title", q.title, "bar_raw", bar(cur, q.amount, 10), "cur", cur, "max", q.amount);
            pl.sched.entity(p, () -> p.sendActionBar(c));
        }
        if (mode.equals("BOSSBAR") || mode.equals("BOTH")) {
            Component title = raw("progress-boss", "title", q.title, "cur", cur, "max", q.amount);
            Bar b = bars.computeIfAbsent(p.getUniqueId(), u -> {
                Bar nb = new Bar();
                nb.bar = BossBar.bossBar(title, frac, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);
                pl.sched.entity(p, () -> p.showBossBar(nb.bar));
                return nb;
            });
            b.bar.name(title); b.bar.progress(frac); b.hideAt = now + 4000;
        }
    }

    private void hideBar(Player p) { Bar b = bars.get(p.getUniqueId()); if (b != null) p.hideBossBar(b.bar); }

    /** MiniMessage progress bar: [▮▮▮▯▯▯] */
    public static String bar(int cur, int max, int len) {
        int f = max <= 0 ? len : Math.min(len, (int) Math.round((double) cur / max * len));
        return "<#00FF55>" + "▮".repeat(f) + "<#555555>" + "▮".repeat(len - f);
    }

    // =====================================================================================================
    // admin operations
    // =====================================================================================================
    public boolean give(UUID u, String name, Quest q) {
        PlayerQuestData d = dataForOffline(u, name);
        synchronized (d) {
            if (d.state(q.id) == PlayerQuestData.DONE) return false;
            d.state.put(q.id, PlayerQuestData.DONE);
            d.progress.put(q.id, q.amount);
            d.points += q.points; d.completed++; d.lastGain = System.currentTimeMillis();
        }
        d.dirty = true; rankDirty = true;
        Player p = Bukkit.getPlayer(u);
        if (p != null) { celebrate(p, q); autoStart(p, d, false); }
        return true;
    }

    public void resetPlayer(UUID u, String name) {
        PlayerQuestData d = dataForOffline(u, name);
        d.clear();
        rankDirty = true;
        Player p = Bukkit.getPlayer(u);
        if (p != null && running) autoStart(p, d, true);
    }

    public void addPoints(UUID u, String name, int delta) {
        PlayerQuestData d = dataForOffline(u, name);
        d.points = Math.max(0, d.points + delta);
        if (delta > 0) d.lastGain = System.currentTimeMillis();
        d.dirty = true; rankDirty = true;
    }

    /** Reset statistics of everybody (new event / admin button). Offline players without anything to keep are dropped from memory. */
    public void resetAll() {
        store.clearPlayers();
        data.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        for (PlayerQuestData d : data.values()) d.clear();
        prevRank.clear();
        ranking = List.of();
        rankDirty = true;
        if (running) for (Player p : Bukkit.getOnlinePlayers()) autoStart(p, dataFor(p), true);
    }

    // =====================================================================================================
    // leaderboard
    // =====================================================================================================
    private static final Comparator<PlayerQuestData> ORDER = Comparator
            .comparingInt((PlayerQuestData d) -> d.points).reversed()
            .thenComparing(Comparator.comparingInt((PlayerQuestData d) -> d.completed).reversed())
            .thenComparingLong(d -> d.lastGain)
            .thenComparing(d -> d.name.toLowerCase(Locale.ROOT));

    private void recomputeRanks() {
        rankDirty = false;
        List<PlayerQuestData> l = new ArrayList<>();
        for (PlayerQuestData d : data.values()) if (d.points > 0) l.add(d);
        l.sort(ORDER);
        ranking = l;
        for (int i = 0; i < l.size(); i++) {
            PlayerQuestData d = l.get(i);
            int rank = i + 1;
            Integer old = prevRank.put(d.uuid, rank);
            if (old == null || old == rank || !running) continue;
            if (rank > TOP && old > TOP) continue;
            Player p = Bukkit.getPlayer(d.uuid);
            if (p == null) continue;
            if (rank < old) {
                Component c = raw("rank-up", "rank", rank);
                pl.sched.entity(p, () -> p.sendActionBar(c));
                if (rank <= TOP && old > TOP)
                    toast.show(p, Material.GOLDEN_HELMET, Component.text("Top " + TOP + ": rank #" + rank, TextColor.color(0xFFD54A)), Component.text("You entered the leaderboard", TextColor.color(0xAAAAAA)), QuestToast.Frame.GOAL);
            } else {
                Component c = raw("rank-down", "rank", rank);
                pl.sched.entity(p, () -> p.sendActionBar(c));
            }
        }
    }

    public List<PlayerQuestData> top(int n) { List<PlayerQuestData> r = ranking; return new ArrayList<>(r.subList(0, Math.min(n, r.size()))); }
    public List<PlayerQuestData> ranking() { return ranking; }

    /** 1-based, 0 = no points yet */
    public int rankOf(UUID u) {
        List<PlayerQuestData> r = ranking;
        for (int i = 0; i < r.size(); i++) if (r.get(i).uuid.equals(u)) return i + 1;
        return 0;
    }

    public static String medal(int rank) { return rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : ""; }

    /** Header, top 5 and (for a player outside the top 5) their own line. */
    public List<Component> boardLines(UUID viewer) {
        List<Component> out = new ArrayList<>();
        out.add(raw("board-header"));
        List<PlayerQuestData> t = top(TOP);
        if (t.isEmpty()) out.add(raw("board-empty"));
        for (int i = 0; i < t.size(); i++) {
            PlayerQuestData d = t.get(i);
            out.add(raw(d.uuid.equals(viewer) ? "board-line-you" : "board-line", "medal", medal(i + 1), "rank", i + 1, "name", d.name, "points", d.points, "completed", d.completed));
        }
        if (viewer != null) {
            int r = rankOf(viewer);
            PlayerQuestData me = data.get(viewer);
            if (r > TOP && me != null) out.add(raw("board-you", "rank", r, "name", me.name, "points", me.points, "completed", me.completed));
        }
        return out;
    }

    // =====================================================================================================
    // rewards
    // =====================================================================================================
    public Map<Integer, List<ItemStack>> rewards() { return rewards; }

    public void setRewards(Map<Integer, List<ItemStack>> r) {
        Map<Integer, List<ItemStack>> copy = new HashMap<>();
        r.forEach((k, v) -> { List<ItemStack> l = new ArrayList<>(); for (ItemStack it : v) l.add(it.clone()); if (!l.isEmpty()) copy.put(k, l); });
        rewards = copy;
        store.saveRewards(copy);
    }

    public boolean hasRewards() { return !rewards.isEmpty(); }

    /** Gives the rewards of the final top 5. Guarded by a saved flag, so it can never run twice. */
    public void distribute() {
        if ("1".equals(store.meta("distributed", "0"))) return;
        store.setMeta("distributed", "1");
        List<PlayerQuestData> top = top(TOP);
        Bukkit.getServer().sendMessage(raw("final-header"));
        for (Component c : boardLines(null)) Bukkit.getServer().sendMessage(c);
        for (int i = 0; i < top.size(); i++) {
            if (i + 1 > pl.rewardGui.places()) break;                       // only the configured places (default: just the winner)
            List<ItemStack> items = rewards.get(i + 1);
            if (items == null || items.isEmpty()) continue;
            PlayerQuestData d = top.get(i);
            Player p = Bukkit.getPlayer(d.uuid);
            if (p == null) { for (ItemStack it : items) addPending(d.uuid, it.clone()); continue; }
            deliver(p, items, i + 1);
        }
    }

    private void addPending(UUID u, ItemStack it) {
        long id = store.addPending(u, it);
        pending.computeIfAbsent(u, k -> new CopyOnWriteArrayList<>()).add(new QuestStore.Pending(id, it));
    }

    private void deliver(Player p, List<ItemStack> items, int place) {
        pl.sched.entity(p, () -> {
            int stored = 0;
            for (ItemStack it : items) {
                for (ItemStack left : p.getInventory().addItem(it.clone()).values()) { addPending(p.getUniqueId(), left); stored++; }   // full inventory: kept safe for /quest claim
            }
            p.sendMessage(msg("reward-received", "place", place));
            if (stored > 0) p.sendMessage(msg("rewards-waiting", "count", stored));
        });
    }

    public int pendingCount(UUID u) { List<QuestStore.Pending> l = pending.get(u); return l == null ? 0 : l.size(); }

    public void claim(Player p) {
        List<QuestStore.Pending> l = pending.get(p.getUniqueId());
        if (l == null || l.isEmpty()) { p.sendMessage(msg("claim-none")); return; }
        pl.sched.entity(p, () -> {
            int given = 0;
            for (QuestStore.Pending pd : new ArrayList<>(l)) {
                if (!p.getInventory().addItem(pd.item().clone()).isEmpty()) break;       // inventory full: keep the rest
                l.remove(pd);
                if (pd.id() >= 0) store.deletePending(pd.id());
                given++;
            }
            p.sendMessage(l.isEmpty() ? msg("claim-done", "count", given) : msg("claim-partial", "count", given, "left", l.size()));
        });
    }

    // =====================================================================================================
    // messages
    // =====================================================================================================
    /** messages.yml quest.<key> with the quest prefix */
    public Component msg(String key, Object... kv) {
        return pl.text.render(pl.cfg.messages().getString("quest-prefix", "") + pl.cfg.messages().getString("quest." + key, key), Text.ph(kv));
    }

    /** messages.yml quest.<key> without prefix (action bars, boards) */
    public Component raw(String key, Object... kv) {
        return pl.text.render(pl.cfg.messages().getString("quest." + key, key), Text.ph(kv));
    }

    public Collection<PlayerQuestData> allData() { return data.values(); }
    public int participants() { int n = 0; for (PlayerQuestData d : data.values()) if (d.points > 0 || d.completed > 0) n++; return n; }
    public int totalCompleted() { int n = 0; for (PlayerQuestData d : data.values()) n += d.completed; return n; }
}
