package net.tfminecraft.activitytf;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig;
import me.clip.placeholderapi.events.ExpansionRegisterEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.objenesis.ObjenesisStd;
import net.tfminecraft.activitytf.commands.ActivityCommand;
import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.hooks.PlaceholderHook;
import net.tfminecraft.activitytf.listeners.CraftListener;
import net.tfminecraft.activitytf.listeners.JoinListener;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(ActivityPluginTest.FailUnimplemented.class)
class ActivityPluginTest {
    @TempDir
    Path directory;

    private Server previousServer;
    private Object previousManager;
    private Object previousPlaceholderApi;
    private ServerMock server;
    private ActivityPlugin plugin;
    private final List<String> logs = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        previousServer = Bukkit.getServer();
        previousManager = field(ActivityManager.class, "instance").get(null);
        field(Bukkit.class, "server").set(null, null);
        field(ActivityManager.class, "instance").set(null, null);
        server = MockBukkit.mock();
        previousPlaceholderApi = field(PlaceholderAPIPlugin.class, "instance").get(null);
    }

    @AfterEach
    void stopServer() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            field(PlaceholderAPIPlugin.class, "instance").set(null, previousPlaceholderApi);
            field(ActivityManager.class, "instance").set(null, previousManager);
            field(Bukkit.class, "server").set(null, previousServer);
        }
    }

    private ActivityPlugin loadPlugin() {
        plugin = (ActivityPlugin) server.getPluginManager().loadPlugin(ActivityPlugin.class);
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return plugin;
    }

    private void enablePlugin() {
        loadPlugin();
        server.getPluginManager().enablePlugin(plugin);
    }

    @Test
    void startupInstallsCommandsListenersAndTimersAndShutdownPersistsState() throws Exception {
        enablePlugin();
        ActivityManager manager = ActivityManager.getInstance();
        assertTrue(plugin.isEnabled());
        assertTrue(manager.getStore().isLoaded());
        assertTrue(Files.isRegularFile(plugin.getDataFolder().toPath().resolve("config.yml")));
        assertTrue(Files.isRegularFile(plugin.getDataFolder().toPath().resolve("messages.yml")));
        assertInstanceOf(ActivityCommand.class, plugin.getCommand("activity").getExecutor());
        assertSame(plugin.getCommand("activity").getExecutor(), plugin.getCommand("activity").getTabCompleter());
        assertEquals(Set.of(JoinListener.class, CraftListener.class, ActivityGui.class), listeners());
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "activity"));
        assertNotNull(server.getConsoleSender().nextMessage());
        server.getScheduler().performOneTick();
        assertEquals(Set.of(JoinListener.class, CraftListener.class, ActivityGui.class), listeners());
        UUID uuid = UUID.randomUUID();
        manager.getStore().get(uuid).addPoints(7, manager.getConfiguration().barMax());
        server.getScheduler().performTicks(manager.getConfiguration().saveIntervalMinutes() * 1200L);
        server.getScheduler().waitAsyncTasksFinished();
        Path dataFile = plugin.getDataFolder().toPath().resolve("players.yml");
        assertEquals(7, YamlConfiguration.loadConfiguration(dataFile.toFile()).getInt("players." + uuid + ".points"));
        manager.getStore().get(uuid).addPoints(1, manager.getConfiguration().barMax());

        server.getPluginManager().disablePlugin(plugin);

        assertFalse(plugin.isEnabled());
        assertNull(ActivityManager.getInstance());
        assertTrue(listeners().isEmpty());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("players.yml").toFile());
        assertEquals(8, saved.getInt("players." + uuid + ".points"));
        String savedBytes = Files.readString(dataFile);
        server.getScheduler().performTicks(manager.getConfiguration().saveIntervalMinutes() * 1200L);
        assertEquals(savedBytes, Files.readString(dataFile));
        plugin.onDisable();
        assertNull(ActivityManager.getInstance());
        assertTrue(logs.contains("activity has been enabled!"));
    }

    @Test
    void rejectedPlaceholderRegistrationMustNotRemoveAProviderInstalledLater() throws Exception {
        LocalExpansionManager expansions = installPlaceholderApi();
        JavaPlugin guard = MockBukkit.createMockPlugin("ExpansionGuard");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void rejectActivity(ExpansionRegisterEvent event) {
                if (event.getExpansion() instanceof PlaceholderHook) {
                    event.setCancelled(true);
                }
            }
        }, guard);
        enablePlugin();
        server.getScheduler().performOneTick();
        assertNull(expansions.getExpansion("activity"));
        PlaceholderExpansion replacement = replacement();
        assertTrue(replacement.register());

        server.getPluginManager().disablePlugin(plugin);

        assertSame(replacement, expansions.getExpansion("activity"), "shutdown must not unregister a rejected hook's replacement");
        assertFalse(logs.contains("Hooked into PlaceholderAPI."), "a rejected registration must not be reported as successful");
        assertTrue(logs.stream().anyMatch(line -> line.contains("Failed to register the PlaceholderAPI expansion")));
    }

    @Test
    void shutdownMustNotUnregisterAProviderThatReplacedTheActivityExpansion() throws Exception {
        LocalExpansionManager expansions = installPlaceholderApi();
        enablePlugin();
        server.getScheduler().performOneTick();
        PlaceholderExpansion registered = expansions.getExpansion("activity");
        assertInstanceOf(PlaceholderHook.class, registered);
        PlaceholderExpansion replacement = new PlaceholderHook(ActivityManager.getInstance());
        assertEquals(registered, replacement, "matching expansion metadata does not establish instance ownership");
        assertTrue(replacement.register());

        server.getPluginManager().disablePlugin(plugin);

        assertSame(replacement, expansions.getExpansion("activity"));
    }

    @Test
    void optionalPluginsRegisterTheirListenersOnTheNextTickAndTheOwnedExpansionIsRemovedAtShutdown() throws Exception {
        for (String name : List.of("VotingPlugin", "MusicalInstruments", "geiger_counter", "MMOCore", "MMOItems",
            "VFBuilders", "RPCharacters", "InteractibleFurniture", "SimpleFactions", "AdvancedCrafting", "Archaeo",
            "MarketBlock", "Games", "Cooking", "BreweryX")) {
            MockBukkit.createMockPlugin(name);
        }
        LocalExpansionManager expansions = installPlaceholderApi();
        enablePlugin();
        assertEquals(Set.of(JoinListener.class, CraftListener.class, ActivityGui.class), listeners());
        assertNull(expansions.getExpansion("activity"));

        server.getScheduler().performOneTick();

        assertEquals(Set.of("JoinListener", "CraftListener", "ActivityGui", "VoteListener", "InstrumentListener",
            "GeigerListener", "ProfessionXpListener", "MmoItemsStationListener", "VehicleBuildListener",
            "CharacterChatListener", "InjuryListener", "ProfessionUpgradeListener", "FurnitureListener", "BattleListener",
            "AdvancedCraftListener", "ArchaeologyListener", "MarketSaleListener", "CasinoWinListener", "DishCookedListener",
            "BreweryListener"), listeners().stream().map(Class::getSimpleName).collect(Collectors.toSet()));
        assertInstanceOf(PlaceholderHook.class, expansions.getExpansion("activity"));
        var player = server.addPlayer("Steve");
        ActivityManager.getInstance().getStore().get(player.getUniqueId()).addPoints(5, 50);
        assertEquals("5", PlaceholderAPI.setPlaceholders(player, "%activity_points%"));
        assertEquals(16, logs.stream().filter(line -> line.startsWith("Hooked into ")).count());

        server.getPluginManager().disablePlugin(plugin);

        assertNull(expansions.getExpansion("activity"));
        assertEquals("%activity_points%", PlaceholderAPI.setPlaceholders(player, "%activity_points%"));
        assertTrue(listeners().isEmpty());
    }

    @Test
    void itemPluginsEnabledLaterCauseOneDeferredConfigurationReload() {
        enablePlugin();
        ActivityManager manager = ActivityManager.getInstance();
        assertFalse(manager.getConfiguration().itemsAdderUsable());
        plugin.getConfig().set("bar.max", 31);
        plugin.saveConfig();
        MockBukkit.createMockPlugin("ItemsAdder");
        assertEquals(50, manager.getConfiguration().barMax());

        server.getScheduler().performOneTick();

        assertTrue(manager.getConfiguration().itemsAdderUsable());
        assertEquals(31, manager.getConfiguration().barMax());
        assertFalse(manager.getConfiguration().itemPluginsChanged());
        server.getScheduler().performOneTick();
        assertEquals(1, logs.stream().filter(line -> line.contains("An item plugin finished enabling after activity")).count());
    }

    @Test
    void placeholderApiFailureDuringShutdownIsLoggedOnceAndDoesNotPreventStoreSave() throws Exception {
        LocalExpansionManager expansions = installPlaceholderApi();
        enablePlugin();
        server.getScheduler().performOneTick();
        assertNotNull(expansions.getExpansion("activity"));
        UUID uuid = UUID.randomUUID();
        ActivityManager.getInstance().getStore().get(uuid).addPoints(4, 50);

        try (var api = mockStatic(PlaceholderAPIPlugin.class)) {
            api.when(PlaceholderAPIPlugin::getInstance).thenThrow(new IllegalStateException("provider unavailable"));
            server.getPluginManager().disablePlugin(plugin);
            plugin.onDisable();
            api.verify(PlaceholderAPIPlugin::getInstance);
        }

        assertNull(ActivityManager.getInstance());
        assertEquals(1, logs.stream().filter(line -> line.equals(
            "Failed to unregister the PlaceholderAPI expansion: provider unavailable")).count());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("players.yml").toFile());
        assertEquals(4, saved.getInt("players." + uuid + ".points"));
    }

    private LocalExpansionManager installPlaceholderApi() throws Exception {
        MockBukkit.createMockPlugin("PlaceholderAPI");
        PlaceholderAPIPlugin api = new ObjenesisStd().newInstance(PlaceholderAPIPlugin.class);
        field(JavaPlugin.class, "dataFolder").set(api, directory.toFile());
        field(JavaPlugin.class, "logger").set(api, Logger.getAnonymousLogger());
        field(JavaPlugin.class, "newConfig").set(api, new YamlConfiguration());
        field(PlaceholderAPIPlugin.class, "config").set(api, new PlaceholderAPIConfig(api));
        LocalExpansionManager expansions = new LocalExpansionManager(api);
        field(PlaceholderAPIPlugin.class, "localExpansionManager").set(api, expansions);
        field(PlaceholderAPIPlugin.class, "instance").set(null, api);
        return expansions;
    }

    private PlaceholderExpansion replacement() {
        return new PlaceholderExpansion() {
            @Override public String getIdentifier() { return "activity"; }
            @Override public String getAuthor() { return "Other provider"; }
            @Override public String getVersion() { return "1"; }
        };
    }

    private Set<Class<?>> listeners() {
        return HandlerList.getRegisteredListeners(plugin).stream().map(listener -> listener.getListener().getClass())
            .collect(Collectors.toSet());
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable thrown) throws Throwable { throw failed(thrown); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable thrown) throws Throwable { throw failed(thrown); }
        private static Throwable failed(Throwable thrown) {
            return thrown instanceof UnimplementedOperationException
                ? new AssertionError("MockBukkit does not implement a lifecycle operation required by this test", thrown) : thrown;
        }
    }
}
