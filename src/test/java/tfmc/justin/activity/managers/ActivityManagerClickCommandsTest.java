package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tfmc.justin.activity.models.ActivityDef;

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

// ====================================
// The console-dispatch half of an activity's 'click-commands':
// ActivityManager.dispatchCommands, which takes the console call and the
// logger rather than reaching for Bukkit and the plugin, so the substitution,
// the name guard and the throw guard can be driven headless - and
// clickCooldownPassed, the rate limit, which takes its clock reading rather
// than sleeping on a real one.
//
// runClickCommands itself is driven too, as far as headless goes: every
// refusal it can answer with (an unrevealed task, a slot outside the draw, an
// activity with no click-commands, a name no command can be run for) is
// decided before it reaches a scheduler, and a click that gets past all of
// them proves the guard around the scheduler rather than the dispatch.
//
// Not reachable headless: Bukkit.dispatchCommand itself (the CONSOLE
// constant) and the next-tick body - the close, the real dispatch and the
// audit line it writes all need a live scheduler. The stub player stands in
// for exactly the calls made on him; everything between them is the real code.
//
// The GUI's quit handler is a Bukkit event and cannot fire here either; what
// it calls, forgetClickCooldown, is driven directly.
// ====================================
class ActivityManagerClickCommandsTest {

    private final List<LogRecord> logged = new ArrayList<>();

    // The broken-command memo is static and lives until a reload, exactly as
    // it does on a server - so each test starts from an empty one rather than
    // from whatever the test before it reported
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
            // Nothing is open on a headless player; the close is only ever
            // reached on the tick after the click, which needs a scheduler
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

    // The name guard is per command: an unsafe name skips the ones that paste
    // it and leaves the %uuid%-only ones working
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

    // A third-party command that throws must not unwind into the click handler
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

    // The reward call site passes its own word, so one dispatcher never
    // mislabels the other's log line
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

    // Per player, not per activity: what is bounded is how often one player
    // can make the console run anything
    @Test
    void oneCooledDownPlayerDoesNotBlockAnother() {
        ActivityManager manager = TestManagers.manager();

        assertTrue(manager.clickCooldownPassed(UUID.randomUUID(), 1_000_000_000L, 1_000_000_000L));
        assertTrue(manager.clickCooldownPassed(UUID.randomUUID(), 1_000_000_000L, 1_000_000_000L));
    }

    // A blocked click must not push the window out, or a player clicking
    // faster than the interval would never come off cooldown
    @Test
    void aBlockedClickDoesNotPushTheCooldownOut() {
        ActivityManager manager = TestManagers.manager();
        UUID uuid = UUID.randomUUID();

        manager.clickCooldownPassed(uuid, 1_000_000_000L, 1_000_000_000L);
        manager.clickCooldownPassed(uuid, 1_900_000_000L, 1_000_000_000L);

        assertTrue(manager.clickCooldownPassed(uuid, 2_000_000_000L, 1_000_000_000L));
    }

    // What the GUI's quit handler does - without it the map would keep an
    // entry per player who ever clicked a task, for the whole uptime
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

    // ====================================
    // The reveal/run boundary, which is the whole contract of a click
    // command: a task only runs anything once it is revealed, and it is the
    // manager - not the caller's branch ordering - that decides so.
    // ====================================
    private static ActivityManager withVote() {
        return TestManagers.manager(new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0,
            List.of("sudo %player% votelist"), List.of()));
    }

    // The first click on a hidden task reveals it and the second finds
    // nothing to reveal - which is the state the GUI reads to decide that a
    // click is a run rather than a reveal
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

    // The invariant the GUI used to hold on its own: an unrevealed task
    // dispatches nothing, however it is reached
    @Test
    void anUnrevealedTaskRunsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertFalse(manager.runClickCommands(player("Notch", uuid), 0));
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
    }

    // Every command skipped by the name guard means nothing will run, and a
    // click that will run nothing must not report success or take a cooldown
    @Test
    void aRevealedTaskWhoseEveryCommandIsSkippedRunsNothing() {
        ActivityManager manager = withVote();
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);

        assertFalse(manager.runClickCommands(player("Bedrock Player", uuid), 0));
        assertEquals(0, manager.clickCooldownEntries());
    }

    // ====================================
    // A click with something to run gets as far as the scheduler, which is
    // where a headless run stops: there is no server behind getScheduler().
    // What that pins is the guard around it - a plugin that is disabling
    // throws there too, and it must come back as a refused click rather than
    // unwind through the inventory click handler.
    // ====================================
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

        // It really did get past both rate limits and as far as the scheduler
        assertEquals(1, manager.clickCooldownEntries());
        assertTrue(logged("Could not schedule an activity click"), logged.toString());
    }

    // ====================================
    // What one click will actually dispatch, decided before anything is
    // committed.
    // ====================================
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

    // The cooldown counts a click and not a command, so a long list would
    // otherwise multiply the cost of every click by its own length
    @Test
    void onlyTheFirstFewCommandsOfALongListAreRun() {
        assertEquals(List.of("say 1", "say 2"), ActivityManager.runnableClickCommands(
            listing("say 1", "say 2", "say 3", "say 4"), "Notch", 2, logger()));
        assertTrue(logged("lists more than 2 click-commands"), logged.toString());
    }

    // ...and the operator hears about it once, not once per click
    @Test
    void theCapIsReportedOnlyOnce() {
        Logger logger = logger();
        ActivityDef def = listing("say 1", "say 2");

        ActivityManager.runnableClickCommands(def, "Notch", 1, logger);
        ActivityManager.runnableClickCommands(def, "Notch", 1, logger);

        assertEquals(1, logged.stream().filter(record -> record.getMessage()
            .contains("lists more than 1")).count(), logged.toString());
    }

    // ====================================
    // The server-wide ceiling the per-player cooldown cannot give. A peek
    // must not spend a token, or a click the player's own cooldown goes on to
    // refuse would cost every other player one.
    // ====================================
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

        // Half a second at two a second is one token back
        assertTrue(manager.clickBudget(1_500_000_000L, 2, true));
    }

    // One second's worth at most: an idle hour must not bank an hour of
    // dispatches for one burst
    @Test
    void theBucketNeverHoldsMoreThanASecondsWorth() {
        ActivityManager manager = TestManagers.manager();

        manager.clickBudget(1_000_000_000L, 2, true);
        long later = 1_000_000_000L + 3_600_000_000_000L;

        assertTrue(manager.clickBudget(later, 2, true));
        assertTrue(manager.clickBudget(later, 2, true));
        assertFalse(manager.clickBudget(later, 2, true));
    }

    // ====================================
    // A command the console refused - unknown on this server, which the
    // shipped 'sudo %player% votelist' is without CMI or EssentialsX. It
    // still counts as run, the way it always has and the way the reward
    // payout's rollback rule is built on, but it is reported, and only once.
    // ====================================
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

    // The reward path reports its own, so one kind of command going quiet
    // does not silence the other
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

    // A command that throws is logged once too - it was once per click per
    // player before, for the whole uptime
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
}
