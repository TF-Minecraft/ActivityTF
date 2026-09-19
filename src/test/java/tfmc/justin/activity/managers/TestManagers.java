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
            // The shipped reroll.max-points, so a test that forgets to set its
            // own gate runs the configuration players actually get rather than
            // a permissive one no server has
            set(config, "rerollMaxPoints", 1);

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

    // The point gate load() would parse from reroll.max-points
    public static void rerollMaxPoints(ActivityManager manager, int maxPoints) {
        try {
            set(manager.getConfiguration(), "rerollMaxPoints", maxPoints);
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

    // ====================================
    // Points players.yml at a file of the test's choosing, for the paths that
    // save before they pay (the daily reward). Pair with storeLoaded().
    // ====================================
    public static void storeFile(ActivityManager manager, File file) {
        try {
            set(manager.getStore(), "file", file);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // A reward pool as load() would leave it: 'pool' is the default one,
    // 'pool_<name>' a named one. Merges, so a test can install several.
    // ====================================
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

    // daily-reward.groups as load() would leave it: group -> reward, in order
    public static void dailyRewards(ActivityManager manager, Map<String, RewardEntry> groups) {
        try {
            set(manager.getConfiguration(), "dailyRewards", groups);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // An online player for the GUI and command paths: his permissions (each
    // one set, as a LuckPerms group node is), his chat, and a no-op for the
    // sounds and the window a real client would get. Anything else throws.
    // ====================================
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

    // ====================================
    // The shipped messages.yml, installed straight off disk. Messages.reload()
    // wants a data folder and a running server, and every reply the admin
    // command sends goes through it - so the text tested here is the text
    // players actually get.
    // ====================================
    public static void messages(ActivityManager manager) {
        try {
            set(manager.getConfiguration().messages(), "messages",
                YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml")));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // A Bukkit server stub, so the record paths that actually credit a point
    // can be driven headless: they end in Bukkit.getPlayer(uuid), which NPEs
    // while Bukkit.server is null. The stub answers that with null - the
    // offline-player case - so the award lands and nothing is announced.
    // Bukkit's singleton can only be set once per JVM, so the contract is
    // first caller wins: whichever test calls this first installs the stub
    // every later test in the same fork gets. That is fine while this is the
    // only stub - and if another one is ever installed, or this is reached
    // inside a real server, the call throws rather than silently handing back
    // a server the caller did not ask for. Every method the stub does not
    // answer throws too, so a test that needs a richer Server fails loudly on
    // the call it makes rather than quietly getting a wrong answer.
    // ====================================
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
            // Its own logger, deliberately not the one audit lines land on:
            // anything logged through Bukkit.getLogger() must not show up in
            // a test's captured audit stream. Hyphen in the name prevents Java's
            // hierarchical logger inheritance (dot would make it a child of TestManagers)
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
            // Not Bukkit.setServer: it logs a version banner built from
            // ServerBuildInfo, which has no provider outside a real server and
            // throws NoSuchElementException - after the field is already set
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // A player the stub's getPlayer(UUID) answers with, so a test can see
    // what an award announces. Cleared by offline(); every other uuid is
    // still the offline-player case.
    // ====================================
    private static final Map<UUID, Player> ONLINE = new ConcurrentHashMap<>();

    public static void online(Player player) {
        ONLINE.put(player.getUniqueId(), player);
    }

    public static void offline(UUID uuid) {
        ONLINE.remove(uuid);
    }

    // The logger the stub plugin hands out, which is what an audit line lands
    // in - a test captures it by attaching a Handler here
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
