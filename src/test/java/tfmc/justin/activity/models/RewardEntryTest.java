package tfmc.justin.activity.models;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RewardEntryTest {

    private static final RewardEntry COMMON = new RewardEntry(3, "common",
        List.of("give %player% diamond 3"), List.of());
    private static final RewardEntry RARE = new RewardEntry(1, "rare",
        List.of("give %player% netherite_ingot 1"), List.of());
    private static final List<RewardEntry> POOL = List.of(COMMON, RARE);

    @Test
    void rollZeroDrawsTheFirstEntry() {
        assertEquals(COMMON, RewardEntry.pick(POOL, 0));
    }

    @Test
    void theLastRollDrawsTheLastEntry() {
        int total = RewardEntry.totalWeight(POOL);
        assertEquals(RARE, RewardEntry.pick(POOL, total - 1));
    }

    @Test
    void everyBoundaryRollAcrossThreeAndOneWeights() {
        assertEquals(COMMON, RewardEntry.pick(POOL, 0));
        assertEquals(COMMON, RewardEntry.pick(POOL, 1));
        assertEquals(COMMON, RewardEntry.pick(POOL, 2));
        assertEquals(RARE, RewardEntry.pick(POOL, 3));
    }

    @Test
    void aSingleEntryPoolAlwaysDrawsItRegardlessOfRoll() {
        List<RewardEntry> single = List.of(COMMON);

        assertEquals(COMMON, RewardEntry.pick(single, 0));
        assertEquals(COMMON, RewardEntry.pick(single, 2));
    }

    @Test
    void aNegativeRollLandsOnTheFirstEntry() {
        assertEquals(COMMON, RewardEntry.pick(POOL, -1));
        assertEquals(COMMON, RewardEntry.pick(POOL, Integer.MIN_VALUE));
    }

    @Test
    void aRollAtOrBeyondTheTotalWeightLandsOnTheLastEntry() {
        int total = RewardEntry.totalWeight(POOL);

        assertEquals(RARE, RewardEntry.pick(POOL, total));
        assertEquals(RARE, RewardEntry.pick(POOL, total + 100));
    }

    @Test
    void totalWeightSumsEveryEntry() {
        assertEquals(4, RewardEntry.totalWeight(POOL));
        assertEquals(0, RewardEntry.totalWeight(List.of()));
        assertEquals(3, RewardEntry.totalWeight(List.of(COMMON)));
    }

    @Test
    void anEmptyPoolPicksNullForAnyRoll() {
        assertNull(RewardEntry.pick(List.of(), 0));
        assertNull(RewardEntry.pick(List.of(), -5));
        assertNull(RewardEntry.pick(List.of(), 5));
    }
}
