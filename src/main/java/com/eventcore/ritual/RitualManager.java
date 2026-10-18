package com.eventcore.ritual;

import com.eventcore.EventCorePlugin;
import com.eventcore.util.*;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/** Native port of RitualCreator.sk: one active ritual, spinning glowing item, timer, bossbar, particle rings. */
public final class RitualManager implements Listener {

    /** The running ritual. */
    private final class Ritual {
        final ItemStack item;
        final Location loc;
        final String starter;
        final int total;
        volatile int remaining;
        String plainName;
        Component displayName;
        ItemDisplay display;
        Entity nameText, timerText;
        BossBar bar;
        ScheduledTask spin, particles, countdown;
        float yaw;
        final String palette = currentPalette();
        String lastTimer = "";
        long cycle;

        Ritual(ItemStack item, Location loc, int seconds, String starter) {
            this.item = item; this.loc = loc; this.starter = starter; this.total = seconds; this.remaining = seconds;
            ItemMetaName();
        }

        private void ItemMetaName() {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                displayName = meta.displayName();
                plainName = Text.plain(displayName);
            } else {
                plainName = Util.pretty(item.getType());
                displayName = pl.text.render(cfgMsg("ritual.unnamed-format", "<gold>{item}</gold>"), Text.ph("item", plainName));
            }
        }

        Map<String, Object> ph() {
            return Text.ph("item", plainName, "item_display", displayName, "player", starter,
                    "world", loc.getWorld().getName(), "x", Math.round(loc.getX()), "y", Math.round(loc.getY()),
                    "z", Math.round(loc.getZ()), "time", Util.mmss(remaining), "minutes", total / 60);
        }

