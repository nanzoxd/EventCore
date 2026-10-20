package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Discord webhook notifications. One clean embed per happening: a short title, a code block with the facts
 * (diff / ini / yaml / css / fix blocks give each kind its own colour in Discord) and a live "<t:..:R>" timestamp.
 * Sent asynchronously with the JDK HTTP client, so it never blocks a server or region thread.
 */
public final class DiscordHook {
    private final EventCorePlugin pl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private boolean warned;

    public DiscordHook(EventCorePlugin pl) { this.pl = pl; }

    private ConfigurationSection c() { return pl.cfg.config().getConfigurationSection("discord"); }

    private boolean on(String key) {
        ConfigurationSection c = c();
        return c != null && c.getBoolean("enabled", false) && !c.getString("webhook-url", "").isBlank() && c.getBoolean("notify." + key, true);
    }

    private int color(String key, String def) {
        ConfigurationSection c = c();
        String hex = c == null ? def : c.getString("colors." + key, def);
        try { return Integer.parseInt(hex.replace("#", ""), 16); } catch (Exception e) { return 0x2F2F2F; }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append("\\t");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.toString();
    }

    private String imageJson(String key) {
        if (key == null) return "";
        ConfigurationSection c = c();
        StringBuilder b = new StringBuilder();
        String th = c.getString("thumbnails." + key, "");
        if (th.startsWith("http")) b.append("\"thumbnail\":{\"url\":\"").append(esc(th)).append("\"},");
        return b.toString();
    }

    private static String clean(String s) { return s.replace("```", "'''"); }

    private void post(boolean mention, String title, int color, String description) { post(mention, title, color, description, null); }

    /** imageKey: discord.thumbnails.<key> (small corner image, optional). */
    private void post(boolean mention, String title, int color, String description, String imageKey) {
        ConfigurationSection c = c();
        String ping = mention ? c.getString("mention", "") : "";
        StringBuilder j = new StringBuilder("{");
        j.append("\"username\":\"").append(esc(c.getString("username", "EventCore"))).append("\",");
        String av = c.getString("avatar-url", "");
        if (!av.isBlank()) j.append("\"avatar_url\":\"").append(esc(av)).append("\",");
        j.append("\"content\":\"").append(esc(ping)).append("\",");
        j.append("\"allowed_mentions\":{\"parse\":").append(ping.isBlank() ? "[]" : "[\"roles\",\"everyone\"]").append("},");
        j.append("\"embeds\":[{").append(title.isEmpty() ? "" : "\"title\":\"" + esc(title) + "\",").append("\"description\":\"").append(esc(description))
                .append("\",\"color\":").append(color).append(",\"timestamp\":\"").append(Instant.now()).append("\",")
                .append(imageJson(imageKey)).append("\"footer\":{\"text\":\"").append(esc(c.getString("server-name", "Minecraft Server"))).append(" • EventCore\"}}]}");
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.getString("webhook-url")))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(j.toString())).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((r, t) -> {
            if ((t != null || r.statusCode() >= 300) && !warned) {
                warned = true;
                pl.getLogger().warning("Discord webhook failed: " + (t != null ? t.getMessage() : "HTTP " + r.statusCode()));
            }
        });
    }

    /** Start-only webhooks: each key is sent once until reset(key) (event end / new run). */
    private final java.util.Map<String, Long> sent = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean once(String key) {
        long now = System.currentTimeMillis();
        Long prev = sent.putIfAbsent(key, now);
        if (prev == null) return true;
        if (now - prev > 15_000L && !key.startsWith("lootdrop")) { sent.put(key, now); return true; }
        return false;
    }
    public void reset(String key) { sent.remove(key); }

    private static String rel(long epochSec) { return "<t:" + epochSec + ":R>"; }

    // ---------- start webhooks only (unicode symbols, no emoji) ----------
    /** MiniEvents start. */
    public void eventStarted(String name, String description, int minutes) {
        if (!on("event-start") || !once("event:" + name)) return;
        long end = Instant.now().getEpochSecond() + minutes * 60L;
        post(true, "", color("start", "#2EFF2E"),
                "⌘ **MiniEvents** › " + clean(name) + "\n⛨ **Ends** " + rel(end)
                + (description == null || description.isBlank() ? "" : "\n\n❖ " + clean(description)), "event");
    }

    /** Lootdrop start: only the first falling crate of a run is announced. */
    public void crateFalling(String tier, String world, String xyz, int seconds) {
        if (!on("airdrop-falling") || !once("lootdrop")) return;
        long land = Instant.now().getEpochSecond() + seconds;
        post(true, "", color("airdrop", "#d8b04a"),
                "⌘ **Lootdrop Falling**\n⛨ **Complete** " + rel(land)
                + "\n\n❖ " + clean(tier) + " crate at " + clean(world) + " " + xyz + "\n❖ Punch the Crate until it's open", "lootdrop");
    }

    /** Ritual start. */
    public void ritualStarted(String item, String player, String world, String xyz, int minutes) {
        if (!on("ritual-start") || !once("ritual:" + item + ":" + player)) return;
        long end = Instant.now().getEpochSecond() + minutes * 60L;
        post(true, "", color("ritual", "#ffd7ca"),
                "⌘ **Ritual Started** › " + clean(item) + "\n⛨ **Ends** " + rel(end)
                + "\n\n❖ " + clean(world) + " " + xyz, "ritual");
    }

    /** Altar ritual: sent once, when all three coordinates (X, Y, Z) have been revealed. */
    public void altarCoordsRevealed(String item, String player, String world, String xyz, int secondsLeft) {
        if (!on("ritual-start") || !once("altar:" + item + ":" + player)) return;
        long end = Instant.now().getEpochSecond() + secondsLeft;
        post(true, "", color("ritual", "#ffd7ca"),
                "⌘ **Altar Ritual** › " + clean(item) + "\n⛨ **Ends** " + rel(end)
                + "\n\n❖ All coordinates revealed\n❖ " + clean(world) + " " + xyz, "altar");
    }

    // The rest never send a webhook: one webhook per start, nothing else.
    public void warning(String name, String in) { }
    public void crateLanded(String tier, String world, String xyz, int hp) { }
    public void crateOpened(String tier, String player, String xyz) { }
    public void ritualComplete(String item) { sent.keySet().removeIf(k -> k.startsWith("ritual:") || k.startsWith("altar:")); }
    public void ritualStopped(String item) { sent.keySet().removeIf(k -> k.startsWith("ritual:")); }
}
