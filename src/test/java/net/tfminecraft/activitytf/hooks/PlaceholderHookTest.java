package net.tfminecraft.activitytf.hooks;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objenesis.ObjenesisStd;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.store.PlayerStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderHookTest {

    private static final ActivityDef CAPPED = new ActivityDef("capped", "Capped", Material.PAPER, null, 2, 3, 2);
    private static final ActivityDef UNLIMITED = new ActivityDef("unlimited", "Unlimited", Material.PAPER, null, 2, 1, 0);

    private final UUID uuid = UUID.randomUUID();
    private final OfflinePlayer player = (OfflinePlayer) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "toString" -> "placeholder-player";
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unexpected OfflinePlayer#" + method.getName());
        });

    private ActivityManager manager;
    private ActivityConfiguration.Keys keys;
    private PlaceholderHook hook;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        manager = TestManagers.manager(CAPPED, UNLIMITED);
        TestManagers.messages(manager);
        field(ActivityConfiguration.class, "barLength").set(manager.getConfiguration(), 10);
        keys = manager.getConfiguration().currentKeys();
        hook = new PlaceholderHook(manager);
    }

    @ParameterizedTest
    @CsvSource({
        "30000000, 30000000, 100",
        "2147483647, 2147483647, 100",
        "1073741824, 2147483647, 50",
        "2147483647, 30000000, 100",
        "0, 30000000, 0",
        "1, 3, 33"
    })
    void percentageHandlesTheFullPositiveIntegerRange(int points, int maximum, String expected)
        throws ReflectiveOperationException {
        TestManagers.limits(manager, maximum, 10);
        stored(points, 0, keys.week(), keys.day(), 0, Map.of());

        assertEquals(expected, hook.onRequest(player, "percent"));
    }

    @Test
    void metadataIdentifiesAPersistentActivityExpansion() {
        assertEquals("activity", hook.getIdentifier());
        assertEquals("Justin", hook.getAuthor());
        assertEquals("1.0", hook.getVersion());
        assertTrue(hook.persist());
    }

    @Test
    void nullPlayerProducesEmptyTextWithoutReadingTheManager() {
        assertEquals("", new PlaceholderHook(null).onRequest(null, "points"));
    }

    @Test
    void unknownPlayerReadsAsZeroWithoutCreatingStoredData() {
        assertEquals("0", hook.onRequest(player, "points"));
        assertEquals("0", hook.onRequest(player, "claimable"));
        assertEquals("0", hook.onRequest(player, "daily_points"));
        assertEquals("0", hook.onRequest(player, "percent"));
        assertEquals("\u00a7c✘", hook.onRequest(player, "done_capped"));
        assertNull(manager.getStore().peek(uuid));
    }

    @Test
    void currentValuesIncludeLimitsClaimableMilestonesAndColoredBar() throws ReflectiveOperationException {
        stored(20, 7, keys.week(), keys.day(), 10, Map.of());

        assertEquals("20", hook.onRequest(player, "PoInTs"));
        assertEquals("50", hook.onRequest(player, "max"));
        assertEquals("40", hook.onRequest(player, "percent"));
        assertEquals("1", hook.onRequest(player, "claimable"));
        assertEquals("7", hook.onRequest(player, "daily_points"));
        assertEquals("10", hook.onRequest(player, "daily_max"));
        String bar = hook.onRequest(player, "bar");
        assertEquals("[||||||||||]", org.bukkit.ChatColor.stripColor(bar));
        assertTrue(bar.contains("\u00a7"));
        assertFalse(bar.contains("#"));
    }

    @Test
    void lowerConfiguredLimitsClampDisplaysWithoutChangingStoredValues() throws ReflectiveOperationException {
        PlayerData data = stored(70, 15, keys.week(), keys.day(), 0, Map.of());

        assertEquals("50", hook.onRequest(player, "points"));
        assertEquals("100", hook.onRequest(player, "percent"));
        assertEquals("10", hook.onRequest(player, "daily_points"));
        assertEquals(70, data.points());
        assertEquals(15, data.dailyPoints());
        assertSame(data, manager.getStore().peek(uuid));
    }

    @Test
    void staleWeekDisplaysZeroWithoutRollingOrClearingTheStoredRecord() throws ReflectiveOperationException {
        PlayerData data = stored(20, 7, "2000-01-03", keys.day(), 0, Map.of("capped", 4));

        assertEquals("0", hook.onRequest(player, "points"));
        assertEquals("0", hook.onRequest(player, "claimable"));
        assertEquals("0", hook.onRequest(player, "daily_points"));
        assertEquals("\u00a7c✘", hook.onRequest(player, "done_capped"));
        assertEquals("2000-01-03", data.weekKey());
        assertEquals(20, data.points());
        assertEquals(7, data.dailyPoints());
        assertEquals(4, data.count("capped"));
    }

    @Test
    void staleDayKeepsWeeklyValuesButHidesYesterdayProgress() throws ReflectiveOperationException {
        PlayerData data = stored(20, 7, keys.week(), "2000-01-03", 0, Map.of("capped", 4));

        assertEquals("20", hook.onRequest(player, "points"));
        assertEquals("2", hook.onRequest(player, "claimable"));
        assertEquals("0", hook.onRequest(player, "daily_points"));
        assertEquals("\u00a7c✘", hook.onRequest(player, "done_capped"));
        assertEquals("2000-01-03", data.dayKey());
        assertEquals(7, data.dailyPoints());
        assertEquals(4, data.count("capped"));
    }

    @Test
    void cappedActivitiesAreDoneOnlyAfterAllDailyCompletions() throws ReflectiveOperationException {
        stored(3, 3, keys.week(), keys.day(), 0, Map.of("capped", 2));
        assertEquals("\u00a7c✘", hook.onRequest(player, "done_capped"));

        stored(6, 6, keys.week(), keys.day(), 0, Map.of("capped", 4));
        assertEquals("\u00a7a✔", hook.onRequest(player, "done_capped"));
    }

    @Test
    void uncappedActivitiesAreDoneAfterTheFirstEarnedPoint() throws ReflectiveOperationException {
        stored(0, 0, keys.week(), keys.day(), 0, Map.of("unlimited", 1));
        assertEquals("\u00a7c✘", hook.onRequest(player, "done_unlimited"));

        stored(1, 1, keys.week(), keys.day(), 0, Map.of("unlimited", 2));
        assertEquals("\u00a7a✔", hook.onRequest(player, "DONE_unlimited"));
    }

    @Test
    void unknownActivitiesReturnEmptyAndUnknownPlaceholdersRemainUnresolved() {
        assertEquals("", hook.onRequest(player, "done_missing"));
        assertNull(hook.onRequest(player, "unsupported"));
    }

    @Test
    void registeredProviderResolvesPlaceholderApiText(@TempDir File directory) throws ReflectiveOperationException {
        stored(20, 7, keys.week(), keys.day(), 10, Map.of("capped", 4));
        Server previousServer = Bukkit.getServer();
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {PluginManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("callEvent")) {
                    return null;
                }
                throw new UnsupportedOperationException("Unexpected PluginManager#" + method.getName());
            });
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPluginManager" -> pluginManager;
                case "getBukkitVersion" -> "1.21.10-R0.1-SNAPSHOT";
                default -> throw new UnsupportedOperationException("Unexpected Server#" + method.getName());
            });
        Field serverField = field(Bukkit.class, "server");
        serverField.set(null, server);
        try {
            Field instanceField = field(PlaceholderAPIPlugin.class, "instance");
            Object previousPlugin = instanceField.get(null);
            try {
                PlaceholderAPIPlugin plugin = new ObjenesisStd().newInstance(PlaceholderAPIPlugin.class);
                field(JavaPlugin.class, "dataFolder").set(plugin, directory);
                field(JavaPlugin.class, "logger").set(plugin, Logger.getAnonymousLogger());
                field(JavaPlugin.class, "newConfig").set(plugin, new YamlConfiguration());
                field(PlaceholderAPIPlugin.class, "config").set(plugin, new PlaceholderAPIConfig(plugin));
                LocalExpansionManager expansions = new LocalExpansionManager(plugin);
                field(PlaceholderAPIPlugin.class, "localExpansionManager").set(plugin, expansions);
                instanceField.set(null, plugin);

                assertTrue(hook.register());
                assertTrue(hook.isRegistered());
                assertSame(hook, expansions.getExpansion("activity"));
                assertEquals("20/50 (40%) 1 \u00a7a✔ %activity_unknown%", PlaceholderAPI.setPlaceholders(player,
                    "%activity_points%/%activity_max% (%activity_percent%%) %activity_claimable%"
                        + " %activity_done_capped% %activity_unknown%"));
                assertTrue(hook.unregister());
                assertFalse(hook.isRegistered());
            } finally {
                instanceField.set(null, previousPlugin);
            }
        } finally {
            serverField.set(null, previousServer);
        }
    }

    @SuppressWarnings("unchecked")
    private PlayerData stored(int points, int daily, String week, String day, int claimed, Map<String, Integer> counts)
        throws ReflectiveOperationException {
        PlayerData data = new PlayerData(points, daily, week, day, claimed, counts);
        Map<UUID, PlayerData> players = (Map<UUID, PlayerData>) field(PlayerStore.class, "players").get(manager.getStore());
        players.put(uuid, data);
        return data;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
