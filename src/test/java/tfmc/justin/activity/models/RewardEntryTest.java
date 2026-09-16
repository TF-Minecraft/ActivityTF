package tfmc.justin.activity.models;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// ====================================
// The weighted draw itself: RewardEntry.pick walks cumulative weights.
// ActivityManagerTest already pins the common/rare boundary case; this file
// covers the shape of the draw beyond that one pool.
// ====================================
class RewardEntryTest {

    private static final RewardEntry COMMON = new RewardEntry(3, "common", List.of("give %player% diamond 3"));
    private static final RewardEntry RARE = new RewardEntry(1, "rare", List.of("give %player% netherite_ingot 1"));
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
        // weights 3 then 1: rolls 0,1,2 land on the first entry, roll 3 is the
        // first roll to cross into the second
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

    // Pinning documented behaviour: a roll below the range still walks off
    // the first cumulative threshold and lands on the first entry, since
    // "roll < cursor" is true for every negative roll too.
    @Test
    void aNegativeRollLandsOnTheFirstEntry() {
        assertEquals(COMMON, RewardEntry.pick(POOL, -1));
        assertEquals(COMMON, RewardEntry.pick(POOL, Integer.MIN_VALUE));
    }

    // Pinning documented behaviour: a roll at or beyond totalWeight (a caller
    // that rolled against a stale total) takes the last entry rather than
    // paying nothing.
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
