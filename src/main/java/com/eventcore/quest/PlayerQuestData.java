package com.eventcore.quest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Everything saved for one player (UUID based). Loaded once at startup, saved asynchronously. */
public final class PlayerQuestData {
    public static final int AVAILABLE = 0, ACTIVE = 1, DONE = 2, ABANDONED = 3;

    public final UUID uuid;
    public volatile String name;
    public volatile int points, completed;
    public volatile long lastGain;                       // tie-breaker: who reached the points first
    public volatile String display = "ACTIONBAR";        // ACTIONBAR | BOSSBAR | BOTH | OFF
    public final Map<String, Integer> progress = new ConcurrentHashMap<>();
    public final Map<String, Integer> state = new ConcurrentHashMap<>();
    public volatile boolean dirty;

    public PlayerQuestData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    public int state(String id) { return state.getOrDefault(id, AVAILABLE); }
    public int progress(String id) { return progress.getOrDefault(id, 0); }

    public int activeCount() {
        int n = 0;
        for (int s : state.values()) if (s == ACTIVE) n++;
        return n;
    }

    public void clear() {
        points = 0; completed = 0; lastGain = 0;
        progress.clear(); state.clear();
        dirty = true;
    }
}
