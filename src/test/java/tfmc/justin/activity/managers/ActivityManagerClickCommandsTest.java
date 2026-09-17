package tfmc.justin.activity.managers;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The console-dispatch half of an activity's 'click-commands':
// ActivityManager.dispatchCommands, which takes the console call and the
// logger rather than reaching for Bukkit and the plugin, so the substitution,
// the name guard and the throw guard can be driven headless - and
// clickCooldownPassed, the rate limit, which takes its clock reading rather
// than sleeping on a real one.
//
// Not reachable headless, and so not covered here: Bukkit.dispatchCommand
// itself (the CONSOLE constant), and runClickCommands around all of this - it
// needs a live scheduler for the next-tick close. The stub player stands in
// for exactly the two calls the dispatcher makes on him, getName and
// getUniqueId; everything between them is the real code.
//
// The GUI's quit handler is a Bukkit event and cannot fire here either; what
// it calls, forgetClickCooldown, is driven directly.
// ====================================
class ActivityManagerClickCommandsTest {

    private final List<LogRecord> logged = new ArrayList<>();

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
        Consumer<String> console = command -> {
            if (command.startsWith("boom")) {
                throw new IllegalStateException("boom");
            }
            ran.add(command);
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
            List.of("give %player% diamond"), "reward", logger(), command -> {
            });

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
}
