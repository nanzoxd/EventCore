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

    private static String clean(String s) { return s.replace("```", "'''"); }

    private void post(boolean mention, String title, int color, String description) {
        ConfigurationSection c = c();
        String ping = mention ? c.getString("mention", "") : "";
        StringBuilder j = new StringBuilder("{");
        j.append("\"username\":\"").append(esc(c.getString("username", "EventCore"))).append("\",");
        String av = c.getString("avatar-url", "");
        if (!av.isBlank()) j.append("\"avatar_url\":\"").append(esc(av)).append("\",");
        j.append("\"content\":\"").append(esc(ping)).append("\",");
        j.append("\"allowed_mentions\":{\"parse\":").append(ping.isBlank() ? "[]" : "[\"roles\",\"everyone\"]").append("},");
        j.append("\"embeds\":[{\"title\":\"").append(esc(title)).append("\",\"description\":\"").append(esc(description))
                .append("\",\"color\":").append(color).append(",\"timestamp\":\"").append(Instant.now()).append("\",")
                .append("\"footer\":{\"text\":\"").append(esc(c.getString("server-name", "Minecraft Server"))).append(" • EventCore\"}}]}");
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

    private static String rel(long epochSec) { return "<t:" + epochSec + ":R>"; }

    // ---------- events ----------
    public void eventStarted(String name, String description, int minutes) {
        if (!on("event-start")) return;
        long end = Instant.now().getEpochSecond() + minutes * 60L;
        post(true, "🟢 Mini event started: " + name, color("start", "#2EFF2E"),
                "> " + clean(description) + "\n```diff\n+ Event     " + clean(name) + "\n+ Duration  " + minutes + " minutes\n```\nEnds " + rel(end));
    }

    public void warning(String name, String in) {
        if (!on("schedule-warning")) return;
        post(false, "⏰ Starting soon: " + name, color("warn", "#FFFF5C"),
                "```ini\n[Event]  " + clean(name) + "\n[Starts] in " + in + "\n```");
    }

    // ---------- airdrop ----------
    public void crateFalling(String tier, String world, String xyz, int seconds) {
        if (!on("airdrop-falling")) return;
        long land = Instant.now().getEpochSecond() + seconds;
        post(true, "🪂 " + tier + " lootdrop falling", color("airdrop", "#d8b04a"),
                "```yaml\ntier: " + clean(tier) + "\nworld: " + clean(world) + "\ncoords: " + xyz + "\n```\nLands " + rel(land));
    }

    public void crateLanded(String tier, String world, String xyz, int hp) {
        if (!on("airdrop-landed")) return;
        post(true, "📦 " + tier + " lootdrop landed", color("airdrop", "#d8b04a"),
                "```yaml\ntier: " + clean(tier) + "\nworld: " + clean(world) + "\ncoords: " + xyz + "\nhealth: " + hp + "\n```\nPunch the crate until it opens.");
    }

    public void crateOpened(String tier, String player, String xyz) {
        if (!on("airdrop-opened")) return;
        post(false, "🎁 " + tier + " lootdrop opened", color("airdrop", "#d8b04a"),
                "```fix\n" + clean(player) + " opened the " + clean(tier) + " crate at " + xyz + "\n```");
    }

    // ---------- ritual ----------
    public void ritualStarted(String item, String player, String world, String xyz, int minutes) {
        if (!on("ritual-start")) return;
        long end = Instant.now().getEpochSecond() + minutes * 60L;
        post(true, "🔮 Ritual started: " + item, color("ritual", "#F47F6A"),
                "```css\n[Item]     " + clean(item) + "\n[Started]  " + clean(player) + "\n[Where]    " + clean(world) + " " + xyz + "\n[Time]     " + minutes + " minutes\n```\nCompletes " + rel(end));
    }

    public void ritualComplete(String item) {
        if (!on("ritual-complete")) return;
        post(false, "✅ Ritual complete: " + item, color("start", "#2EFF2E"),
                "```diff\n+ " + clean(item) + " was crafted. The item dropped.\n```");
    }

    public void ritualStopped(String item) {
        if (!on("ritual-stop")) return;
        post(false, "🛑 Ritual stopped: " + item, color("end", "#FF2E4A"),
                "```diff\n- The ritual for " + clean(item) + " was cancelled.\n```");
    }
}