        void spawn() {
            World w = loc.getWorld();
            ConfigurationSection e = rc().getConfigurationSection("entities");
            double dh = e.getDouble("display.height", 1.5);
            display = w.spawn(loc.clone().add(0, dh, 0), ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setGlowing(e.getBoolean("display.glowing", true));
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.FIXED);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(
                        e.getString("display.transform", "NONE").toUpperCase(Locale.ROOT)));
                float sc = (float) e.getDouble("display.scale", 1.0);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
                try { d.setTeleportDuration(1); } catch (Throwable ignored) { }
                d.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            });
            String gc = e.getString("display.glow-color", "");
            if (!gc.isBlank()) {
                try { display.setGlowColorOverride(Color.fromRGB(Integer.parseInt(gc.replace("#", ""), 16))); } catch (Exception ignored) { }
            }
            String mode = e.getString("text-mode", "ARMOR_STAND");
            nameText = spawnText(loc.clone().add(0, e.getDouble("item-name.height", 2.275), 0), nameComponent(), mode);
            timerText = spawnText(loc.clone().add(0, e.getDouble("timer.height", 2.0), 0), timerComponent(), mode);
        }

        Component nameComponent() {
            return pl.text.render(cfgMsg("ritual.name-text", "{item_display}"), ph());
        }

        Component timerComponent() { return pl.text.render(cfgMsg("ritual.timer-text", "<red>({time} left)</red>"), ph()); }

        Entity spawnText(Location l, Component c, String mode) {
            World w = l.getWorld();
            if (mode.equalsIgnoreCase("TEXT_DISPLAY")) {
                return w.spawn(l, TextDisplay.class, t -> {
                    t.text(c); t.setBillboard(Display.Billboard.CENTER); t.setPersistent(false);
                    t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    t.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
                });
            }
            return w.spawn(l, ArmorStand.class, a -> {
                a.setInvisible(true); a.setMarker(true); a.setSmall(true); a.setGravity(false);
                a.setCustomNameVisible(true); a.customName(c); a.setPersistent(false); a.setInvulnerable(true);
                a.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            });
        }

        void setText(Entity e, Component c) {
            if (e instanceof TextDisplay t) t.text(c); else if (e != null) e.customName(c);
        }

        void startTasks() {
            int spinIv = Math.max(1, rc().getInt("rotation.interval-ticks", 1));
            double speed = rc().getDouble("rotation.degrees-per-tick", 3.0) * spinIv;
            boolean rot = rc().getBoolean("rotation.enabled", true);
            spin = pl.sched.entityTimer(display, t -> {
                if (rot) { yaw = (float) ((yaw + speed) % 360); display.setRotation(yaw, 0f); }
                String ts = Util.mmss(remaining);
                if (!ts.equals(lastTimer)) { lastTimer = ts; setText(timerText, timerComponent()); }
            }, () -> { }, 1, spinIv);
            if (rc().getBoolean("particles.enabled", true)) {
                // the sigil from RitualCreator.sk: layers of the palette that was active when the ritual started
                List<ParticleEngine.Layer> layers = pl.particles.read(rc().getConfigurationSection("particles.palettes." + palette + ".layers"));
                Location center = loc.clone().add(0, rc().getDouble("particles.height", 2.325), 0);
                double view = Math.max(1.0, rc().getDouble("particles.view-distance", 32.0));
                particles = pl.sched.entityTimer(display, t -> {
                    // only draw while a player can see it
                    if (center.getWorld().getNearbyPlayers(center, view).isEmpty()) return;
                    pl.particles.playMaybeAsync(center, layers, cycle++);
                }, () -> { }, 1, Math.max(1, rc().getInt("particles.interval-ticks", 4)));
            }
            countdown = pl.sched.globalTimer(t -> {
                if (cur != this) { t.cancel(); return; }
                remaining--;
                updateBar(this);
                if (remaining > 0 && remaining % Math.max(1, rc().getInt("persistence.save-interval-seconds", 10)) == 0) save();
                if (remaining <= 0) complete();
            }, 20, 20);
        }

        void cancelTasks() { Sched.cancel(spin); Sched.cancel(particles); Sched.cancel(countdown); }

        void removeEntities() {
            for (Entity e : new Entity[]{display, nameText, timerText}) if (e != null && e.isValid()) e.remove();
        }
    }

    private final EventCorePlugin pl;
    private final NamespacedKey KEY;
    private volatile Ritual cur;
    private final File dataFile;

    public RitualManager(EventCorePlugin pl) {
        this.pl = pl;
        this.KEY = new NamespacedKey(pl, "ritual_entity");
        this.dataFile = new File(new File(pl.getDataFolder(), "data"), "ritual.yml");
    }

    private ConfigurationSection rc() { return pl.cfg.rituals(); }
    private String cfgMsg(String path, String def) { return pl.cfg.messages().getString(path, def); }
    private Component m(String path, Map<String, Object> ph) { return pl.msgMap(path, ph); }

    public boolean active() { return cur != null; }
    public int minutes() { return clampMin(rc().getInt("time.minutes", 15)); }
    private int clampMin(int v) { return Math.max(rc().getInt("time.min", 1), Math.min(rc().getInt("time.max", 60), v)); }
    public int minMinutes() { return rc().getInt("time.min", 1); }
    public int maxMinutes() { return rc().getInt("time.max", 60); }
    public void setMinutes(int v) {
        pl.cfg.rituals().set("time.minutes", clampMin(v));
        pl.sched.async(() -> pl.cfg.save("rituals"));
    }

    // ---------- particle palette (nether star in the GUI) ----------
    private List<String> paletteCycle() {
        List<String> c = new ArrayList<>(rc().getStringList("particles.cycle"));
        c.removeIf(n -> rc().getConfigurationSection("particles.palettes." + n) == null);
        if (c.isEmpty()) c.add("gold");
        return c;
    }

    public String currentPalette() {
        String p = rc().getString("particles.palette", "gold");
        return rc().getConfigurationSection("particles.palettes." + p) != null ? p : paletteCycle().get(0);
    }

    public String paletteLabel() { return rc().getString("particles.palettes." + currentPalette() + ".label", currentPalette()); }
    public String paletteColor() { return rc().getString("particles.palettes." + currentPalette() + ".color", "#d8b04a"); }

    /** Next palette in particles.cycle; saved to rituals.yml. */
    public String cyclePalette() {
        List<String> c = paletteCycle();
        String next = c.get((Math.max(0, c.indexOf(currentPalette())) + 1) % c.size());
        pl.cfg.rituals().set("particles.palette", next);
        pl.sched.async(() -> pl.cfg.save("rituals"));
        return next;
    }

    private void updateBar(Ritual r) {
        if (r.bar != null) r.bar.name(pl.text.render(cfgMsg("ritual.bossbar-title", "<bb>{item} Ritual | {time}</bb>"), r.ph()));
        if (r.bar != null && rc().getString("bossbar.progress-mode", "STATIC").equalsIgnoreCase("COUNTDOWN"))
            r.bar.progress(BossBars.clamp(r.remaining / (double) Math.max(1, r.total)));
    }

    /** Starts a ritual at the admin's location. Call from the admin's own thread. */
    public boolean start(Player admin, ItemStack item) {
        if (cur != null) return false;
        Ritual r = new Ritual(item.clone(), admin.getLocation().clone(), minutes() * 60, admin.getName());
        cur = r;
        r.spawn();
        begin(r, true, admin);
        save();
        return true;
    }

    private void begin(Ritual r, boolean announce, Player starter) {
        Map<String, Object> ph = r.ph();
        if (announce) {
            pl.fx.playAll(rc().getConfigurationSection("sounds.start"));
            if (starter != null) pl.fx.title(starter, pl.cfg.messages().getConfigurationSection("ritual.start-title"), ph);
            Bukkit.getServer().sendMessage(m("ritual.broadcast", ph));
            pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("ritual.announce-title"), ph);
            pl.discord.ritualStarted(r.plainName, r.starter, r.loc.getWorld().getName(), Math.round(r.loc.getX()) + " " + Math.round(r.loc.getY()) + " " + Math.round(r.loc.getZ()), r.total / 60);
        }
        ConfigurationSection bb = rc().getConfigurationSection("bossbar");
        if (bb != null && bb.getBoolean("enabled", true)) {
            ConfigurationSection tmp = new org.bukkit.configuration.MemoryConfiguration();
            tmp.set("text", cfgMsg("ritual.bossbar-title", "<g>{item} ritual | {world} [{x}, {y}, {z}]</g>"));
            tmp.set("color", rc().getString("particles.palettes." + r.palette + ".bossbar-color", bb.getString("color", "YELLOW")));
            tmp.set("style", bb.getString("style", "PROGRESS"));
            double prog = bb.getString("progress-mode", "STATIC").equalsIgnoreCase("COUNTDOWN")
                    ? r.remaining / (double) r.total : bb.getDouble("static-progress", 1.0);
            r.bar = pl.bars.create(tmp, ph, (float) prog);
            pl.bars.show(r.bar);
        }
        r.startTasks();
    }

    /** Normal completion (timer 0 or Submitter): drop the item, announce, clean up. */
    public void complete() {
        Ritual r = cur;
        if (r == null) return;
        cur = null;
        r.remaining = 0;
        r.cancelTasks();
        pl.bars.hide(r.bar);
        Location drop = r.display != null && r.display.isValid() ? r.display.getLocation() : r.loc.clone().add(0, 1.5, 0);
        pl.sched.region(drop, () -> {
            drop.getWorld().dropItem(drop, r.item);
            r.removeEntities();
        });
        pl.fx.playAll(rc().getConfigurationSection("sounds.complete"));
        pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("ritual.complete-title"), r.ph());
        pl.discord.ritualComplete(r.plainName);
        deleteSave();
    }

    /** Submitter button: set the timer to 0 and run the completion sequence. */
    public void finishNow() { complete(); }

    /** Cancel button (StopRitual). */
    public void stop(Player admin) {
        Ritual r = cur;
        if (r == null) return;
        cur = null;
        r.remaining = 0;
        r.cancelTasks();
        pl.bars.hide(r.bar);
        Location at = r.loc.clone();
        boolean back = rc().getBoolean("cancel.return-item-to-admin", false);
        pl.sched.region(at, r::removeEntities);
        if (back && admin != null) Util.give(admin, r.item);
        pl.fx.playAll(rc().getConfigurationSection("sounds.stop"));
        pl.fx.title(Bukkit.getServer(), pl.cfg.messages().getConfigurationSection("ritual.stopped-title"), r.ph());
        Bukkit.getServer().sendMessage(m("ritual.stopped-broadcast", r.ph()));
        pl.discord.ritualStopped(r.plainName);
        deleteSave();
    }

    // ---------- persistence ----------
    private void save() {
        Ritual r = cur;
        if (r == null || !rc().getBoolean("persistence.enabled", true)) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", r.loc.getWorld().getName());
        y.set("x", r.loc.getX()); y.set("y", r.loc.getY()); y.set("z", r.loc.getZ());
        y.set("remaining", r.remaining); y.set("total", r.total); y.set("starter", r.starter);
        y.set("item", Base64.getEncoder().encodeToString(r.item.serializeAsBytes()));
        pl.sched.async(() -> {
            try { dataFile.getParentFile().mkdirs(); y.save(dataFile); } catch (Exception ex) { pl.getLogger().warning("ritual save: " + ex); }
        });
    }

    private void saveBlocking(Ritual r) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", r.loc.getWorld().getName());
        y.set("x", r.loc.getX()); y.set("y", r.loc.getY()); y.set("z", r.loc.getZ());
        y.set("remaining", r.remaining); y.set("total", r.total); y.set("starter", r.starter);
        y.set("item", Base64.getEncoder().encodeToString(r.item.serializeAsBytes()));
        try { dataFile.getParentFile().mkdirs(); y.save(dataFile); } catch (Exception ex) { pl.getLogger().warning("ritual save: " + ex); }
    }

    private void deleteSave() { if (dataFile.exists()) dataFile.delete(); }

    /** On enable: restore (or cleanly cancel) the ritual that was running at shutdown. */
    public void restore() {
        if (!dataFile.exists() || !rc().getBoolean("persistence.enabled", true)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) { deleteSave(); return; }
        ItemStack item;
        try { item = ItemStack.deserializeBytes(Base64.getDecoder().decode(y.getString("item", ""))); }
        catch (Exception e) { deleteSave(); return; }
        Location loc = new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"));
        boolean restore = rc().getString("persistence.on-restart", "RESTORE").equalsIgnoreCase("RESTORE");
        int rem = y.getInt("remaining", 60), total = y.getInt("total", rem);
        String starter = y.getString("starter", "?");
        deleteSave();
        w.getChunkAtAsync(loc).thenAccept(ch -> pl.sched.region(loc, () -> {
            if (!restore) {
                if (rc().getBoolean("persistence.cancel-drops-item", true)) w.dropItem(loc.clone().add(0, 1, 0), item);
                return;
            }
            if (cur != null) return;
            Ritual r = new Ritual(item, loc, total, starter);
            r.remaining = rem;
            cur = r;
            r.spawn();
            begin(r, false, null);
            save();
        }));
    }

    /** Plugin disable: remove entities and bossbar, keep or drop the item per config. */
    public void shutdown() {
        Ritual r = cur;
        if (r == null) return;
        cur = null;
        r.cancelTasks();
        pl.bars.hide(r.bar);
        boolean persist = rc().getBoolean("persistence.enabled", true);
        if (persist) saveBlocking(r);
        try { r.removeEntities(); } catch (Throwable ignored) { }
        if (!persist) {
            try { r.loc.getWorld().dropItem(r.loc.clone().add(0, 1, 0), r.item); } catch (Throwable ignored) { }
        }
    }

    /** Warn about reloading while a ritual runs (like the original script). The ritual keeps running. */
    public void warnReload(org.bukkit.command.CommandSender who) {
        if (cur == null) return;
        Component c = pl.msg("ritual.reload-warning");
        who.sendMessage(c);
        Bukkit.broadcast(c, "eventcore.ritual");
    }

    // ---------- protection ----------
    private boolean isRitual(Entity e) { return e != null && e.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE); }

    @EventHandler public void onInteract(PlayerInteractEntityEvent e) { if (isRitual(e.getRightClicked())) e.setCancelled(true); }
    @EventHandler public void onInteractAt(PlayerInteractAtEntityEvent e) { if (isRitual(e.getRightClicked())) e.setCancelled(true); }
    @EventHandler public void onManip(PlayerArmorStandManipulateEvent e) { if (isRitual(e.getRightClicked())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (isRitual(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onHit(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) { if (isRitual(e.getAttacked())) e.setCancelled(true); }
}
