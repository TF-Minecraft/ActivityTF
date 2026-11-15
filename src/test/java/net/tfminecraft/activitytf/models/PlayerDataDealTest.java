package net.tfminecraft.activitytf.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataDealTest {

    private static List<String> ids(String prefix, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(prefix + i);
        }
        return ids;
    }

    @Test
    void moreGuaranteedTasksThanADayHoldsAreCutToSeven() {
        List<String> all = ids("g", 9);

        List<String> drawn = PlayerData.draw(all, all, List.of(), 3, new Random(1));

        assertEquals(PlayerData.TASKS_PER_DAY, drawn.size());
        assertTrue(all.containsAll(drawn));
    }

    @Test
    void sparesFromTheProfessionPoolFillADayShortOfGeneralTasks() {
        List<String> drawn = PlayerData.draw(List.of("a", "p1", "p2", "p3"), List.of(), List.of("p1", "p2", "p3"),
            1, new Random(2));

        assertEquals(Set.of("a", "p1", "p2", "p3"), Set.copyOf(drawn));
    }

    @Test
    void toppingUpDropsUnknownTasksAndStopsAtSeven() {
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of("old", "a0"), List.of("old"));
        List<String> all = ids("a", 12);

        assertTrue(data.ensureTasks(all, List.of(), List.of(), 0, id -> true, true, new Random(3)));

        assertEquals(PlayerData.TASKS_PER_DAY, data.tasks().size());
        assertFalse(data.tasks().contains("old"));
        assertTrue(data.revealed().isEmpty());
        assertFalse(data.ensureTasks(all, List.of(), List.of(), 0, id -> true, true, new Random(3)),
            "a full day changed");
    }

    @Test
    void anEmptyDayWithNothingEligibleStaysEmpty() {
        PlayerData data = new PlayerData("w", "d");

        assertFalse(data.ensureTasks(List.of("a"), List.of(), List.of(), 0, id -> false, false, new Random(4)));
    }

    @Test
    void ineligibleTasksStayWhenNotAskedToDropThem() {
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of("a", "b"), List.of());

        data.ensureTasks(List.of("a", "b"), List.of(), List.of(), 0, id -> false, false, new Random(5));

        assertEquals(List.of("a", "b"), data.tasks());
    }

    @Test
    void aRedrawKeepsGuaranteedAndStartedTasksAndCountsOnlyWhenSomethingChanged() {
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, new HashMap<>(Map.of("b", 1)),
            List.of("v", "b", "c"), List.of("v", "c"));

        assertFalse(data.redraw("v"::equals, List.of("b", "c")));
        assertEquals(0, data.rerolls());

        assertTrue(data.redraw("v"::equals, List.of("x", "y")));

        assertEquals(List.of("v", "b", "x"), data.tasks());
        assertEquals(Set.of("v"), data.revealed());
        assertEquals(1, data.rerolls());
    }

    @Test
    void markRevealedNeedsADealtTask() {
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of("a"), List.of());

        assertFalse(data.markRevealed("b"));
        assertTrue(data.markRevealed("a"));
        assertFalse(data.markRevealed("a"));
    }

    @Test
    void heldCreditAddsUpSafelyIsTakenOnceAndIsClearedByANewDayWeekOrReset() {
        PlayerData data = new PlayerData("w", "d");
        data.addPending("vote", Integer.MAX_VALUE);
        data.addPending("vote", 5);
        assertEquals(Integer.MAX_VALUE, data.pending().get("vote"));
        assertEquals(Integer.MAX_VALUE, data.takePending("vote"));
        assertEquals(0, data.takePending("vote"));

        Map<String, Integer> loaded = new HashMap<>();
        loaded.put("vote", 2);
        loaded.put("zero", 0);
        loaded.put("none", null);
        data.setPending(loaded);
        assertEquals(Map.of("vote", 2), data.pending());

        data.roll("w", "d2");
        assertTrue(data.pending().isEmpty());
        data.addPending("vote", 1);
        data.roll("w2", "d2");
        assertTrue(data.pending().isEmpty());
        data.addPending("vote", 1);
        data.reset("w2", "d2");
        assertTrue(data.pending().isEmpty());
    }
}
