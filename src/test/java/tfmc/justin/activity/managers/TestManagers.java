package tfmc.justin.activity.managers;

import org.bukkit.plugin.java.JavaPlugin;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.models.ActivityDef;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// ====================================
// A real ActivityManager (and with it a real ActivityConfiguration and
// PlayerStore) without a running server: load() is skipped and the handful of
// config fields the record path reads are set directly.
//
// JavaPlugin's constructor throws unless its classloader is a live
// PluginClassLoader, so one is allocated through ReflectionFactory the same
// way ActivityConfigurationStationTest does, then handed a real Logger.
//
// The store is left "not loaded" on purpose: nothing here writes a file. The
// paths that refuse to work unsaved (claim, reroll) opt back in with
// storeLoaded().
// ====================================
public final class TestManagers {

    private TestManagers() {
    }

    private static final class TestPlugin extends JavaPlugin {
    }

    public static ActivityManager manager(ActivityDef... defs) {
        try {
            Constructor<ActivityManager> constructor =
                ActivityManager.class.getDeclaredConstructor(JavaPlugin.class);
            constructor.setAccessible(true);
            ActivityManager manager = constructor.newInstance(stubPlugin());

            Map<String, ActivityDef> activities = new LinkedHashMap<>();
            for (ActivityDef def : defs) {
                activities.put(def.id(), def);
            }

            ActivityConfiguration config = manager.getConfiguration();
            set(config, "activities", activities);
            set(config, "resetDay", DayOfWeek.MONDAY);
            set(config, "resetHour", 0);
            set(config, "barMax", 50);
            set(config, "dailyMax", 10);
            set(config, "milestones", List.of(10, 20));

            return manager;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // Marks activities 'daily-guaranteed', the same way load() would - the
    // draw is lazy, so this counts as long as it happens before the first one
    public static void guarantee(ActivityManager manager, String... ids) {
        try {
            set(manager.getConfiguration(), "guaranteedActivities", List.of(ids));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // What a reload dropping an activity leaves behind: the loaded set minus
    // one id, installed the same way manager() installs it. Here rather than
    // in a test, so the field name lives in exactly one place.
    // ====================================
    public static void unload(ActivityManager manager, String id) {
        ActivityConfiguration config = manager.getConfiguration();
        Map<String, ActivityDef> remaining = new LinkedHashMap<>();
        for (ActivityDef def : config.activities()) {
            if (!def.id().equals(id)) {
                remaining.put(def.id(), def);
            }
        }
        try {
            set(config, "activities", remaining);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // A spent budget or a full bar without a server to award the points on:
    // headless, anything that actually credits a point reaches
    // Bukkit.getPlayer, so a cap is set up by lowering the limit instead.
    // ====================================
    public static void limits(ActivityManager manager, int barMax, int dailyMax) {
        try {
            set(manager.getConfiguration(), "barMax", barMax);
            set(manager.getConfiguration(), "dailyMax", dailyMax);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // The reroll budget load() would parse from reroll.per-day - set directly
    // the same way every other numeric config field here is
    public static void rerollsPerDay(ActivityManager manager, int perDay) {
        try {
            set(manager.getConfiguration(), "rerollsPerDay", perDay);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // Marks players.yml as having been read, which load() would do. Only
    // needed by the paths that refuse outright while nothing can be saved;
    // still nothing is ever written, since no test calls save().
    // ====================================
    public static void storeLoaded(ActivityManager manager) {
        try {
            set(manager.getStore(), "loaded", true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static JavaPlugin stubPlugin() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

            Field logger = JavaPlugin.class.getDeclaredField("logger");
            logger.setAccessible(true);
            logger.set(plugin, Logger.getLogger("TestManagers"));

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
