package tfmc.justin.activity.managers;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.RewardEntry;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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
            set(config, "rerollMaxPoints", 1);

            return manager;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void guarantee(ActivityManager manager, String... ids) {
        try {
            set(manager.getConfiguration(), "guaranteedActivities", List.of(ids));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

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

    public static void limits(ActivityManager manager, int barMax, int dailyMax) {
        try {
            set(manager.getConfiguration(), "barMax", barMax);
            set(manager.getConfiguration(), "dailyMax", dailyMax);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void rerollsPerDay(ActivityManager manager, int perDay) {
        try {
            set(manager.getConfiguration(), "rerollsPerDay", perDay);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void rerollMaxPoints(ActivityManager manager, int maxPoints) {
        try {
            set(manager.getConfiguration(), "rerollMaxPoints", maxPoints);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void storeLoaded(ActivityManager manager) {
        try {
            set(manager.getStore(), "loaded", true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void storeFile(ActivityManager manager, File file) {
        try {
            set(manager.getStore(), "file", file);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void pool(ActivityManager manager, String name, List<RewardEntry> entries) {
        try {
            ActivityConfiguration config = manager.getConfiguration();
            Field field = ActivityConfiguration.class.getDeclaredField("rewardPools");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, List<RewardEntry>> current = (Map<String, List<RewardEntry>>) field.get(config);
            Map<String, List<RewardEntry>> pools = new LinkedHashMap<>(current);
            pools.put(name, entries);
            field.set(config, Map.copyOf(pools));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void dailyRewards(ActivityManager manager, Map<String, RewardEntry> groups) {
        try {
            set(manager.getConfiguration(), "dailyRewards", groups);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Player player(UUID uuid, Set<String> permissions, List<String> chat) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "hasPermission", "isPermissionSet" -> permissions.contains(String.valueOf(args[0]));
            case "sendMessage" -> chat.add(String.valueOf(args[0]));
            case "getLocation", "playSound", "openInventory" -> null;
            case "toString" -> "stub-player";
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected call to Player#" + method.getName());
        };
        return (Player) Proxy.newProxyInstance(
            TestManagers.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    public static void messages(ActivityManager manager) {
        try {
            set(manager.getConfiguration().messages(), "messages",
                YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml")));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void bukkit() {
        Server installed = Bukkit.getServer();
        if (installed != null) {
            if (!STUB_NAME.equals(installed.toString())) {
                throw new IllegalStateException(
                    "Bukkit.server is already set to " + installed + "; this JVM cannot hold two stubs");
            }
            return;
        }
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getPlayer" -> args[0] instanceof UUID uuid ? ONLINE.get(uuid) : null;
            case "getLogger" -> Logger.getLogger(SERVER_LOGGER_NAME);
            case "getName", "getVersion", "getBukkitVersion" -> "TestManagers";
            case "toString" -> STUB_NAME;
            case "hashCode" -> STUB_NAME.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected call to Server#" + method.getName());
        };
        Server server = (Server) Proxy.newProxyInstance(
            TestManagers.class.getClassLoader(), new Class<?>[] {Server.class}, handler);
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Map<UUID, Player> ONLINE = new ConcurrentHashMap<>();

    public static void online(Player player) {
        ONLINE.put(player.getUniqueId(), player);
    }

    public static void offline(UUID uuid) {
        ONLINE.remove(uuid);
    }

    public static Logger logger() {
        return Logger.getLogger(LOGGER_NAME);
    }

    private static final String LOGGER_NAME = "TestManagers";

    private static final String SERVER_LOGGER_NAME = "TestManagers-Server";

    private static final String STUB_NAME = "stub-server";

    private static JavaPlugin stubPlugin() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

            Field logger = JavaPlugin.class.getDeclaredField("logger");
            logger.setAccessible(true);
            logger.set(plugin, Logger.getLogger(LOGGER_NAME));

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
