package com.eventcore.event;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.util.Text;
import com.eventcore.util.Util;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat Question: a question is sent in chat (chat only, no bossbar, no action bar).
 * The event ends as soon as somebody answers correctly; the only title is the winner title.
 * The first player to type a correct answer wins half a stack or a full stack of golden apples.
 * Questions and answers live in events.yml (events.chat_question.settings.questions).
 */
public final class ChatQuestionEvent extends GameEvent {

    private record Q(String question, List<String> answers) {
        String shown() { return answers.get(0); }
    }

    private static final class Live {
        final Q q; final int number; final long at;
        Live(Q q, int number, long at) { this.q = q; this.number = number; this.at = at; }
    }

    private final AtomicReference<Live> live = new AtomicReference<>();
    private final List<Integer> bag = new ArrayList<>();       // shuffled order: every question comes once before any repeats
    private volatile int wait, number;

    public ChatQuestionEvent(EventCorePlugin pl) { super(pl, "chat_question"); }

    // ---------------------------------------------------------------------------------------------
    // questions
    // ---------------------------------------------------------------------------------------------
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ").replaceAll("[.!?,]+$", "");
    }

    private List<Q> questions() {
        List<Q> out = new ArrayList<>();
        for (Map<?, ?> m : settings().getMapList("questions")) {
            Object q = m.get("q"), a = m.get("a");
            if (q == null || a == null) continue;
            List<String> answers = new ArrayList<>();
            for (String part : String.valueOf(a).split("\\|")) if (!norm(part).isEmpty()) answers.add(part.trim());
            if (!answers.isEmpty()) out.add(new Q(String.valueOf(q), answers));
        }
        return out;
    }

    private synchronized Q pick(List<Q> all) {
        bag.removeIf(i -> i >= all.size());
        if (bag.isEmpty()) {
            for (int i = 0; i < all.size(); i++) bag.add(i);
            Collections.shuffle(bag, ThreadLocalRandom.current());
        }
        return all.get(bag.remove(0));
    }

    // ---------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------
    @Override
    public void onStart(ActiveEvent ae) {
        live.set(null);
        number = 0;
        wait = Math.max(1, settings().getInt("first-question-after-seconds", 5));
    }

    @Override
    public void onStop(ActiveEvent ae) { live.set(null); }

    @Override
    public void onSecond(ActiveEvent ae, int secondsLeft) {
        ConfigurationSection s = settings();
        Live l = live.get();
        if (l != null) {                                                   // a question is open: wait for an answer or let it expire
            long timeout = Math.max(5, s.getInt("answer-timeout-seconds", 30)) * 1000L;
            if (System.currentTimeMillis() - l.at >= timeout && live.compareAndSet(l, null)) {
                wait = Math.max(1, s.getInt("question-interval-seconds", 45));
                pl.fx.broadcast(sec(), "messages.expired", Text.ph("answer", l.q.shown(), "number", l.number), prefix());
            }
            return;
        }
        if (--wait > 0 || secondsLeft < 8) return;                         // no new question in the last seconds
        List<Q> all = questions();
        if (all.isEmpty()) return;
        Q q = pick(all);
        Live next = new Live(q, ++number, System.currentTimeMillis());
        live.set(next);
        pl.fx.broadcast(sec(), "messages.question",
                Text.ph("number", next.number, "question", q.question(), "seconds", Math.max(5, s.getInt("answer-timeout-seconds", 30))), prefix());
    }

    private String prefix() { return pl.cfg.messages().getString("prefix", ""); }

    // ---------------------------------------------------------------------------------------------
    // answers
    // ---------------------------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Live l = live.get();
        if (l == null || !active()) return;
        String said = norm(Text.plain(e.message()));
        if (said.isEmpty()) return;
        boolean right = false;
        for (String a : l.q.answers()) if (norm(a).equals(said)) { right = true; break; }
        if (!right) return;
        if (!live.compareAndSet(l, null)) return;                          // somebody else was faster
        if (settings().getBoolean("hide-correct-answer", true)) e.setCancelled(true);
        Player p = e.getPlayer();
        pl.sched.global(() -> win(p, l));
    }

    private void win(Player p, Live l) {
        ConfigurationSection s = settings();
        wait = Math.max(1, s.getInt("question-interval-seconds", 45));
        Material mat = Material.matchMaterial(s.getString("reward.item", "GOLDEN_APPLE"));
        if (mat == null || !mat.isItem()) mat = Material.GOLDEN_APPLE;
        List<Integer> amounts = s.getIntegerList("reward.amounts");
        if (amounts.isEmpty()) amounts = List.of(32, 64);
        int amount = Math.max(1, Math.min(mat.getMaxStackSize(), amounts.get(ThreadLocalRandom.current().nextInt(amounts.size()))));
        ItemStack reward = new ItemStack(mat, amount);
        if (p.isOnline()) pl.sched.entity(p, () -> Util.give(p, reward));
        java.util.Map<String, Object> ph = Text.ph("player", p.getName(), "amount", amount, "item", Util.pretty(mat), "answer", l.q.shown(),
                "number", l.number, "event", pl.text.sc(displayName()), "gradient_raw", gradientRaw());
        pl.fx.title(org.bukkit.Bukkit.getServer(), sec().getConfigurationSection("titles.winner"), ph);   // the only title: event name + who won
        pl.fx.broadcast(sec(), "messages.winner", ph, prefix());
        if (settings().getBoolean("end-on-answer", true)) pl.events.stop(id(), true);   // first correct answer ends the whole event
    }
}
