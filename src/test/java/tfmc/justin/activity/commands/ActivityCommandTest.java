package tfmc.justin.activity.commands;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.commands.ActivityCommand.Outcome;
import tfmc.justin.activity.gui.ActivityGui;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.RewardEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // The flag is exact: the one argument that decides whether the gate runs
    // is not left to a case-insensitive guess at what was meant
    @Test
    void onlyTheExactFlagCountsAsForce() {
        assertFalse(ActivityCommand.forced(add("--FORCE")));
        assertFalse(ActivityCommand.forced(add("--Force")));
        assertFalse(ActivityCommand.forced(add("force")));
        assertFalse(ActivityCommand.forced(add("-f")));
        assertFalse(ActivityCommand.forced(add("")));

        // ...and a miscased flag is a usage error, not a quiet gated add
        assertFalse(ActivityCommand.wellFormedAdd(add("--FORCE")));
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

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false).outcome());
        // Not even a row: a gated add must not pin an offline player in the file
        assertNull(manager.getStore().peek(player));
    }

    @Test
    void aGatedAddToADrawnButHiddenTaskCreditsNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false).outcome());
        assertEquals(0, manager.getStore().get(player).count("vote"));
    }

    @Test
    void aGatedAddToARevealedTaskIsCredited() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, false).outcome());
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void forceCreditsATaskThatWasNeverDrawnOrRevealed() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, true).outcome());
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void anActivityNobodyLoadedIsUnknownEitherWay() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, false).outcome());
        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, true).outcome());
    }

    // ====================================
    // A cap that swallows the points is not a success: the count goes in, no
    // point reaches the bar, and the admin is told which cap did it. Set up
    // by lowering the limit, since awarding a point headlessly would reach
    // Bukkit.getPlayer.
    // ====================================
    @Test
    void aSpentDailyBudgetIsReportedRatherThanCountedAsAdded() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0));
        TestManagers.limits(manager, 50, 0);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.CAPPED_DAILY, command(manager).add(player, "vote", 5, false).outcome());
        // The count still went in - only the points were lost
        assertEquals(5, manager.getStore().get(player).count("vote"));
        assertEquals(0, manager.getStore().get(player).points());
    }

    @Test
    void aFullWeeklyBarIsReportedRatherThanCountedAsAdded() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0));
        TestManagers.limits(manager, 0, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.CAPPED_WEEKLY, command(manager).add(player, "vote", 5, false).outcome());
        assertEquals(0, manager.getStore().get(player).points());
    }

    @Test
    void anActivityThatHasGivenAllItCanTodayIsReported() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 1));
        // A full bar keeps the point off it, so the cap can be reached headless
        TestManagers.limits(manager, 0, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        ActivityCommand command = command(manager);

        // The first add is what meets the activity's own daily cap of 1
        assertEquals(Outcome.CAPPED_WEEKLY, command.add(player, "vote", 1, false).outcome());
        assertEquals(Outcome.CAPPED_ACTIVITY, command.add(player, "vote", 1, false).outcome());
    }

    // ====================================
    // What --force is for: the shipped vote activity (every: 1, points: 1,
    // daily-cap: 5) with bar.daily-max 10, credited 50. Both limits are
    // ignored and all 50 points land. Awarding a point reaches
    // Bukkit.getPlayer, so the server stub goes in first.
    // ====================================
    @Test
    void forceAwardsTheFullWorthPastTheActivityCapAndTheDailyBudget() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 100, 10);
        UUID player = UUID.randomUUID();

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, true);

        assertEquals(Outcome.ADDED, added.outcome());
        assertEquals(50, added.points());
        assertEquals(50, manager.getStore().get(player).points());
        assertEquals(50, manager.getStore().get(player).count("vote"));
        assertEquals(0, manager.getStore().get(player).dailyPoints());
    }

    // The same add unforced: the activity cap of 5 is what lands, and the
    // daily budget of 10 would have held it to 10 anyway
    @Test
    void theSameAddUnforcedIsStillHeldToTheActivityCap() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 100, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, false);

        assertEquals(Outcome.ADDED, added.outcome());
        assertEquals(5, added.points());
        assertEquals(5, manager.getStore().get(player).points());
        assertEquals(5, manager.getStore().get(player).dailyPoints());
    }

    // bar.max is the one limit --force does not bypass: a bar that cannot hold
    // the award is reported as clamped rather than as a plain success
    @Test
    void forceStillStopsAtTheWeeklyMaximum() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 10, 10);
        UUID player = UUID.randomUUID();

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, true);

        assertEquals(Outcome.CLAMPED_WEEKLY, added.outcome());
        assertEquals(10, added.points());
        assertEquals(10, manager.getStore().get(player).points());

        // ...and a second forced add onto the full bar has nothing to clamp
        ActivityCommand.Added again = command(manager).add(player, "vote", 5, true);
        assertEquals(Outcome.CAPPED_WEEKLY, again.outcome());
        assertEquals(0, again.points());
    }

    // A count part-way to its next point is a plain success, not a cap
    @Test
    void partialProgressTowardsTheNextPointIsStillAdded() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, true).outcome());
    }

    // Each outcome has to have something to say, and it has to be in the
    // shipped file - a missing key reaches the admin as a raw path
    @Test
    void everyOutcomeHasAShippedMessage() {
        YamlConfiguration messages = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"));

        assertEquals("admin.add-done", Outcome.ADDED.messageKey());
        assertEquals("admin.add-not-a-task", Outcome.NOT_A_TASK.messageKey());
        assertEquals("admin.add-capped-activity", Outcome.CAPPED_ACTIVITY.messageKey());
        assertEquals("admin.add-capped-daily", Outcome.CAPPED_DAILY.messageKey());
        assertEquals("admin.add-capped-weekly", Outcome.CAPPED_WEEKLY.messageKey());
        assertEquals("admin.add-clamped-weekly", Outcome.CLAMPED_WEEKLY.messageKey());
        assertEquals("admin.unknown-activity", Outcome.UNKNOWN_ACTIVITY.messageKey());

        for (Outcome outcome : Outcome.values()) {
            String value = messages.getString(outcome.messageKey());
            assertFalse(value == null || value.isBlank(), outcome.messageKey() + " is missing");
        }

        // The points figure rides on its own key, not on admin.add-done: that
        // key predates the figure, so a live messages.yml would win over the
        // packaged default and hide it
        String plain = messages.getString("admin.add-done");
        assertFalse(plain == null || plain.contains("%points%"), plain);
        String withPoints = messages.getString("admin.add-done-points");
        assertTrue(withPoints != null && withPoints.contains("%points%"), withPoints);

        String notATask = messages.getString("admin.add-not-a-task");
        assertTrue(notATask.contains("%activity%"), notATask);
        assertTrue(notATask.contains("%player%"), notATask);
        assertTrue(notATask.contains("--force"), notATask);
    }

    // ====================================
    // /activity asks for the daily reward on open, which is how a failed one
    // is retried. build() needs a live server, so the GUI hands back no
    // window; the reward is an m. path with no TLibs behind it, so the
    // attempt shows as a refusal in chat - the proof the open asked for it.
    // ====================================
    @Test
    void openingTheMenuAsksForTheDailyReward() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("a", "a", Material.PAPER, null, 1, 1, 0));
        TestManagers.messages(manager);
        TestManagers.dailyRewards(manager, Map.of("vip", new RewardEntry(1, "Steel", List.of(),
            List.of(new RewardEntry.Item("m.material.steel", 1)))));
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);
        List<String> chat = new ArrayList<>();
        Player player = TestManagers.player(uuid, Set.of("activity.use", "group.vip"), chat);
        ActivityGui gui = new ActivityGui(manager) {
            @Override
            public Inventory build(Player who) {
                return null;
            }
        };

        new ActivityCommand(manager, gui).onCommand(player, null, "activity", new String[0]);

        assertTrue(chat.stream().anyMatch(line -> line.contains("could not be handed over")), chat.toString());
    }
}
