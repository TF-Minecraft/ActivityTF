package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Weeks;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityManagerRerollTest {

    private static ActivityDef def(String id) {
        return new ActivityDef(id, id, Material.PAPER, null, 1, 1, 0);
    }

    private static ActivityDef[] defs(int count) {
        ActivityDef[] defs = new ActivityDef[count];
        for (int i = 0; i < count; i++) {
            defs[i] = def("a" + i);
        }
        return defs;
    }

    @Test
    void aForcedAwardNeitherBlocksARerollNorIsRefundedByOne() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.limits(manager, 100, 10);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertEquals(50, manager.recordAdmin(uuid, "a0", 50, true).pointsAwarded());
        PlayerData data = manager.getStore().get(uuid);
        assertEquals(50, data.points());
        assertEquals(0, data.dailyPoints());

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertEquals(50, data.points(), "the reroll refunded points nobody earned today");
    }

    @Test
    void aRerollReplacesAllSevenTasksAndLeavesThemUnrevealed() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);
        manager.reveal(uuid, 0);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        PlayerData data = manager.getStore().get(uuid);
        assertEquals(PlayerData.TASKS_PER_DAY, data.tasks().size());
        assertEquals(data.tasks().size(), new HashSet<>(data.tasks()).size(), "the new draw repeats an id");
        assertTrue(data.revealed().isEmpty(), "a task came back already revealed");
        assertTrue(data.daily().isEmpty(), "the daily count map was not cleared");
        assertEquals(0, data.dailyPoints());
    }

    @Test
    void theRerollCounterIncrementsOnASuccessfulReroll() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 3);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        manager.reroll(uuid);

        assertEquals(1, manager.getStore().get(uuid).rerolls());
    }

    @Test
    void exhaustingTheBudgetReturnsNoneLeftAndMutatesNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        PlayerData data = manager.getStore().get(uuid);
        List<String> tasksAfterFirst = List.copyOf(data.tasks());
        int pointsAfterFirst = data.points();
        int dailyPointsAfterFirst = data.dailyPoints();
        int rerollsAfterFirst = data.rerolls();
        Set<String> revealedAfterFirst = Set.copyOf(data.revealed());

        assertEquals(ActivityManager.Rerolled.NONE_LEFT, manager.reroll(uuid));

        assertEquals(tasksAfterFirst, data.tasks(), "NONE_LEFT changed the draw");
        assertEquals(pointsAfterFirst, data.points(), "NONE_LEFT changed the weekly bar");
        assertEquals(dailyPointsAfterFirst, data.dailyPoints(), "NONE_LEFT changed daily points");
        assertEquals(rerollsAfterFirst, data.rerolls(), "NONE_LEFT changed the counter");
        assertEquals(revealedAfterFirst, data.revealed(), "NONE_LEFT changed revealed flags");
    }

    @Test
    void aZeroBudgetIsDisabledAndCreatesNoRow() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 0);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DISABLED, manager.reroll(uuid));

        assertNull(manager.getStore().peek(uuid), "a disabled reroll created a players.yml row");
    }

    @Test
    void aZeroBudgetMutatesNothingForAnExistingPlayer() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 0);
        UUID uuid = UUID.randomUUID();
        List<String> before = List.copyOf(manager.tasks(uuid).tasks());
        int pointsBefore = manager.getStore().get(uuid).points();

        assertEquals(ActivityManager.Rerolled.DISABLED, manager.reroll(uuid));

        PlayerData data = manager.getStore().get(uuid);
        assertEquals(before, data.tasks());
        assertEquals(pointsBefore, data.points());
        assertEquals(0, data.rerolls());
    }

    @Test
    void aBudgetAboveOneAllowsExactlyThatManyPerDay() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 3);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertEquals(ActivityManager.Rerolled.NONE_LEFT, manager.reroll(uuid));

        assertEquals(3, manager.getStore().get(uuid).rerolls());
    }

    @Test
    void aGuaranteedActivitySurvivesARerollEveryTime() {
        for (int i = 0; i < 200; i++) {
            ActivityManager manager = TestManagers.manager(defs(20));
            TestManagers.guarantee(manager, "a3");
            TestManagers.storeLoaded(manager);
            TestManagers.rerollsPerDay(manager, 1);
            UUID uuid = UUID.randomUUID();
            manager.tasks(uuid);

            assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

            List<String> tasks = manager.getStore().get(uuid).tasks();
            assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
            assertTrue(tasks.contains("a3"), "a guaranteed activity was dropped by a reroll: " + tasks);
        }
    }

    @Test
    void aStoreThatNeverLoadedRefusesTheRerollAndMutatesNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();
        List<String> before = List.copyOf(manager.tasks(uuid).tasks());

        assertEquals(ActivityManager.Rerolled.FAILED, manager.reroll(uuid));

        PlayerData data = manager.getStore().get(uuid);
        assertEquals(before, data.tasks(), "a refused reroll changed the draw");
        assertEquals(0, data.rerolls(), "a refused reroll spent the budget");
    }

    @Test
    void aRerollDoesNotRefundBudgetTheClaimedFloorKeptOnTheBar() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 10);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        data.record(10, def("a0"), 50, 10, List.of());
        data.setClaimedPoints(10);
        assertEquals(10, data.points());
        assertEquals(10, data.dailyPoints());

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(10, data.points(), "the claimed points came off the bar");
        assertEquals(10, data.dailyPoints(), "the reroll handed today's budget back for free");
    }

    @Test
    void theClaimedFloorStillBlocksTheRefundAtTheShippedThreshold() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 1);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        data.record(1, def("a0"), 50, 10, List.of());
        data.setClaimedPoints(1);
        assertEquals(1, data.points());
        assertEquals(1, data.dailyPoints());

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(1, data.points(), "the claimed point came off the bar");
        assertEquals(1, data.dailyPoints(), "the reroll handed today's budget back for free");
    }

    @Test
    void aRerollWithNoPriorDrawStillProducesAFullDraw() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(PlayerData.TASKS_PER_DAY, manager.getStore().get(uuid).tasks().size());
    }

    @Test
    void dailyPointsExactlyAtTheThresholdStillRerolls() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 1);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        data.record(1, def("a0"), 50, 10, List.of());
        assertEquals(1, data.dailyPoints());

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
    }

    @Test
    void onePointAboveTheThresholdIsTooLateAndMutatesNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 1);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        manager.reveal(uuid, 0);
        data.record(2, def("a0"), 50, 10, List.of());
        assertEquals(2, data.dailyPoints());
        List<String> tasksBefore = List.copyOf(data.tasks());
        Set<String> revealedBefore = Set.copyOf(data.revealed());
        int pointsBefore = data.points();

        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(uuid));

        assertEquals(tasksBefore, data.tasks(), "TOO_LATE changed the draw");
        assertEquals(revealedBefore, data.revealed(), "TOO_LATE changed revealed flags");
        assertEquals(pointsBefore, data.points(), "TOO_LATE changed the weekly bar");
        assertEquals(2, data.dailyPoints(), "TOO_LATE changed daily points");
        assertEquals(0, data.rerolls(), "TOO_LATE spent the budget");
    }

    @Test
    void aPlayerRefusedYesterdayMayRerollAfterTheDayRolls() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 1);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        data.record(5, def("a0"), 50, 10, List.of());

        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(uuid));

        String today = manager.getConfiguration().currentKeys().day();
        data.roll(data.weekKey(), Weeks.dayKey(LocalDate.parse(today).minusDays(1)));

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertEquals(today, data.dayKey(), "the reroll ran without the day rolling forward");
    }

    @Test
    void aZeroThresholdAllowsOnlyAPlayerWhoHasEarnedNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 2);
        TestManagers.rerollMaxPoints(manager, 0);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        data.record(1, def("a0"), 50, 10, List.of());

        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(uuid));
    }

    @Test
    void theThresholdIsReportedBeforeAnExhaustedBudget() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 1);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        data.record(2, def("a0"), 50, 10, List.of());

        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(uuid));
    }
}
