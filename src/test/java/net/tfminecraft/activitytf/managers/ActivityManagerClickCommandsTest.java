package net.tfminecraft.activitytf.managers;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.models.ActivityDef;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityManagerClickCommandsTest {

    private final List<LogRecord> logged = new ArrayList<>();

    @BeforeEach
    void forgetReportedCommands() {
        ActivityManager.reportedBrokenCommands.clear();
    }

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static Player player(String name, UUID uuid) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> uuid;
            case "getOpenInventory" -> null;
            case "toString" -> "stub-player";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected Player#" + method.getName());
        };
        return (Player) Proxy.newProxyInstance(
            ActivityManagerClickCommandsTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private boolean logged(String fragment) {
        return logged.stream().anyMatch(record -> record.getMessage().contains(fragment));
    }

    @Test
    void bothPlaceholdersAreSubstituted() {
        UUID uuid = UUID.randomUUID();
        List<String> ran = new ArrayList<>();

        assertTrue(ActivityManager.dispatchCommands(player("Notch", uuid),
            List.of("sudo %player% votelist", "lp user %uuid% parent add vip"), "click", logger(), ran::add));

        assertEquals(List.of("sudo Notch votelist", "lp user " + uuid + " parent add vip"), ran);
    }

    @Test
    void anUnsafeNameSkipsThePlayerCommandAndStillRunsTheUuidOne() {
        UUID uuid = UUID.randomUUID();
        List<String> ran = new ArrayList<>();

        assertTrue(ActivityManager.dispatchCommands(player("Bob; op Bob", uuid),
            List.of("sudo %player% votelist", "say %uuid%"), "click", logger(), ran::add));

        assertEquals(List.of("say " + uuid), ran);
        assertTrue(logged("Skipping click command"), logged.toString());
    }

    @Test
    void everyCommandSkippedMeansNothingRan() {
        List<String> ran = new ArrayList<>();

        assertFalse(ActivityManager.dispatchCommands(player("Bedrock Player", UUID.randomUUID()),
            List.of("sudo %player% votelist"), "click", logger(), ran::add));

        assertEquals(List.of(), ran);
    }

    @Test
    void aThrowingCommandIsLoggedAndTheRestStillRun() {
        List<String> ran = new ArrayList<>();
        Predicate<String> console = command -> {
            if (command.startsWith("boom")) {
                throw new IllegalStateException("boom");
            }
            return ran.add(command);
        };

        assertTrue(ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("boom now", "say ok"), "click", logger(), console));

        assertEquals(List.of("say ok"), ran);
        assertTrue(logged("A click command 'boom now' threw"), logged.toString());
    }

    @Test
    void aThrowingOnlyCommandCountsAsNothingRan() {
        assertFalse(ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("boom"), "click", logger(), command -> {
                throw new IllegalStateException("boom");
            }));
    }

    @Test
    void theLabelNamesTheKindOfCommandInTheLogLine() {
        ActivityManager.dispatchCommands(player("Bedrock Player", UUID.randomUUID()),
            List.of("give %player% diamond"), "reward", logger(), command -> true);

        assertTrue(logged("Skipping reward command"), logged.toString());
    }

    @Test
    void theFirstClickPassesAndASecondInsideTheIntervalDoesNot() {
        ActivityManager manager = TestManagers.manager();
        UUID uuid = UUID.randomUUID();

        assertTrue(manager.clickCooldownPassed(uuid, 1_000_000_000L, 1_000_000_000L));
        assertFalse(manager.clickCooldownPassed(uuid, 1_500_000_000L, 1_000_000_000L));
    }

    @Test
    void aClickAfterTheIntervalPassesAgain() {
        ActivityManager manager = TestManagers.manager();
        UUID uuid = UUID.randomUUID();

        assertTrue(manager.clickCooldownPassed(uuid, 1_000_000_000L, 1_000_000_000L));
        assertTrue(manager.clickCooldownPassed(uuid, 2_000_000_000L, 1_000_000_000L));
    }

    @Test
    void oneCooledDownPlayerDoesNotBlockAnother() {
        ActivityManager manager = TestManagers.manager();

        assertTrue(manager.clickCooldownPassed(UUID.randomUUID(), 1_000_000_000L, 1_000_000_000L));
        assertTrue(manager.clickCooldownPassed(UUID.randomUUID(), 1_000_000_000L, 1_000_000_000L));
    }

    @Test
    void aBlockedClickDoesNotPushTheCooldownOut() {
        ActivityManager manager = TestManagers.manager();
        UUID uuid = UUID.randomUUID();

        manager.clickCooldownPassed(uuid, 1_000_000_000L, 1_000_000_000L);
        manager.clickCooldownPassed(uuid, 1_900_000_000L, 1_000_000_000L);

        assertTrue(manager.clickCooldownPassed(uuid, 2_000_000_000L, 1_000_000_000L));
    }

    @Test
    void quittingDropsThePlayersCooldownEntry() {
        ActivityManager manager = TestManagers.manager();
        UUID stays = UUID.randomUUID();
        UUID leaves = UUID.randomUUID();

        manager.clickCooldownPassed(stays, 1_000_000_000L, 1_000_000_000L);
        manager.clickCooldownPassed(leaves, 1_000_000_000L, 1_000_000_000L);
        assertEquals(2, manager.clickCooldownEntries());

        manager.forgetClickCooldown(leaves);

        assertEquals(1, manager.clickCooldownEntries());
    }

    @Test
    void forgettingAPlayerWhoNeverClickedIsHarmless() {
        ActivityManager manager = TestManagers.manager();

        manager.forgetClickCooldown(UUID.randomUUID());

        assertEquals(0, manager.clickCooldownEntries());
    }

    private static ActivityManager withVote() {
        return TestManagers.manager(new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0,
            List.of("sudo %player% votelist"), List.of()));
    }

    @Test
    void theFirstClickRevealsAndASecondClickRevealsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();

        ActivityManager.Reveal first = manager.reveal(uuid, 0);
        assertEquals("vote", first.revealedId());

        ActivityManager.Reveal second = manager.reveal(uuid, 0);
        assertNull(second.revealedId());
        assertFalse(second.drawChanged());
    }

    @Test
    void anUnrevealedTaskRunsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertFalse(manager.runClickCommands(player("Notch", uuid), 0));
        assertEquals(0, manager.clickCooldownEntries());
    }

    @Test
    void aSlotOutsideTheDrawRunsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertFalse(manager.runClickCommands(player("Notch", uuid), -1));
        assertFalse(manager.runClickCommands(player("Notch", uuid), 99));
    }

    @Test
    void aRevealedTaskWithoutClickCommandsRunsNothing() {
        ActivityManager manager =
            TestManagers.manager(new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0));
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);

        assertFalse(manager.runClickCommands(player("Notch", uuid), 0));
        assertEquals(0, manager.clickCooldownEntries());
    }

    @Test
    void aRevealedTaskWhoseEveryCommandIsSkippedRunsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);

        assertFalse(manager.runClickCommands(player("Bedrock Player", uuid), 0));
        assertEquals(0, manager.clickCooldownEntries());
    }

    @Test
    void aClickThatCannotBeScheduledIsRefusedRatherThanThrown() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);

        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        TestManagers.logger().addHandler(capture);
        try {
            assertFalse(manager.runClickCommands(player("Notch", uuid), 0));
        } finally {
            TestManagers.logger().removeHandler(capture);
        }

        assertEquals(1, manager.clickCooldownEntries());
        assertTrue(logged("Could not schedule an activity click"), logged.toString());
    }

    private static ActivityDef listing(String... commands) {
        return new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0, List.of(commands), List.of());
    }

    @Test
    void anUnsafeNameLeavesOnlyTheUuidCommandsToRun() {
        assertEquals(List.of("say %uuid%"), ActivityManager.runnableClickCommands(
            listing("sudo %player% votelist", "say %uuid%"), "Bedrock Player", 5, logger()));
    }

    @Test
    void aNameNoCommandCanBeRunForLeavesNothingToRun() {
        assertEquals(List.of(), ActivityManager.runnableClickCommands(
            listing("sudo %player% votelist"), "Bedrock Player", 5, logger()));
    }

    @Test
    void onlyTheFirstFewCommandsOfALongListAreRun() {
        assertEquals(List.of("say 1", "say 2"), ActivityManager.runnableClickCommands(
            listing("say 1", "say 2", "say 3", "say 4"), "Notch", 2, logger()));
        assertTrue(logged("lists more than 2 click-commands"), logged.toString());
    }

    @Test
    void theCapIsReportedOnlyOnce() {
        Logger logger = logger();
        ActivityDef def = listing("say 1", "say 2");

        ActivityManager.runnableClickCommands(def, "Notch", 1, logger);
        ActivityManager.runnableClickCommands(def, "Notch", 1, logger);

        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("lists more than 1")).count(), logged.toString());
    }

    @Test
    void aPeekDoesNotSpendAToken() {
        ActivityManager manager = TestManagers.manager();

        for (int i = 0; i < 10; i++) {
            assertTrue(manager.clickBudget(1_000_000_000L, 2, false));
        }
        assertTrue(manager.clickBudget(1_000_000_000L, 2, true));
    }

    @Test
    void aBurstPastTheCeilingIsRefused() {
        ActivityManager manager = TestManagers.manager();

        assertTrue(manager.clickBudget(1_000_000_000L, 2, true));
        assertTrue(manager.clickBudget(1_000_000_000L, 2, true));
        assertFalse(manager.clickBudget(1_000_000_000L, 2, true));
    }

    @Test
    void theBucketRefillsOverTime() {
        ActivityManager manager = TestManagers.manager();

        manager.clickBudget(1_000_000_000L, 2, true);
        manager.clickBudget(1_000_000_000L, 2, true);
        assertFalse(manager.clickBudget(1_000_000_000L, 2, false));

        assertTrue(manager.clickBudget(1_500_000_000L, 2, true));
    }

    @Test
    void theBucketNeverHoldsMoreThanASecondsWorth() {
        ActivityManager manager = TestManagers.manager();

        manager.clickBudget(1_000_000_000L, 2, true);
        long later = 1_000_000_000L + 3_600_000_000_000L;

        assertTrue(manager.clickBudget(later, 2, true));
        assertTrue(manager.clickBudget(later, 2, true));
        assertFalse(manager.clickBudget(later, 2, true));
    }

    @Test
    void aRefusedCommandStillCountsAsRunAndIsReportedOnce() {
        Logger logger = logger();

        assertTrue(ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("votelist"), "click", logger, command -> false));
        assertTrue(ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("votelist"), "click", logger, command -> false));

        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("was refused by the console")).count(), logged.toString());
    }

    @Test
    void aRefusedRewardCommandIsReportedSeparatelyFromAClickOne() {
        Logger logger = logger();

        ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("votelist"), "click", logger, command -> false);
        ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("votelist"), "reward", logger, command -> false);

        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("The click command")).count(), logged.toString());
        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("The reward command")).count(), logged.toString());
    }

    @Test
    void aThrowingCommandIsLoggedOnlyOnce() {
        Logger logger = logger();

        for (int i = 0; i < 3; i++) {
            ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
                List.of("boom"), "click", logger, command -> {
                    throw new IllegalStateException("boom");
                });
        }

        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("threw for")).count(), logged.toString());
    }

    private static final ClassLoader LOADER = ActivityManagerClickCommandsTest.class.getClassLoader();

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(LOADER, new Class<?>[]{type}, handler);
    }

    private static InventoryView viewOf(Inventory top) {
        return (InventoryView) proxy(InventoryView.class, (p, method, args) -> switch (method.getName()) {
            case "getTopInventory" -> top;
            case "toString" -> "stub-view";
            case "hashCode" -> 3;
            case "equals" -> p == args[0];
            default -> throw new UnsupportedOperationException("unexpected InventoryView#" + method.getName());
        });
    }

    private static Player lookingAt(String name, UUID uuid, InventoryHolder holder, List<String> record) {
        Inventory top = (Inventory) proxy(Inventory.class, (p, method, args) -> switch (method.getName()) {
            case "getHolder" -> holder;
            case "toString" -> "stub-inventory";
            case "hashCode" -> 2;
            case "equals" -> p == args[0];
            default -> throw new UnsupportedOperationException("unexpected Inventory#" + method.getName());
        });
        return (Player) proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> uuid;
            case "getOpenInventory" -> viewOf(top);
            case "closeInventory" -> {
                record.add("close");
                yield null;
            }
            case "toString" -> "stub-player";
            case "hashCode" -> 1;
            case "equals" -> p == args[0];
            default -> throw new UnsupportedOperationException("unexpected Player#" + method.getName());
        });
    }

    private void capturingPluginLog(Runnable work) {
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        TestManagers.logger().addHandler(capture);
        try {
            work.run();
        } finally {
            TestManagers.logger().removeHandler(capture);
        }
    }

    @Test
    void theMenuIsClosedBeforeTheCommandIsDispatched() {
        ActivityManager manager = withVote();
        List<String> record = new ArrayList<>();
        Player player = lookingAt("Notch", UUID.randomUUID(), new ActivityGui.Marker(), record);

        capturingPluginLog(() -> manager.runClickCommandsNow(
            player, List.of("sudo %player% votelist"), "vote", record::add));

        assertEquals(List.of("close", "sudo Notch votelist"), record);
    }

    @Test
    void anotherOpenWindowIsNotClosed() {
        ActivityManager manager = withVote();
        List<String> record = new ArrayList<>();
        InventoryHolder chest = (InventoryHolder) proxy(InventoryHolder.class,
            (p, method, args) -> switch (method.getName()) {
                case "toString" -> "stub-chest";
                case "hashCode" -> 4;
                case "equals" -> p == args[0];
                default -> throw new UnsupportedOperationException("unexpected holder#" + method.getName());
            });
        Player player = lookingAt("Notch", UUID.randomUUID(), chest, record);

        capturingPluginLog(() -> manager.runClickCommandsNow(
            player, List.of("sudo %player% votelist"), "vote", record::add));

        assertEquals(List.of("sudo Notch votelist"), record);
    }

    private void auditedRun(Predicate<String> console) {
        ActivityManager manager = withVote();
        Player player = lookingAt("Notch", UUID.randomUUID(), new ActivityGui.Marker(), new ArrayList<>());

        capturingPluginLog(() ->
            manager.runClickCommandsNow(player, List.of("sudo %player% votelist"), "vote", console));
    }

    @Test
    void aDispatchedCommandIsAudited() {
        auditedRun(command -> true);

        assertTrue(logged("action=click-command activity=\"vote\" command=\"sudo Notch votelist\""
            + " result=done"), logged.toString());
    }

    @Test
    void aRefusedCommandIsAuditedAsRefused() {
        auditedRun(command -> false);

        assertTrue(logged("command=\"sudo Notch votelist\" result=refused"), logged.toString());
    }

    @Test
    void aThrowingCommandIsStillAudited() {
        auditedRun(command -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(logged("command=\"sudo Notch votelist\" result=threw"), logged.toString());
    }

    @Test
    void aCommandThatThrewIsStillReportedWhenItIsLaterRefused() {
        Logger logger = logger();

        ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("boom"), "click", logger, command -> {
                throw new IllegalStateException("boom");
            });
        ActivityManager.dispatchCommands(player("Notch", UUID.randomUUID()),
            List.of("boom"), "click", logger, command -> false);

        assertTrue(logged("threw for"), logged.toString());
        assertTrue(logged("was refused by the console"), logged.toString());
    }

    @Test
    void aClickPaysForEveryCommandItDispatches() {
        ActivityManager manager = TestManagers.manager();

        assertTrue(manager.clickBudget(1_000_000_000L, 10, 5, true));
        assertTrue(manager.clickBudget(1_000_000_000L, 10, 5, true));
        assertFalse(manager.clickBudget(1_000_000_000L, 10, 5, false));
    }

    @Test
    void aClickBiggerThanTheBucketOverdrawsOnceAndThenWaits() {
        ActivityManager manager = TestManagers.manager();

        assertTrue(manager.clickBudget(1_000_000_000L, 2, 5, true));
        assertFalse(manager.clickBudget(1_000_000_000L, 2, 5, false));
        assertFalse(manager.clickBudget(2_000_000_000L, 2, 5, false));
        assertTrue(manager.clickBudget(3_000_000_000L, 2, 5, false));
    }
}
