package com.eventcore;

import com.eventcore.airdrop.AirdropEvent;
import com.eventcore.airdrop.LootService;
import com.eventcore.command.EventCoreCommand;
import com.eventcore.command.RitualCommand;
import com.eventcore.event.*;
import com.eventcore.gui.AdminGui;
import com.eventcore.gui.LootGui;
import com.eventcore.gui.RitualGui;
import com.eventcore.ritual.RitualManager;
import com.eventcore.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/** EventCore: scheduled server events, the Ritual Creator and airdrop crates. Native Java, Folia-safe scheduling. */
public final class EventCorePlugin extends JavaPlugin {
    public Cfg cfg;
    public Text text;
    public Sched sched;
    public Fx fx;
    public DiscordHook discord;
    public BossBars bars;
    public ParticleEngine particles;
    public TeamService teams;
    public ChatInput chat;
    public LootService loot;
    public EventManager events;
    public EventScheduler scheduler;
    public RitualManager ritual;
    public RitualGui ritualGui;
    public AdminGui adminGui;
    public LootGui lootGui;
    public AirdropEvent airdrop;

    @Override
    public void onEnable() {
        cfg = new Cfg(this);
        cfg.save("config"); cfg.save("messages"); cfg.save("events"); cfg.save("rituals"); cfg.save("gui");
        text = new Text(this);
        sched = new Sched(this);
        fx = new Fx(this);
        discord = new DiscordHook(this);
        bars = new BossBars(this);
        particles = new ParticleEngine(this);
        teams = new TeamService(this);
        chat = new ChatInput(this);
        loot = new LootService(this);
        events = new EventManager(this);
        ritual = new RitualManager(this);
        ritualGui = new RitualGui(this);
        adminGui = new AdminGui(this);
        lootGui = new LootGui(this);
        scheduler = new EventScheduler(this);

        airdrop = new AirdropEvent(this);
        events.register(new VulnerableEvent(this));
        events.register(new ShieldlessEvent(this));
        events.register(new GoldenTreesEvent(this));
        events.register(new AnonymousEvent(this));
        events.register(new GlowEvent(this));
        events.register(new EloBuffEvent(this));
        events.register(new LocatorBarEvent(this));
        events.register(airdrop);

        for (org.bukkit.event.Listener l : new org.bukkit.event.Listener[]{bars, chat, events, ritual, ritualGui, adminGui, lootGui})
            Bukkit.getPluginManager().registerEvents(l, this);

        getCommand("eventcore").setExecutor(new EventCoreCommand(this));
        getCommand("eventcore").setTabCompleter(new EventCoreCommand(this));
        getCommand("ritualcreator").setExecutor(new RitualCommand(this));

        events.startTicker();
        scheduler.start();
        ritual.restore();
        getLogger().info("EventCore enabled.");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.stop();
        if (events != null) events.shutdown();
        if (airdrop != null) airdrop.destroyAll();
        if (ritual != null) ritual.shutdown();
        if (teams != null) teams.shutdown();
        if (bars != null) bars.clear();
        // closing GUIs returns any item sitting in the ritual selection slot
        for (Player p : Bukkit.getOnlinePlayers()) {
            var h = p.getOpenInventory().getTopInventory().getHolder();
            if (h != null && h.getClass().getName().startsWith("com.eventcore.gui")) p.closeInventory();
        }
    }

    /** Message from messages.yml with prefix (ritual.* uses ritual-prefix). */
    public Component msg(String path, Object... kv) { return msgMap(path, Text.ph(kv)); }

    public Component msgMap(String path, Map<String, Object> ph) {
        Fx.Msg m = fx.msg(cfg.messages(), path);
        String prefix = cfg.messages().getString(path.startsWith("ritual.") ? "ritual-prefix" : "prefix", "");
        return text.render(prefix + m.tpl(), ph);
    }

    /** Called after any config edit in the GUI. Everything reads config live, so only derived state is rebuilt. */
    public void afterConfigChange(String file) {
        if (file.equals("events") || file.equals("config")) scheduler.rebuild();
        teams.refreshLater();
    }

    /** /eventcore reload and the GUI reload button. Active events and the ritual keep running. */
    public void reloadAll(CommandSender who) {
        cfg.reload();
        scheduler.rebuild();
        who.sendMessage(msg("command.reloaded"));
        ritual.warnReload(who);
    }
}
