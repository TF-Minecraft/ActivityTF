package tfmc.justin.activity.commands;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.commands.ActivityCommand.Outcome;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// /activity add is the console intake path (ConditionalEvents and friends), so
// it goes through the same daily-task gate a listener does; only an explicit
// trailing --force skips it. onCommand itself needs a live server to resolve a
// player, so what is checked here is the one argument that decides which of
// the two record paths add takes, plus that the usage strings say so.
// ====================================
class ActivityCommandTest {

    private static String[] add(String... trailing) {
        String[] args = new String[4 + trailing.length];
        args[0] = "add";
        args[1] = "Justin";
        args[2] = "vote";
        args[3] = "1";
        System.arraycopy(trailing, 0, args, 4, trailing.length);
        return args;
    }

    @Test
    void addIsGatedUnlessForceIsAskedFor() {
        assertFalse(ActivityCommand.forced(add()));
        assertTrue(ActivityCommand.forced(add("--force")));
    }

    @Test
    void forceIsCaseInsensitiveAndNothingElseCountsAsIt() {
        assertTrue(ActivityCommand.forced(add("--FORCE")));
        assertFalse(ActivityCommand.forced(add("force")));
        assertFalse(ActivityCommand.forced(add("-f")));
        assertFalse(ActivityCommand.forced(add("")));
    }

    @Test
    void theUsageStringsDocumentForce() {
        String usage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"))
            .getString("admin.usage", "");
        assertTrue(usage.contains("--force"), usage);

        String pluginUsage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/plugin.yml"))
            .getString("commands.activity.usage", "");
        assertTrue(pluginUsage.contains("--force"), pluginUsage);
    }

    @Test
    void nothingButOneTrailingForceIsAWellFormedAdd() {
        assertTrue(ActivityCommand.wellFormedAdd(add()));
        assertTrue(ActivityCommand.wellFormedAdd(add("--force")));

        assertFalse(ActivityCommand.wellFormedAdd(add("--froce")));
        assertFalse(ActivityCommand.wellFormedAdd(add("-force")));
        assertFalse(ActivityCommand.wellFormedAdd(add("force")));
        assertFalse(ActivityCommand.wellFormedAdd(add("")));
        assertFalse(ActivityCommand.wellFormedAdd(add("--force", "extra")));
        assertFalse(ActivityCommand.wellFormedAdd(add("--force", "extra", "junk")));
        assertFalse(ActivityCommand.wellFormedAdd(new String[] {"add", "Justin", "vote"}));
    }

    // ====================================
    // The add routing, on a real manager built without a server (see
    // TestManagers). Every activity is 'every: 2' and one action is added, so
    // no point is ever awarded and the record path stays away from
    // Bukkit.getPlayer(), unreachable headless. The witness for "credited" is
    // the stored action count.
    // ====================================
    private static ActivityCommand command(ActivityManager manager) {
        return new ActivityCommand(manager, null);
    }

    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("vote", "Vote", Material.PAPER, null, 2, 1, 0));
    }

    @Test
    void aGatedAddToATaskThatIsNotRevealedCreditsNothingAndSaysSo() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false));
        // Not even a row: a gated add must not pin an offline player in the file
        assertNull(manager.getStore().peek(player));
    }

    @Test
    void aGatedAddToADrawnButHiddenTaskCreditsNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false));
        assertEquals(0, manager.getStore().get(player).count("vote"));
    }

    @Test
    void aGatedAddToARevealedTaskIsCredited() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, false));
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void forceCreditsATaskThatWasNeverDrawnOrRevealed() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, true));
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void anActivityNobodyLoadedIsUnknownEitherWay() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, false));
        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, true));
    }

    // Each outcome has to have something to say, and it has to be in the
    // shipped file - a missing key reaches the admin as a raw path
    @Test
    void everyOutcomeHasAShippedMessage() {
        YamlConfiguration messages = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"));

        assertEquals("admin.add-done", Outcome.ADDED.messageKey());
        assertEquals("admin.add-not-a-task", Outcome.NOT_A_TASK.messageKey());
        assertEquals("admin.unknown-activity", Outcome.UNKNOWN_ACTIVITY.messageKey());

        for (Outcome outcome : Outcome.values()) {
            String value = messages.getString(outcome.messageKey());
            assertFalse(value == null || value.isBlank(), outcome.messageKey() + " is missing");
        }

        String notATask = messages.getString("admin.add-not-a-task");
        assertTrue(notATask.contains("%activity%"), notATask);
        assertTrue(notATask.contains("%player%"), notATask);
        assertTrue(notATask.contains("--force"), notATask);
    }
}
