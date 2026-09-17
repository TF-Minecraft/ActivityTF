package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;

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

    @Test
    void aRerollReplacesAllSevenTasksAndLeavesThemUnrevealed() {
        ActivityManager manager = TestManagers.manager(defs(20));
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
        TestManagers.rerollsPerDay(manager, 0);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DISABLED, manager.reroll(uuid));

        assertNull(manager.getStore().peek(uuid), "a disabled reroll created a players.yml row");
    }

    // A disabled budget mutates nothing for a player who already has a draw
    @Test
    void aZeroBudgetMutatesNothingForAnExistingPlayer() {
        ActivityManager manager = TestManagers.manager(defs(20));
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
            TestManagers.rerollsPerDay(manager, 1);
            UUID uuid = UUID.randomUUID();
            manager.tasks(uuid);

            assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

            List<String> tasks = manager.getStore().get(uuid).tasks();
            assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
            assertTrue(tasks.contains("a3"), "a guaranteed activity was dropped by a reroll: " + tasks);
        }
    }

    // A reroll called before the player ever opened the GUI still hands out a
    // full, valid draw rather than working from an empty one
    @Test
    void aRerollWithNoPriorDrawStillProducesAFullDraw() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.rerollsPerDay(manager, 1);
        UUID uuid = UUID.randomUUID();

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(PlayerData.TASKS_PER_DAY, manager.getStore().get(uuid).tasks().size());
    }
}
