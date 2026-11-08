package com.eventcore;

import com.eventcore.airdrop.AirdropEvent;
import com.eventcore.airdrop.LootService;
import com.eventcore.altar.AltarManager;
import com.eventcore.bounty.BountyManager;
import com.eventcore.command.AltarCommand;
import com.eventcore.command.BountyCommand;
import com.eventcore.command.HuntCommand;
import com.eventcore.command.DisasterCommand;
import com.eventcore.command.EggHuntCommand;
import com.eventcore.command.EventCoreCommand;
import com.eventcore.command.RitualCommand;
import com.eventcore.event.*;
import com.eventcore.gui.AdminGui;
import com.eventcore.gui.BountyGui;
import com.eventcore.gui.ConfigGui;
import com.eventcore.gui.HuntBountyGui;
import com.eventcore.hunt.HuntManager;
import com.eventcore.gui.AltarGui;
import com.eventcore.gui.LootGui;
import com.eventcore.gui.RitualGui;
import com.eventcore.ritual.RitualManager;
import com.eventcore.ritual.RitualScheduler;
import com.eventcore.gui.RitualScheduleGui;
import com.eventcore.command.RitualScheduleCommand;
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
    public RitualScheduler ritualSchedule;
    public RitualScheduleGui ritualScheduleGui;
    public AdminGui adminGui;
    public LootGui lootGui;
    public AirdropEvent airdrop;
    public EggHuntEvent eggHunt;
    public NaturalDisasterEvent disaster;
    public AltarManager altar;
    public AltarGui altarGui;
    public HuntManager hunt;
    public BountyManager bounty;
    public BountyGui bountyGui;
    public ConfigGui configGui;
    public HuntBountyGui huntGui;
    public SkinHeads skins;

    @Override
    public void onEnable() {
        cfg = new Cfg(this);
        if (!cfg.config().getBoolean("timezone-ist-applied", false)) {      // one-time: EventCore times are IST now (change timezone later if you like)
            cfg.config().set("timezone", "Asia/Kolkata");
            cfg.config().set("timezone-ist-applied", true);
            getLogger().info("Timezone set to Asia/Kolkata (IST). Scheduled event times are now read as IST.");
        }
        cfg.save("config"); cfg.save("messages"); cfg.save("events"); cfg.save("rituals"); cfg.save("gui");
        migrate();
        text = new Text(this);
        sched = new Sched(this);
        skins = new SkinHeads(this);
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
        ritualSchedule = new RitualScheduler(this);
        ritualScheduleGui = new RitualScheduleGui(this);
        adminGui = new AdminGui(this);
        lootGui = new LootGui(this);
        scheduler = new EventScheduler(this);
        altar = new AltarManager(this);
        altarGui = new AltarGui(this);
        hunt = new HuntManager(this);
        bounty = new BountyManager(this);
        bountyGui = new BountyGui(this);
        configGui = new ConfigGui(this);
        huntGui = new HuntBountyGui(this);

        airdrop = new AirdropEvent(this);
        events.register(new VulnerableEvent(this));
        events.register(new ShieldlessEvent(this));
        events.register(new GoldenTreesEvent(this));
        events.register(new AnonymousEvent(this));
        events.register(new GlowEvent(this));
        events.register(new EloBuffEvent(this));
        events.register(new MinersHavenEvent(this));
        events.register(new HealthyFeederEvent(this));
        events.register(new LocatorBarEvent(this));
        events.register(new RandomTargetEvent(this));
        events.register(airdrop);
        eggHunt = new EggHuntEvent(this);
        disaster = new NaturalDisasterEvent(this);
        events.register(eggHunt);
        events.register(disaster);

        for (org.bukkit.event.Listener l : new org.bukkit.event.Listener[]{bars, chat, events, ritual, ritualGui, ritualScheduleGui, adminGui, lootGui, altar, altarGui, hunt, bounty, bountyGui})
            Bukkit.getPluginManager().registerEvents(l, this);

        EventCoreCommand ecCmd = new EventCoreCommand(this);
        getCommand("eventcore").setExecutor(ecCmd);
        getCommand("eventcore").setTabCompleter(ecCmd);
        AltarCommand altarCmd = new AltarCommand(this);
        getCommand("altar").setExecutor(altarCmd);
        getCommand("altar").setTabCompleter(altarCmd);
        getCommand("ritualcreator").setExecutor(new RitualCommand(this));
        RitualScheduleCommand ritualCmd = new RitualScheduleCommand(this);
        getCommand("ritual").setExecutor(ritualCmd);
        getCommand("ritual").setTabCompleter(ritualCmd);
        HuntCommand huntCmd = new HuntCommand(this);
        getCommand("playerhunt").setExecutor(huntCmd);
        getCommand("playerhunt").setTabCompleter(huntCmd);
        EggHuntCommand eggCmd = new EggHuntCommand(this);
        getCommand("egghunt").setExecutor(eggCmd);
        getCommand("egghunt").setTabCompleter(eggCmd);
        DisasterCommand disasterCmd = new DisasterCommand(this);
        getCommand("disaster").setExecutor(disasterCmd);
        getCommand("disaster").setTabCompleter(disasterCmd);
        BountyCommand bountyCmd = new BountyCommand(this);
        for (String n : new String[]{"bountyadd", "bountystop", "bountylist"}) {
            getCommand(n).setExecutor(bountyCmd);
            getCommand(n).setTabCompleter(bountyCmd);
        }

        events.startTicker();
        scheduler.start();
        ritual.restore();
        ritualSchedule.start();
        altar.load();
        sched.globalLater(eggHunt::recover, 60);   // removes eggs a crash left in the world
        bounty.start();
        getLogger().info("EventCore enabled.");
    }

    /** One-time config updates for servers that already have an events.yml (new defaults never overwrite existing values). */
    private void migrate() {
        org.bukkit.configuration.file.YamlConfiguration ev = cfg.events();
        if (!ev.getBoolean("migrations.player-hunt-v1", false)) {
            // Player Hunt and Locator Bar both own the locator bar gamerule: never run them together
            java.util.List<Object> groups = new java.util.ArrayList<>(cfg.config().getList("incompatible-events", java.util.List.of()));
            groups.add(java.util.List.of("locator_bar", "random_target"));
            cfg.config().set("incompatible-events", groups);
            cfg.save("config");
            ev.set("migrations.player-hunt-v1", true);
            cfg.save("events");
        }
        if (!ev.getBoolean("migrations.random-target-v1", false)) {
            // Player Hunt event was renamed to RandomTarget: carry over the admin's old settings, messages and incompatible-events entries
            org.bukkit.configuration.ConfigurationSection old = ev.getConfigurationSection("events.player_hunt");
            if (old != null) {
                ev.set("events.random_target", null);
                for (String k : old.getKeys(true)) if (!old.isConfigurationSection(k)) ev.set("events.random_target." + k, old.get(k));
                ev.set("events.random_target.display-name", "random target");
                ev.set("events.player_hunt", null);
            }
            org.bukkit.configuration.file.YamlConfiguration ms = cfg.messages();
            org.bukkit.configuration.ConfigurationSection oldMsg = ms.getConfigurationSection("player-hunt");
            if (oldMsg != null) {
                for (String k : oldMsg.getKeys(true)) if (!oldMsg.isConfigurationSection(k)) ms.set("random-target." + k, oldMsg.get(k));
                ms.set("player-hunt", null);
                cfg.save("messages");
            }
            java.util.List<Object> groups = new java.util.ArrayList<>();
            for (Object g : cfg.config().getList("incompatible-events", java.util.List.of())) {
                if (g instanceof java.util.List<?> l) {
                    java.util.List<Object> fixed = new java.util.ArrayList<>();
                    for (Object o : l) fixed.add("player_hunt".equals(o) ? "random_target" : o);
                    groups.add(fixed);
                } else groups.add(g);
            }
            cfg.config().set("incompatible-events", groups);
            cfg.save("config");
            ev.set("migrations.random-target-v1", true);
            cfg.save("events");
        }
        if (ev.getBoolean("migrations.shulker-colors-v1", false)) return;
        String base = "events.airdrop.settings.tiers.";
        String[][] colors = {{"iron", "YELLOW"}, {"gold", "ORANGE"}, {"diamond", "RED"}, {"netherite", "BLACK"}};
        for (String[] c : colors) if (ev.isConfigurationSection(base + c[0])) ev.set(base + c[0] + ".shulker-color", c[1]);
        ev.set("migrations.shulker-colors-v1", true);
        cfg.save("events");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.stop();
        if (events != null) events.shutdown();
        if (airdrop != null) airdrop.destroyAll();
        if (ritualSchedule != null) ritualSchedule.shutdown();
        if (ritual != null) ritual.shutdown();
        if (altar != null) altar.shutdown();
        if (hunt != null) hunt.shutdown();
        if (bounty != null) bounty.shutdown();
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
        if (file.equals("config")) hunt.rebuild();
        teams.refreshLater();
    }

    /** /eventcore reload and the GUI reload button. Active events and the ritual keep running. */
    public void reloadAll(CommandSender who) {
        cfg.reload();
        scheduler.rebuild();
        who.sendMessage(msg("command.reloaded"));
        altar.reload();
        ritual.warnReload(who);
    }
}
