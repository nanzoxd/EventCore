package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Player-head components for the action bar (Adventure object components, Paper 1.21.9+ with a 1.21.9+ client).
 *
 * Skin source, in this order:
 *   1. SkinsRestorer API: the skin the player really wears (custom /skin or the premium skin looked up by name),
 *      and the premium UUID from SkinsRestorer's Mojang API. This is what makes heads right on offline-mode servers,
 *      where the player's own UUID would only give a Steve head.
 *   2. The "textures" property of the live Paper profile (SkinsRestorer writes the applied skin there as well).
 *
 * SkinsRestorer and the head API are reached by reflection, so EventCore compiles without either and keeps
 * working when SkinsRestorer is missing or the server is older (get() then returns null and callers show a glyph).
 * Lookups run off-thread and are cached; an entry is refreshed after skin.refresh-minutes.
 */
public final class SkinHeads {
    private record Skin(UUID premium, String value, String signature, long at) { }
    private record Tex(String value, String signature) { }

    private final EventCorePlugin pl;
    private final Map<UUID, Skin> cache = new ConcurrentHashMap<>();
    private final Set<UUID> inflight = ConcurrentHashMap.newKeySet();
    private volatile boolean headApiChecked, headApiOk, warnedHead, warnedSr;

    // reflection handles for the Adventure head API
    private Method mPlayerHead, mName, mId, mProp, mPropFactory, mBuild, mObject;

    public SkinHeads(EventCorePlugin pl) { this.pl = pl; }

    private long refreshMs() { return Math.max(1, pl.cfg.events().getInt("events.random_target.settings.skin-refresh-minutes", 10)) * 60_000L; }

    // ---------- lookup ----------
    /** Starts an async lookup unless a fresh entry exists. Call from the player's own thread (reads the live profile). */
    public void prefetch(Player p) {
        UUID id = p.getUniqueId();
        Skin old = cache.get(id);
        if (old != null && System.currentTimeMillis() - old.at() < refreshMs()) return;
        if (!inflight.add(id)) return;
        String name = p.getName();
        Tex live = liveTextures(p);
        try {
            pl.sched.async(() -> {
                try { cache.put(id, lookup(id, name, live)); }
                catch (Throwable t) { pl.getLogger().fine("Skin lookup failed for " + name + ": " + t); }
                finally { inflight.remove(id); }
            });
        } catch (Throwable t) { inflight.remove(id); }
    }

    private static Tex liveTextures(Player p) {
        try {
            for (com.destroystokyo.paper.profile.ProfileProperty pr : p.getPlayerProfile().getProperties())
                if ("textures".equals(pr.getName())) return new Tex(pr.getValue(), pr.getSignature());
        } catch (Throwable ignored) { }
        return null;
    }

    private Skin lookup(UUID id, String name, Tex live) {
        String value = null, sig = null;
        UUID premium = null;
        Object api = skinsRestorer();
        if (api != null) {
            try {
                Class<?> sr = Class.forName("net.skinsrestorer.api.SkinsRestorer");
                Object players = sr.getMethod("getPlayerStorage").invoke(api);
                Class<?> ps = Class.forName("net.skinsrestorer.api.storage.PlayerStorage");
                Optional<?> prop = (Optional<?>) ps.getMethod("getSkinForPlayer", UUID.class, String.class).invoke(players, id, name);
                if (prop != null && prop.isPresent()) {
                    Class<?> sp = Class.forName("net.skinsrestorer.api.property.SkinProperty");
                    value = (String) sp.getMethod("getValue").invoke(prop.get());
                    sig = (String) sp.getMethod("getSignature").invoke(prop.get());
                }
            } catch (Throwable t) { pl.getLogger().fine("SkinsRestorer skin lookup failed for " + name + ": " + t); }
            try {
                Class<?> sr = Class.forName("net.skinsrestorer.api.SkinsRestorer");
                Object mojang = sr.getMethod("getMojangAPI").invoke(api);
                Class<?> mj = Class.forName("net.skinsrestorer.api.connections.MojangAPI");
                Optional<?> u = (Optional<?>) mj.getMethod("getUUID", String.class).invoke(mojang, name);
                if (u != null && u.isPresent() && u.get() instanceof UUID uu) premium = uu;
            } catch (Throwable t) { pl.getLogger().fine("SkinsRestorer premium UUID lookup failed for " + name + ": " + t); }
        }
        if (value == null && live != null) { value = live.value(); sig = live.signature(); }
        return new Skin(premium, value, sig, System.currentTimeMillis());
    }

    /** The SkinsRestorer API object, or null when the plugin is missing or not ready. */
    private Object skinsRestorer() {
        try {
            if (!pl.getServer().getPluginManager().isPluginEnabled("SkinsRestorer")) return null;
            return Class.forName("net.skinsrestorer.api.SkinsRestorerProvider").getMethod("get").invoke(null);
        } catch (Throwable t) {
            if (!warnedSr) { warnedSr = true; pl.getLogger().info("SkinsRestorer API not available (" + t.getClass().getSimpleName() + "); player heads use the live profile skin."); }
            return null;
        }
    }

    // ---------- component ----------
    /** A head component for this player, or null when unsupported or the skin is not loaded yet. */
    public Component head(UUID id, String name) {
        if (!headApi()) return null;
        Skin s = cache.get(id);
        if (s == null || s.value() == null) return null;
        try {
            Object b = mPlayerHead.invoke(null);
            mName.invoke(b, name);
            mId.invoke(b, s.premium() != null ? s.premium() : id);
            mProp.invoke(b, mPropFactory.invoke(null, "textures", s.value(), s.signature()));
            Object contents = mBuild.invoke(b);
            return (Component) mObject.invoke(null, contents);
        } catch (Throwable t) {
            if (!warnedHead) { warnedHead = true; pl.getLogger().warning("Could not build a player head component: " + t); }
            return null;
        }
    }

    private boolean headApi() {
        if (headApiChecked) return headApiOk;
        synchronized (this) {
            if (headApiChecked) return headApiOk;
            try {
                Class<?> oc = Class.forName("net.kyori.adventure.text.object.ObjectContents");
                Class<?> phc = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents");
                Class<?> bld = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents$Builder");
                Class<?> prop = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents$ProfileProperty");
                mPlayerHead = oc.getMethod("playerHead");
                mName = bld.getMethod("name", String.class);
                mId = bld.getMethod("id", UUID.class);
                mProp = bld.getMethod("profileProperty", prop);
                mBuild = bld.getMethod("build");
                mPropFactory = phc.getMethod("property", String.class, String.class, String.class);
                for (Method m : Component.class.getMethods())
                    if (m.getName().equals("object") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(phc)) { mObject = m; break; }
                headApiOk = mObject != null;
            } catch (Throwable t) {
                headApiOk = false;
            }
            if (!headApiOk) pl.getLogger().info("RandomTarget: this server has no player-head text support (needs Paper 1.21.9+); the action bar shows a symbol instead of the head.");
            headApiChecked = true;
            return headApiOk;
        }
    }
}
