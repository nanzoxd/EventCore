package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.Sched;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.ConfigurationSection;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-clock scheduler. Per event (events.yml: events.&lt;id&gt;.schedule):
 *   mode: times     -> "19:00" or "SAT 20:00" entries, filtered by days: [ALL | MONDAY ...]
 *   mode: interval  -> every interval-minutes
 *   mode: off
 * Pre-start warnings come from events.yml scheduler.warnings (seconds). Timezone comes from config.yml.
 */
public final class EventScheduler {
    private final EventCorePlugin pl;
    private final Map<String, Instant> pending = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> warned = new ConcurrentHashMap<>();
    private ScheduledTask task;

    public EventScheduler(EventCorePlugin pl) { this.pl = pl; }

    public void start() { task = pl.sched.globalTimer(t -> tick(), 20, 20); }
    public void stop() { Sched.cancel(task); }
    /** Forget computed times; they are recalculated on the next tick (after a config change). */
    public void rebuild() { pending.clear(); warned.clear(); }

    public ZoneId zone() {
        try { return ZoneId.of(pl.cfg.config().getString("timezone", "Asia/Kolkata")); }
        catch (Exception e) { return ZoneId.of("Asia/Kolkata"); }
    }

    private void tick() {
        if (!pl.cfg.events().getBoolean("scheduler.enabled", true)) return;
        Instant now = Instant.now();
        List<Integer> warnings = new ArrayList<>(pl.cfg.events().getIntegerList("scheduler.warnings"));
        warnings.sort(Comparator.reverseOrder());
        for (GameEvent ev : pl.events.registry().values()) {
            ConfigurationSection sc = ev.sec().getConfigurationSection("schedule");
            String mode = sc == null ? "off" : sc.getString("mode", "off").toLowerCase(Locale.ROOT);
            if (sc == null || mode.equals("off") || !ev.sec().getBoolean("enabled", true)) { pending.remove(ev.id()); continue; }
            Instant next = pending.computeIfAbsent(ev.id(), k -> computeNext(sc, mode, now));
            if (next == null) continue;
            long left = Duration.between(now, next).getSeconds();
            Set<Integer> w = warned.computeIfAbsent(ev.id(), k -> ConcurrentHashMap.newKeySet());
            for (int sec : warnings) {
                if (!w.contains(sec) && left <= sec) {
                    w.add(sec);
                    if (left >= sec - 2 && left > 0) warn(ev, (int) left);
                }
            }
            if (!now.isBefore(next)) {
                Integer minutes = sc.contains("minutes") ? sc.getInt("minutes") : null;
                String err = pl.events.start(ev.id(), minutes, false);
                if (err != null) pl.getLogger().info("Scheduled start of " + ev.id() + " skipped: " + err);
                w.clear();
                pending.put(ev.id(), mode.equals("interval")
                        ? next.plusSeconds(Math.max(1, sc.getInt("interval-minutes", 180)) * 60L)
                        : orNull(computeNext(sc, mode, next.plusSeconds(1))));
                if (pending.get(ev.id()) == null) pending.remove(ev.id());
            }
        }
    }

    private static <T> T orNull(T t) { return t; }

    private void warn(GameEvent ev, int secondsLeft) {
        ConfigurationSection s = ev.sec();
        Map<String, Object> ph = Text.ph("event", pl.text.sc(ev.displayName()), "time", humanTime(secondsLeft),
                "color_raw", s.getString("color", "#ffffff"));
        pl.fx.broadcast(pl.cfg.messages(), "scheduler.warning", ph, pl.cfg.messages().getString("prefix", ""));
        pl.fx.playAll(pl.cfg.events().getConfigurationSection("scheduler.warning-sound"));
        pl.discord.warning(com.eventcore.util.Util.title(ev.displayName()), humanTime(secondsLeft));
    }

    private static String humanTime(long sec) {
        if (sec >= 3600) return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m";
        if (sec >= 60) return (sec / 60) + "m" + (sec % 60 > 0 ? " " + (sec % 60) + "s" : "");
        return sec + "s";
    }

    private Instant computeNext(ConfigurationSection sc, String mode, Instant from) {
        if (mode.equals("interval")) return from.plusSeconds(Math.max(1, sc.getInt("interval-minutes", 180)) * 60L);
        if (!mode.equals("times")) return null;
        ZoneId z = zone();
        ZonedDateTime base = from.atZone(z);
        Set<DayOfWeek> globalDays = parseDays(sc.getStringList("days"));
        Instant best = null;
        for (int off = 0; off <= 7; off++) {
            LocalDate date = base.toLocalDate().plusDays(off);
            for (String entry : sc.getStringList("times")) {
                String[] parts = entry.trim().split("\\s+");
                try {
                    LocalTime t = LocalTime.parse(parts[parts.length - 1]);
                    Set<DayOfWeek> days = parts.length > 1 ? parseDays(List.of(parts[0])) : globalDays;
                    if (!days.contains(date.getDayOfWeek())) continue;
                    Instant cand = date.atTime(t).atZone(z).toInstant();
                    if (cand.isAfter(from) && (best == null || cand.isBefore(best))) best = cand;
                } catch (Exception ignored) { }
            }
            if (best != null) break;
        }
        return best;
    }

    private static Set<DayOfWeek> parseDays(List<String> list) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (list.isEmpty()) return EnumSet.allOf(DayOfWeek.class);
        for (String s : list) {
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (u.equals("ALL") || u.equals("*")) return EnumSet.allOf(DayOfWeek.class);
            for (DayOfWeek d : DayOfWeek.values()) if (d.name().startsWith(u.length() >= 3 ? u.substring(0, 3) : u)) out.add(d);
        }
        return out;
    }

    /** One line per scheduled event for /eventcore schedule. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        Instant now = Instant.now();
        ZoneId z = zone();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm z", Locale.ENGLISH);
        for (GameEvent ev : pl.events.registry().values()) {
            ConfigurationSection sc = ev.sec().getConfigurationSection("schedule");
            String mode = sc == null ? "off" : sc.getString("mode", "off").toLowerCase(Locale.ROOT);
            if (sc == null || mode.equals("off")) continue;
            Instant next = pending.get(ev.id());
            if (next == null) next = computeNext(sc, mode, now);
            if (next == null) continue;
            lines.add(ev.id() + "|" + next.atZone(z).format(f) + "|" + humanTime(Math.max(0, Duration.between(now, next).getSeconds())));
        }
        return lines;
    }
}
