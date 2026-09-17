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

// ====================================
// ActivityManager.reroll(UUID), on a real manager built without a server (see
// TestManagers). rerollsPerDay defaults to 0 on a freshly built config (load()
// itself is never run headless), so every test here sets its own budget
// explicitly through TestManagers.rerollsPerDay - a test that forgot to would
// see DISABLED everywhere and fail loudly rather than silently pass.
//
// rerollMaxPoints is the other half of that rule, defaulted the other way
// round: TestManagers ships it at 1, the production value, so a test that
// banks points and forgets to widen the gate sees TOO_LATE rather than
// silently passing on a threshold no server runs. Anything that needs more
// headroom says so with TestManagers.rerollMaxPoints.
// ====================================
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

    // ====================================
    // A forced add (/activity add --force) never touches dailyPoints, so it
    // neither trips the reroll.max-points gate nor gets handed back off the
    // weekly bar by the reroll that follows it - only the points the player
    // genuinely earned today are refunded.
    // ====================================
    @Test
    void aForcedAwardNeitherBlocksARerollNorIsRefundedByOne() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.limits(manager, 100, 10);
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        // 50 points on an activity capped at 10 a day and a budget of 10
        assertEquals(50, manager.recordAdmin(uuid, "a0", 50, true).pointsAwarded());
        PlayerData data = manager.getStore().get(uuid);
        assertEquals(50, data.points());
        // the gate reads dailyPoints, which a forced award leaves alone
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

    // ====================================
    // Once the budget is spent, NONE_LEFT must be a true no-op: not just the
    // counter, every field a reroll would otherwise touch.
    // ====================================
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

    // ====================================
    // reroll.per-day: 0 refuses everyone before ever touching the store - the
    // same shape as the DISABLED playtime.afk-minutes check, so no row is
    // created for a player who never opened the GUI.
    // ====================================
    @Test
    void aZeroBudgetIsDisabledAndCreatesNoRow() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 0);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DISABLED, manager.reroll(uuid));

        assertNull(manager.getStore().peek(uuid), "a disabled reroll created a players.yml row");
    }

    // A disabled budget mutates nothing for a player who already has a draw
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

    // ====================================
    // The reroll draw goes through PlayerData.draw the same way the normal
    // draw does, so a 'daily-guaranteed' activity is still guaranteed
    // afterwards - checked over enough repetitions to be meaningful, the same
    // way ActivityManagerTasksTest checks the ordinary draw.
    // ====================================
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

    // ====================================
    // players.yml was never read, so the store refuses every save: a reroll
    // done here would cost the player today's points only until the next
    // restart, and would hand the spent budget back with them. Refused whole,
    // the same way claim() refuses a payout it cannot persist.
    // ====================================
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

    // ====================================
    // Claim-then-reroll must not hand today's budget back for free: the
    // claimed floor keeps the points on the bar, so dailyPoints carries
    // forward instead of resetting to 0 (see PlayerData.reroll).
    //
    // A full day's earnings only reach the reroll at all on an admin who has
    // raised reroll.max-points well above the shipped 1, so the gate is opened
    // to 10 here rather than left at the default it would refuse under.
    // ====================================
    @Test
    void aRerollDoesNotRefundBudgetTheClaimedFloorKeptOnTheBar() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.rerollMaxPoints(manager, 10);
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);
        // Today's whole budget (dailyMax is 10 in TestManagers), then claimed
        data.record(10, def("a0"), 50, 10, List.of());
        data.setClaimedPoints(10);
        assertEquals(10, data.points());
        assertEquals(10, data.dailyPoints());

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(10, data.points(), "the claimed points came off the bar");
        assertEquals(10, data.dailyPoints(), "the reroll handed today's budget back for free");
    }

    // ====================================
    // The same floor at the shipped reroll.max-points: 1, the only threshold
    // on which PlayerData.reroll's carry-forward is still reachable by a
    // default server. One claimed point is all a player can have banked and
    // still reroll, and it must stay banked.
    // ====================================
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

    // A reroll called before the player ever opened the GUI still hands out a
    // full, valid draw rather than working from an empty one
    @Test
    void aRerollWithNoPriorDrawStillProducesAFullDraw() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(PlayerData.TASKS_PER_DAY, manager.getStore().get(uuid).tasks().size());
    }

    // ====================================
    // reroll.max-points is an inclusive ceiling: banked points equal to it
    // still reroll, one more refuses.
    // ====================================
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

    // ====================================
    // The gate reads dailyPoints after the rollover has been applied, so
    // yesterday's earnings never block today's reroll.
    //
    // Midnight is modelled by backdating the row to the real previous day
    // key rather than by inventing one: what makes the second click work is
    // store.get rolling the row forward onto the key currentKeys() reports,
    // so the test asserts the row landed on exactly that key.
    // ====================================
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

    // max-points: 0 - only a player who has earned nothing today may reroll
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

    // ====================================
    // Both blockers at once: the point gate wins. The threshold holds for the
    // rest of the day whatever the budget says, so it is the useful answer.
    // ====================================
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
