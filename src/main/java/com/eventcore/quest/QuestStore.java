package com.eventcore.quest;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** SQLite storage (the driver ships with Paper). One connection, every call is guarded by a lock, bulk writes run on one worker thread. */
public final class QuestStore {
    public record Pending(long id, ItemStack item) { }

    private final Plugin pl;
    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "EventCore-Quest-DB"); t.setDaemon(true); return t; });
    private Connection c;

    public QuestStore(Plugin pl) { this.pl = pl; }

    public void open(File file) {
        synchronized (lock) {
            try {
                Class.forName("org.sqlite.JDBC");
                c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("CREATE TABLE IF NOT EXISTS player(uuid TEXT PRIMARY KEY, name TEXT, points INTEGER, completed INTEGER, last_gain INTEGER, display TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS progress(uuid TEXT, quest TEXT, amount INTEGER, state INTEGER, PRIMARY KEY(uuid, quest))");
                    s.execute("CREATE TABLE IF NOT EXISTS reward(pos INTEGER, slot INTEGER, item TEXT, PRIMARY KEY(pos, slot))");
                    s.execute("CREATE TABLE IF NOT EXISTS pending(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, item TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)");
                }
            } catch (Exception e) { pl.getLogger().warning("Quest database could not be opened: " + e); c = null; }
        }
    }

    public boolean ok() { return c != null; }

    // ---------- items ----------
    public static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
    public static ItemStack decode(String s) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }

    // ---------- players ----------
    public Map<UUID, PlayerQuestData> loadPlayers() {
        Map<UUID, PlayerQuestData> out = new HashMap<>();
        synchronized (lock) {
            if (c == null) return out;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT uuid,name,points,completed,last_gain,display FROM player")) {
                while (r.next()) {
                    PlayerQuestData d = new PlayerQuestData(UUID.fromString(r.getString(1)), r.getString(2));
                    d.points = r.getInt(3); d.completed = r.getInt(4); d.lastGain = r.getLong(5);
                    String disp = r.getString(6); if (disp != null) d.display = disp;
                    out.put(d.uuid, d);
                }
            } catch (Exception e) { pl.getLogger().warning("Quest load players: " + e); }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT uuid,quest,amount,state FROM progress")) {
                while (r.next()) {
                    PlayerQuestData d = out.get(UUID.fromString(r.getString(1)));
                    if (d == null) continue;
                    d.progress.put(r.getString(2), r.getInt(3));
                    d.state.put(r.getString(2), r.getInt(4));
                }
            } catch (Exception e) { pl.getLogger().warning("Quest load progress: " + e); }
        }
        return out;
    }

    /** Snapshot now, write on the worker thread. */
    public void savePlayer(PlayerQuestData d) {
        final String uuid = d.uuid.toString(), name = d.name, display = d.display;
        final int points = d.points, completed = d.completed;
        final long gain = d.lastGain;
        final Map<String, Integer> prog = new HashMap<>(d.progress), st = new HashMap<>(d.state);
        d.dirty = false;
        try { worker.execute(() -> writePlayer(uuid, name, points, completed, gain, display, prog, st)); } catch (Exception ignored) { writePlayer(uuid, name, points, completed, gain, display, prog, st); }
    }

    private void writePlayer(String uuid, String name, int points, int completed, long gain, String display, Map<String, Integer> prog, Map<String, Integer> st) {
        synchronized (lock) {
            if (c == null) return;
            try {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO player(uuid,name,points,completed,last_gain,display) VALUES(?,?,?,?,?,?)")) {
                    p.setString(1, uuid); p.setString(2, name); p.setInt(3, points); p.setInt(4, completed); p.setLong(5, gain); p.setString(6, display);
                    p.executeUpdate();
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM progress WHERE uuid=?")) { del.setString(1, uuid); del.executeUpdate(); }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO progress(uuid,quest,amount,state) VALUES(?,?,?,?)")) {
                    for (Map.Entry<String, Integer> e : st.entrySet()) {
                        p.setString(1, uuid); p.setString(2, e.getKey()); p.setInt(3, prog.getOrDefault(e.getKey(), 0)); p.setInt(4, e.getValue());
                        p.addBatch();
                    }
                    for (Map.Entry<String, Integer> e : prog.entrySet()) {
                        if (st.containsKey(e.getKey())) continue;
                        p.setString(1, uuid); p.setString(2, e.getKey()); p.setInt(3, e.getValue()); p.setInt(4, 0);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) { }
                pl.getLogger().warning("Quest save player: " + e);
            } finally { try { c.setAutoCommit(true); } catch (Exception ignored) { } }
        }
    }

    public void clearPlayers() {
        synchronized (lock) {
            if (c == null) return;
            try (Statement s = c.createStatement()) { s.execute("DELETE FROM player"); s.execute("DELETE FROM progress"); } catch (Exception e) { pl.getLogger().warning("Quest clear: " + e); }
        }
    }

    // ---------- rewards ----------
    public Map<Integer, List<ItemStack>> loadRewards() {
        Map<Integer, List<ItemStack>> out = new HashMap<>();
        synchronized (lock) {
            if (c == null) return out;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT pos,item FROM reward ORDER BY pos,slot")) {
                while (r.next()) out.computeIfAbsent(r.getInt(1), k -> new ArrayList<>()).add(decode(r.getString(2)));
            } catch (Exception e) { pl.getLogger().warning("Quest load rewards: " + e); }
        }
        return out;
    }

    public void saveRewards(Map<Integer, List<ItemStack>> rewards) {
        synchronized (lock) {
            if (c == null) return;
            try {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) { s.execute("DELETE FROM reward"); }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO reward(pos,slot,item) VALUES(?,?,?)")) {
                    for (Map.Entry<Integer, List<ItemStack>> e : rewards.entrySet()) {
                        int slot = 0;
                        for (ItemStack it : e.getValue()) { p.setInt(1, e.getKey()); p.setInt(2, slot++); p.setString(3, encode(it)); p.addBatch(); }
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) { }
                pl.getLogger().warning("Quest save rewards: " + e);
            } finally { try { c.setAutoCommit(true); } catch (Exception ignored) { } }
        }
    }

    // ---------- pending rewards (full inventory / offline) ----------
    public Map<UUID, List<Pending>> loadPending() {
        Map<UUID, List<Pending>> out = new HashMap<>();
        synchronized (lock) {
            if (c == null) return out;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id,uuid,item FROM pending ORDER BY id")) {
                while (r.next()) out.computeIfAbsent(UUID.fromString(r.getString(2)), k -> new ArrayList<>()).add(new Pending(r.getLong(1), decode(r.getString(3))));
            } catch (Exception e) { pl.getLogger().warning("Quest load pending: " + e); }
        }
        return out;
    }

    /** Synchronous on purpose: a reward must be on disk before the caller forgets it. Returns the row id, or -1. */
    public long addPending(UUID u, ItemStack it) {
        synchronized (lock) {
            if (c == null) return -1;
            try (PreparedStatement p = c.prepareStatement("INSERT INTO pending(uuid,item) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, u.toString()); p.setString(2, encode(it)); p.executeUpdate();
                try (ResultSet r = p.getGeneratedKeys()) { return r.next() ? r.getLong(1) : -1; }
            } catch (Exception e) { pl.getLogger().warning("Quest add pending: " + e); return -1; }
        }
    }

    public void deletePending(long id) {
        synchronized (lock) {
            if (c == null) return;
            try (PreparedStatement p = c.prepareStatement("DELETE FROM pending WHERE id=?")) { p.setLong(1, id); p.executeUpdate(); } catch (Exception e) { pl.getLogger().warning("Quest delete pending: " + e); }
        }
    }

    // ---------- meta ----------
    public String meta(String k, String def) {
        synchronized (lock) {
            if (c == null) return def;
            try (PreparedStatement p = c.prepareStatement("SELECT v FROM meta WHERE k=?")) {
                p.setString(1, k);
                try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : def; }
            } catch (Exception e) { return def; }
        }
    }

    public void setMeta(String k, String v) {
        synchronized (lock) {
            if (c == null) return;
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)")) { p.setString(1, k); p.setString(2, v); p.executeUpdate(); } catch (Exception e) { pl.getLogger().warning("Quest meta: " + e); }
        }
    }

    public void close() {
        worker.shutdown();
        try { worker.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        synchronized (lock) { try { if (c != null) c.close(); } catch (Exception ignored) { } c = null; }
    }
}
