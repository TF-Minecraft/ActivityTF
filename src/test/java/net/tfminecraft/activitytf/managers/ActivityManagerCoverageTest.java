package net.tfminecraft.activitytf.managers;

import net.tfminecraft.activitytf.hooks.TLibsItems;
import net.tfminecraft.activitytf.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Integration coverage uses real configuration, storage, inventory and scheduled work. */
@ExtendWith(ActivityManagerCoverageTest.FailUnimplemented.class)
class ActivityManagerCoverageTest {
    private Server previousServer;
    private Object previousManager;
    private ServerMock server;
    private JavaPlugin plugin;
    private ActivityManager manager;
    private PlayerMock player;
    private Path folder;
    private YamlConfiguration settings;
    private final List<String> logs = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        previousServer = Bukkit.getServer();
        previousManager = field(ActivityManager.class, "instance").get(null);
        field(Bukkit.class, "server").set(null, null);
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ManagerCoverage");
        folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        Files.copy(Path.of("src/main/resources/messages.yml"), folder.resolve("messages.yml"));
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        settings = new YamlConfiguration();
        settings.set("activities.playtime.display", "Playtime");
        settings.set("activities.playtime.material", "CLOCK");
        settings.set("activities.playtime.every", 1);
        settings.set("activities.playtime.points", 1);
        settings.set("bar.max", 50);
        settings.set("bar.daily-max", 50);
        settings.set("bar.milestones", List.of(10, 20));
        settings.set("playtime.afk-minutes", 5);
        settings.set("save-interval-minutes", 60);
        settings.set("sounds.goal-complete", "ENTITY_EXPERIENCE_ORB_PICKUP");
        settings.set("sounds.bar-complete", "UI_TOAST_CHALLENGE_COMPLETE");
        settings.set("rewards.drops.drop_1", "DIAMOND 2");
        settings.set("rewards.drops.drop_2", "EMERALD 3");
        ActivityManager.reportedItemPaths.clear();
        ActivityManager.reportedBrokenCommands.clear();
        player = server.addPlayer("Steve");
    }

    @AfterEach
    void stopServer() throws Exception {
        try {
            if (manager != null) {
                server.getScheduler().waitAsyncTasksFinished();
                manager.shutdown();
            }
            MockBukkit.unmock();
        } finally {
            field(ActivityManager.class, "instance").set(null, previousManager);
            field(Bukkit.class, "server").set(null, previousServer);
        }
    }

    private void load(boolean loadStore) throws Exception {
        settings.save(folder.resolve("config.yml").toFile());
        Constructor<ActivityManager> constructor = ActivityManager.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        manager = constructor.newInstance(plugin);
        manager.getConfiguration().load();
        if (loadStore) manager.getStore().load();
    }

    private PlayerData data() { return manager.getStore().get(player.getUniqueId()); }
    private Path playersFile() { return folder.resolve("players.yml"); }
    private YamlConfiguration saved() { return YamlConfiguration.loadConfiguration(playersFile().toFile()); }
    private int claimedOnDisk() { return saved().getInt("players." + player.getUniqueId() + ".claimed-points"); }
    private long warnings(String part) { return logs.stream().filter(line -> line.contains(part)).count(); }
    private void points(int count) { data().addPoints(count, manager.getConfiguration().barMax()); }
    private void reveal(Player target) {
        for (int i = 0; i < manager.tasks(target.getUniqueId()).tasks().size(); i++) {
            manager.reveal(target.getUniqueId(), i);
        }
    }
    private void pool(String command) {
        settings.set("rewards.pool", List.of(Map.of("weight", 1, "display", "Console reward", "commands", List.of(command))));
    }
    private void command(String name, Consumer<String[]> execute) {
        server.getCommandMap().register("coverage", new Command(name) {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                assertSame(server.getConsoleSender(), sender);
                execute.accept(args);
                return true;
            }
        });
    }
    private void assertFailureMessage() {
        assertEquals(manager.getConfiguration().messages().get("reward-failed"), player.nextMessage());
    }
    private void makePlayersPathUnwritable() throws Exception {
        Files.deleteIfExists(playersFile());
        Files.createDirectory(playersFile());
    }
    private void restorePlayersPath() throws Exception { Files.delete(playersFile()); }

    @Test
    void unloadedStoreRejectsClaimsOnceWithoutDispatchOrWriting() throws Exception {
        load(false);
        points(20);
        assertEquals(0, manager.claim(player));
        assertEquals(0, manager.claim(player));
        assertEquals(0, data().claimedPoints());
        assertTrue(player.getInventory().isEmpty());
        assertFalse(Files.exists(playersFile()));
        assertEquals(1, warnings("Refusing every reward claim"));
        assertFailureMessage();
        assertFailureMessage();
    }

    @Test
    void missingDefaultAndNamedPoolsStayClaimableAndWarnOnlyOnce() throws Exception {
        settings.set("rewards.drops.drop_1", "pool");
        settings.set("rewards.drops.drop_2", "pool_missing");
        load(true);
        points(20);
        logs.clear();
        assertEquals(0, manager.claim(player));
        assertEquals(0, manager.claim(player));
        assertEquals(0, data().claimedPoints());
        assertEquals(1, warnings("Handed nothing"));
        assertTrue(logs.stream().anyMatch(line -> line.contains("rewards.pool") && line.contains("rewards.pools.pool_missing")));
        assertEquals(manager.getConfiguration().messages().get("reward-unconfigured"), player.nextMessage());
        assertTrue(player.getInventory().isEmpty());
    }

    @Test
    void unsafeCommandOnlyPoolCannotClaimEitherMilestoneOrDailyReward() throws Exception {
        player = server.addPlayer("Unsafe Name");
        settings.set("rewards.drops.drop_1", "pool");
        settings.set("daily-reward.groups.vip", "pool");
        pool("reward %player%");
        load(true);
        player.addAttachment(plugin, "group.vip", true);
        points(10);
        reveal(player);
        assertEquals(0, manager.claim(player));
        assertFalse(manager.claimDailyReward(player));
        assertEquals(0, data().claimedPoints());
        assertFalse(data().dailyRewardClaimed());
        assertTrue(player.getInventory().isEmpty());
        assertFailureMessage();
        assertFailureMessage();
        assertEquals(1, warnings("No reward command could be run"));
        assertEquals(1, warnings("No daily reward command could be run"));
        assertFalse(Files.exists(playersFile()));
    }

    @Test
    void failedPrepaymentSaveRestoresClaimAndHandsNothingOver() throws Exception {
        load(true);
        points(20);
        makePlayersPathUnwritable();
        try {
            assertEquals(0, manager.claim(player));
            assertEquals(0, data().claimedPoints());
            assertTrue(player.getInventory().isEmpty());
            assertFailureMessage();
            assertEquals(1, warnings("nothing was dispatched"));
        } finally { restorePlayersPath(); }
        assertEquals(2, manager.claim(player));
        assertEquals(20, claimedOnDisk());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        assertEquals(Material.EMERALD, player.getInventory().getItem(1).getType());
    }

    @Test
    void successfulPoolCommandsObserveSavedClaimAndCannotBePaidTwice() throws Exception {
        settings.set("rewards.drops.drop_1", "pool");
        pool("reward %player% %uuid%");
        AtomicInteger paid = new AtomicInteger();
        command("reward", args -> {
            assertEquals(20, claimedOnDisk(), "all due milestones must be saved before the first payout");
            assertArrayEquals(new String[]{player.getName(), player.getUniqueId().toString()}, args);
            paid.incrementAndGet();
        });
        load(true);
        points(20);
        assertEquals(2, manager.claim(player));
        assertEquals(1, paid.get());
        assertEquals(20, data().claimedPoints());
        assertEquals(Material.EMERALD, player.getInventory().getItem(0).getType());
        assertNotNull(player.nextMessage());
        assertNotNull(player.nextMessage());
        assertEquals(1, player.getHeardSounds().size());
        assertEquals(0, manager.claim(player));
        assertEquals(1, paid.get());
    }

    @Test
    void noSuccessfulPayoutQueuesRollbackToDiskAndCanRetry() throws Exception {
        settings.set("rewards.drops.drop_1", "pool");
        pool("reward");
        AtomicInteger attempts = new AtomicInteger();
        command("reward", args -> {
            assertEquals(10, claimedOnDisk());
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("provider failed before payment");
        });
        load(true);
        points(10);
        assertEquals(0, manager.claim(player));
        assertEquals(0, data().claimedPoints());
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals(0, claimedOnDisk());
        assertFailureMessage();
        assertEquals(1, warnings("paid none of the milestones"));
        assertEquals(1, manager.claim(player));
        assertEquals(10, claimedOnDisk());
        assertEquals(2, attempts.get());
    }

    @Test
    void partiallyPaidMilestonesKeepThePaidClaimAndPersistTheUnpaidRemainder() throws Exception {
        settings.set("rewards.drops.drop_2", "m.material.missing");
        load(true);
        points(20);
        assertEquals(1, manager.claim(player));
        assertEquals(10, data().claimedPoints());
        assertEquals(10, claimedOnDisk());
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        assertNotNull(player.nextMessage());
        assertFailureMessage();
        assertEquals(0, manager.claim(player));
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals(10, claimedOnDisk());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void failedPartialRollbackReportsDiskMismatchWithoutRepeatingPaidMilestone() throws Exception {
        settings.set("rewards.drops.drop_1", "pool");
        settings.set("rewards.drops.drop_2", "m.material.missing");
        pool("reward");
        AtomicInteger paid = new AtomicInteger();
        command("reward", args -> {
            assertEquals(20, claimedOnDisk());
            paid.incrementAndGet();
            try { makePlayersPathUnwritable(); } catch (Exception e) { throw new AssertionError(e); }
        });
        load(true);
        points(20);
        try {
            assertEquals(1, manager.claim(player));
            assertEquals(10, data().claimedPoints());
            assertEquals(1, paid.get());
            assertEquals(1, warnings("is out of sync"));
            assertNotNull(player.nextMessage());
            assertFailureMessage();
        } finally { restorePlayersPath(); }
        assertEquals(0, manager.claim(player));
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals(1, paid.get());
        assertEquals(10, claimedOnDisk());
    }

    @Test
    void partiallyPaidSpinsKeepMilestoneClaimedAndReportTheOwedSpin() throws Exception {
        settings.set("rewards.drops.drop_1", "pool");
        settings.set("rewards.multiplier", 2);
        settings.set("rewards.multiplier-pools", List.of("pool"));
        pool("reward");
        AtomicInteger attempts = new AtomicInteger();
        command("reward", args -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("second spin failed");
        });
        load(true);
        points(10);
        assertEquals(1, manager.claim(player));
        assertEquals(2, attempts.get());
        assertEquals(10, claimedOnDisk());
        assertEquals(10, data().claimedPoints());
        assertEquals(1, warnings("1 spin is owed"));
        assertNotNull(player.nextMessage());
        assertFailureMessage();
        assertTrue(player.getHeardSounds().isEmpty(), "incomplete payouts must not play the success sound");
        assertEquals(0, manager.claim(player));
        assertEquals(2, attempts.get());
    }

    private void enableTLibsDependencies() {
        for (String name : List.of("TLibs", "MMOItems", "MythicLib")) MockBukkit.createMockPlugin(name);
    }

    @Test
    void dailyRewardProviderFailureLeavesRewardUnclaimedAndCanRecover() throws Exception {
        enableTLibsDependencies();
        settings.set("daily-reward.groups.vip", "m.material.steel 3");
        settings.set("rewards.multiplier", 2);
        load(true);
        reveal(player);
        player.addAttachment(plugin, "group.vip", true);
        try (var items = mockStatic(TLibsItems.class)) {
            items.when(() -> TLibsItems.item("m.material.steel")).thenThrow(new LinkageError("provider unavailable"));
            assertFalse(manager.claimDailyReward(player));
            assertFalse(data().dailyRewardClaimed());
            assertTrue(player.getInventory().isEmpty());
            assertFailureMessage();
            items.when(() -> TLibsItems.item("m.material.steel")).thenReturn(new ItemStack(Material.IRON_INGOT));
            assertTrue(manager.claimDailyReward(player));
        }
        assertEquals(6, player.getInventory().getItem(0).getAmount());
        assertTrue(data().dailyRewardClaimed());
        assertTrue(saved().getBoolean("players." + player.getUniqueId() + ".daily-reward-claimed"));
        assertTrue(player.nextMessage().contains("x6"));
    }

    @Test
    void inventoryResyncFailureDoesNotUndoAlreadyDeliveredReward() throws Exception {
        load(true);
        points(10);
        Player boundary = mock(Player.class, delegatesTo(player));
        doThrow(new IllegalStateException("resync failed")).when(boundary).updateInventory();
        assertEquals(1, manager.claim(boundary));
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        assertEquals(10, claimedOnDisk());
        assertEquals(1, warnings("Could not resync the inventory"));
        assertEquals(0, manager.claim(boundary));
    }

    @Test
    void fullInventoryDropsEveryRewardStackWithTheRecipientsOwnership() throws Exception {
        settings.set("rewards.drops.drop_1", "DIAMOND 64");
        settings.set("rewards.multiplier", 2);
        load(true);
        points(10);
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        // MockBukkit addItem also scans equipment/extra slots. Model Bukkit's full-storage
        // result at that API boundary while retaining the real items, player and world drops.
        PlayerInventory inventory = mock(PlayerInventory.class, delegatesTo(player.getInventory()));
        doAnswer(call -> {
            ItemStack[] offered = (ItemStack[]) call.getRawArguments()[0];
            assertEquals(1, offered.length);
            assertTrue(java.util.Arrays.stream(player.getInventory().getStorageContents())
                .allMatch(stack -> stack != null && stack.getType() == Material.DIRT && stack.getAmount() == 64));
            return new HashMap<>(Map.of(0, offered[0].clone()));
        }).when(inventory).addItem(any(ItemStack[].class));
        Player boundary = mock(Player.class, delegatesTo(player));
        doReturn(inventory).when(boundary).getInventory();
        // ItemMock also leaves ownership setters unimplemented. Spawn the real entity,
        // then verify the ownership calls on an API adapter passed to the production callback.
        World world = mock(World.class, delegatesTo(player.getWorld()));
        List<Item> ownershipCalls = new ArrayList<>();
        doAnswer(call -> {
            Item actual = player.getWorld().dropItemNaturally(call.getArgument(0), call.getArgument(1));
            Item drop = mock(Item.class, delegatesTo(actual));
            doNothing().when(drop).setOwner(any());
            doNothing().when(drop).setThrower(any());
            Consumer<Item> initialize = call.getArgument(2);
            initialize.accept(drop);
            ownershipCalls.add(drop);
            return drop;
        }).when(world).dropItemNaturally(any(Location.class), any(ItemStack.class), any());
        doReturn(world).when(boundary).getWorld();
        assertEquals(1, manager.claim(boundary));
        var drops = player.getWorld().getEntitiesByClass(Item.class);
        assertEquals(2, drops.size());
        for (Item drop : drops) {
            assertEquals(Material.DIAMOND, drop.getItemStack().getType());
            assertEquals(64, drop.getItemStack().getAmount());
            assertTrue(drop.getLocation().distance(player.getLocation()) < 2);
        }
        assertEquals(2, ownershipCalls.size());
        for (Item drop : ownershipCalls) {
            verify(drop).setOwner(player.getUniqueId());
            verify(drop).setThrower(player.getUniqueId());
        }
        assertEquals(0, warnings("threw for"));
        assertEquals(10, claimedOnDisk());
        assertFalse(player.getInventory().contains(Material.DIAMOND));
        assertEquals(0, manager.claim(boundary));
        assertEquals(2, player.getWorld().getEntitiesByClass(Item.class).size());
    }

    @Test
    void minuteTimerCreditsOnlyRevealedActivePlayersAndCanDisableTheAfkCheck() throws Exception {
        IdlePlayer active = new IdlePlayer(server, "Active", Duration.ofMinutes(4));
        IdlePlayer idle = new IdlePlayer(server, "Idle", Duration.ofMinutes(5));
        IdlePlayer hidden = new IdlePlayer(server, "Hidden", Duration.ZERO);
        server.addPlayer(active);
        server.addPlayer(idle);
        server.addPlayer(hidden);
        player.disconnect();
        load(true);
        manager.initialize();
        manager.initialize(); // Reinitialization must replace, not multiply, the playtime timer.
        reveal(active);
        reveal(idle);
        manager.tasks(hidden.getUniqueId());
        server.getScheduler().performTicks(1200);
        assertEquals(1, manager.tasks(active.getUniqueId()).count("playtime"));
        assertEquals(0, manager.tasks(idle.getUniqueId()).count("playtime"));
        assertEquals(0, manager.tasks(hidden.getUniqueId()).count("playtime"));
        assertEquals(1, active.getHeardSounds().size());
        settings.set("playtime.afk-minutes", 0);
        settings.save(folder.resolve("config.yml").toFile());
        manager.reload();
        server.getScheduler().performTicks(1200);
        assertEquals(2, manager.tasks(active.getUniqueId()).count("playtime"));
        assertEquals(1, manager.tasks(idle.getUniqueId()).count("playtime"));
        assertEquals(0, manager.tasks(hidden.getUniqueId()).count("playtime"));
        manager.shutdown();
        server.getScheduler().performTicks(1200);
        assertEquals(2, manager.tasks(active.getUniqueId()).count("playtime"));
    }

    @Test
    void joiningPlayerSeesOnlyOutstandingClaimAndMilestoneCreditPlaysBothSounds() throws Exception {
        load(true);
        manager.onJoin(player);
        assertNull(player.nextMessage());
        manager.recordPoints(player.getUniqueId(), 10);
        assertNotNull(player.nextMessage());
        assertEquals(manager.getConfiguration().messages().get("reward-ready"), player.nextMessage());
        assertEquals(2, player.getHeardSounds().size());
        manager.onJoin(player);
        assertEquals(manager.getConfiguration().messages().get("reward-ready"), player.nextMessage());
        assertEquals(1, manager.claim(player));
        assertNotNull(player.nextMessage());
        manager.onJoin(player);
        assertNull(player.nextMessage());
    }

    @Test
    void clickCommandsEnforcePlayerCooldownWithoutDebitingAnotherPlayersBudget() throws Exception {
        settings.set("activities.playtime.click-commands", List.of("clickreward"));
        settings.set("click-command-cooldown-millis", 60_000);
        settings.set("click-commands-per-second", 200);
        AtomicInteger runs = new AtomicInteger();
        command("clickreward", args -> runs.incrementAndGet());
        load(true);
        // MockBukkit's closed view has no top inventory; Paper supplies a crafting inventory.
        player.openInventory(server.createInventory(null, 9));
        assertFalse(manager.runClickCommands(player, -1));
        assertFalse(manager.runClickCommands(player, 1));
        assertFalse(manager.runClickCommands(player, 0));
        reveal(player);
        assertTrue(manager.runClickCommands(player, 0));
        assertFalse(manager.runClickCommands(player, 0));
        assertEquals(0, runs.get(), "commands wait for the next server tick");
        server.getScheduler().performOneTick();
        assertEquals(1, runs.get());
        manager.forgetClickCooldown(player.getUniqueId());
        assertTrue(manager.runClickCommands(player, 0));
        server.getScheduler().performOneTick();
        assertEquals(2, runs.get());
    }

    @Test
    void clickCommandsUseOneServerWideBudgetAcrossPlayers() throws Exception {
        settings.set("activities.playtime.click-commands", List.of("clickreward"));
        settings.set("click-commands-per-second", 1);
        AtomicInteger runs = new AtomicInteger();
        command("clickreward", args -> runs.incrementAndGet());
        load(true);
        // MockBukkit's closed view has no top inventory; Paper supplies a crafting inventory.
        player.openInventory(server.createInventory(null, 9));
        PlayerMock second = server.addPlayer("Alex");
        reveal(player);
        reveal(second);
        assertTrue(manager.runClickCommands(player, 0));
        assertFalse(manager.runClickCommands(second, 0));
        server.getScheduler().performOneTick();
        assertEquals(1, runs.get());
        assertEquals(1, manager.clickCooldownEntries(), "budget rejection must not start the second player's cooldown");
    }

    @Test
    void revealedTaskWithoutCommandsDoesNotScheduleAnything() throws Exception {
        load(true);
        reveal(player);
        assertFalse(manager.runClickCommands(player, 0));
        assertEquals(0, manager.clickCooldownEntries());
        server.getScheduler().performOneTick();
        assertNull(player.nextMessage());
    }

    private static final class IdlePlayer extends PlayerMock {
        private final Duration idle;
        private IdlePlayer(ServerMock server, String name, Duration idle) {
            super(server, name);
            this.idle = idle;
        }
        @Override public Duration getIdleDuration() { return idle; }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            if (failure instanceof UnimplementedOperationException) {
                throw new AssertionError("Fixture encountered an unimplemented Bukkit API", failure);
            }
            throw failure;
        }
    }
}
