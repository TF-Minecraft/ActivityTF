package tfmc.justin.activity.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    private static final String WEEK = "2026-09-07";
    private static final String DAY = "2026-09-09";
    private static final int MAX = 20;
    private static final int DAILY_MAX = 1000;
    private static final List<Integer> MILESTONES = List.of(10, 20);

    private static final ActivityDef VOTE = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5);
    private static final ActivityDef QUEST = new ActivityDef("quest", "Quest", Material.BOOK, null, 1, 1, 5);
    private static final ActivityDef INSTRUMENT =
        new ActivityDef("instrument", "Notes", Material.NOTE_BLOCK, null, 20, 1, 1);
    private static final ActivityDef UNCAPPED = new ActivityDef("free", "Free", Material.STONE, null, 1, 1, 0);

    private PlayerData data() {
        return new PlayerData(WEEK, DAY);
    }

    private RecordResult record(PlayerData data, ActivityDef def, int amount) {
        return data.record(amount, def, MAX, DAILY_MAX, MILESTONES);
    }

    @Test
    void eachActionIsWorthItsPoints() {
        PlayerData data = data();

        RecordResult result = record(data, VOTE, 1);

        assertEquals(1, result.pointsAwarded());
        assertEquals(1, data.points());
    }

    @Test
    void dailyCapStopsFurtherAwardsButStillCounts() {
        PlayerData data = data();

        record(data, VOTE, 5);
        RecordResult sixth = record(data, VOTE, 1);

        assertEquals(0, sixth.pointsAwarded());
        assertEquals(5, data.points());
        assertEquals(6, data.count("vote"));
    }

    @Test
    void everyGroupsActionsIntoOneAward() {
        PlayerData data = data();

        assertEquals(0, record(data, INSTRUMENT, 19).pointsAwarded());
        assertEquals(1, record(data, INSTRUMENT, 1).pointsAwarded());
        assertEquals(0, record(data, INSTRUMENT, 20).pointsAwarded());
        assertEquals(1, data.points());
    }

    @Test
    void uncappedActivityKeepsAwarding() {
        PlayerData data = data();

        record(data, UNCAPPED, 15);

        assertEquals(15, data.points());
    }

    @Test
    void twoDaysOfVotingAndQuestsMaxTheBar() {
        PlayerData data = data();

        record(data, VOTE, 5);
        record(data, QUEST, 5);
        data.roll(WEEK, "2026-09-10");
        record(data, VOTE, 5);
        record(data, QUEST, 5);

        assertEquals(MAX, data.points());
        assertEquals(2, data.claimable(MILESTONES));
    }

    @Test
    void milestonesFireOnlyOnTheCrossing() {
        PlayerData data = data();

        assertEquals(0, record(data, UNCAPPED, 9).milestonesReached());
        assertEquals(1, record(data, UNCAPPED, 1).milestonesReached());
        assertEquals(0, record(data, UNCAPPED, 5).milestonesReached());
        assertEquals(1, record(data, UNCAPPED, 5).milestonesReached());
    }

    @Test
    void oneCallCanCrossTwoMilestones() {
        PlayerData data = data();

        assertEquals(2, record(data, UNCAPPED, 20).milestonesReached());
        assertEquals(2, data.claimable(MILESTONES));
    }

    @Test
    void pointsClampAtMaxAndAwardedReflectsTheClampedDelta() {
        PlayerData data = data();
        data.addPoints(18, MAX);

        RecordResult result = record(data, UNCAPPED, 5);

        assertEquals(2, result.pointsAwarded());
        assertEquals(MAX, data.points());
        assertEquals(0, record(data, UNCAPPED, 5).pointsAwarded());
    }

    @Test
    void claimingRemovesFromClaimable() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);

        data.setClaimedPoints(10);

        assertEquals(0, data.claimable(MILESTONES));
        record(data, UNCAPPED, 10);
        assertEquals(1, data.claimable(MILESTONES));
    }

    // A paid milestone stays paid: adding milestones below it must not make
    // what was already handed over claimable again
    @Test
    void addingLowerMilestonesDoesNotReviveAPaidClaim() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints(10);

        assertEquals(0, data.claimable(List.of(5, 10, 15, 20)));
    }

    @Test
    void addPointsClampsNegativeValuesAtZero() {
        PlayerData data = data();

        data.addPoints(-50, MAX);

        assertEquals(0, data.points());
    }

    @Test
    void aHugeAmountSaturatesInsteadOfWrappingNegative() {
        PlayerData data = data();

        record(data, VOTE, Integer.MAX_VALUE);
        RecordResult again = record(data, VOTE, Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, data.count("vote"));
        assertEquals(0, again.pointsAwarded());
        assertEquals(5, data.points());
    }

    @Test
    void worthDoesNotOverflowOnHugeCounts() {
        ActivityDef rich = new ActivityDef("rich", "Rich", Material.STONE, null, 1, 1_000_000, 0);

        assertEquals(Integer.MAX_VALUE, rich.worth(Integer.MAX_VALUE));
    }

    @Test
    void clampPullsLegacyPointsDownToTheBar() {
        PlayerData data = new PlayerData(100, 0, WEEK, DAY, 0, java.util.Map.of());

        assertTrue(data.clamp(MAX));
        assertEquals(MAX, data.points());
        assertEquals(2, data.claimable(MILESTONES));
        assertFalse(data.clamp(MAX));
    }

    @Test
    void newWeekWipesEverything() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints(10);

        assertTrue(data.roll("2026-09-14", "2026-09-14"));

        assertEquals(0, data.points());
        assertEquals(0, data.claimedPoints());
        assertEquals(0, data.count("free"));
    }

    @Test
    void newDayWipesOnlyTheDailyCounters() {
        PlayerData data = data();
        record(data, VOTE, 5);
        data.setClaimedPoints(10);

        assertTrue(data.roll(WEEK, "2026-09-10"));

        assertEquals(5, data.points());
        assertEquals(10, data.claimedPoints());
        assertEquals(0, data.count("vote"));
    }

    @Test
    void sameWeekAndDayChangeNothing() {
        PlayerData data = data();
        record(data, VOTE, 1);

        assertFalse(data.roll(WEEK, DAY));

        assertEquals(1, data.count("vote"));
        assertEquals(1, data.points());
    }

    @Test
    void resetClearsTheWeekAndMovesTheKeys() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints(10);

        data.reset("2026-09-14", "2026-09-14");

        assertEquals(0, data.points());
        assertEquals(0, data.claimedPoints());
        assertEquals(0, data.count("free"));
        assertEquals("2026-09-14", data.weekKey());
    }

    @Test
    void claimableCountsOnlyTheMilestonesReachedAndNotYetPaid() {
        PlayerData data = data();
        record(data, UNCAPPED, 15);
        data.setClaimedPoints(10);

        // 15 has reached 5, 10 and 15; 5 and 10 are already paid for
        assertEquals(1, data.claimable(List.of(5, 10, 15, 20)));
    }

    @Test
    void droppingMilestonesAfterAFullClaimDoesNotReviveIt() {
        PlayerData data = data();
        record(data, UNCAPPED, MAX);
        // Claimed everything: the highest milestone on the bar is burned
        data.setClaimedPoints(MAX);

        assertEquals(0, data.claimable(List.of(20)));
    }

    @Test
    void milestonesBelowAFullClaimAreNotReviveable() {
        PlayerData data = data();
        record(data, UNCAPPED, MAX);
        data.setClaimedPoints(MAX);

        // Every one of them is at or under what was already paid for
        assertEquals(0, data.claimable(List.of(7, 14)));
    }

    @Test
    void changingMilestonesStillExposesAReachableUnclaimedOne() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints(10);
        record(data, UNCAPPED, 7);

        // points=17, claimed=10: 14 has been reached and never paid for
        assertEquals(1, data.claimable(List.of(7, 14)));
    }

    // ====================================
    // claimable() is what is left unpaid, not everything the bar has reached:
    // a player who has already been paid for part of the bar must be owed only
    // the rest, or every claim would hand out the whole bar again.
    // ====================================
    @Test
    void partlyClaimedPointsOnlyOweTheRemainder() {
        PlayerData data = new PlayerData(20, 0, WEEK, DAY, 10, java.util.Map.of());

        // 15 and 20 are left; 5 and 10 were paid for
        assertEquals(2, data.claimable(List.of(5, 10, 15, 20)));
    }

    @Test
    void pointsShortOfAMilestoneDoNotOweIt() {
        PlayerData data = new PlayerData(45, 0, WEEK, DAY, 0, java.util.Map.of());

        // 20 and 40 are reached; the last 5 points fall short of 60
        assertEquals(2, data.claimable(List.of(20, 40, 60)));
    }

    @Test
    void claimableNeverGoesNegativeWhenClaimedPointsExceedsPoints() {
        PlayerData data = data();
        record(data, UNCAPPED, 5);

        data.setClaimedPoints(100);

        assertEquals(0, data.claimable(MILESTONES));
    }

    // Points past the daily max are lost: they must not reach the weekly bar
    // today, nor leak into it once the day rolls over.
    @Test
    void pointsPastTheDailyMaxAreLost() {
        PlayerData data = data();

        assertEquals(3, data.record(36, UNCAPPED, MAX, 3, MILESTONES).pointsAwarded());
        assertEquals(3, data.dailyPoints());
        assertEquals(3, data.points());

        assertEquals(0, data.record(10, UNCAPPED, MAX, 3, MILESTONES).pointsAwarded());
        assertEquals(3, data.points());

        data.roll(WEEK, "2026-09-10");
        assertEquals(0, data.dailyPoints());
        assertEquals(3, data.record(25, UNCAPPED, MAX, 3, MILESTONES).pointsAwarded());
        assertEquals(6, data.points());
    }

    // Product-owner example: dailyMax 10, barMax large enough to never bind.
    // Earning "36 points" worth on day one and "25 points" worth on day two
    // must land on weekly points 20 (10 + 10) and dailyPoints 10 (today only).
    @Test
    void productOwnerExampleTwoDaysOfThirtySixAndTwentyFive() {
        PlayerData data = data();
        int hugeMax = 1_000_000;
        int dailyMax = 10;

        RecordResult day1 = data.record(36, UNCAPPED, hugeMax, dailyMax, MILESTONES);
        assertEquals(10, day1.pointsAwarded());
        assertEquals(10, data.points());
        assertEquals(10, data.dailyPoints());

        data.roll(WEEK, "2026-09-10");

        RecordResult day2 = data.record(25, UNCAPPED, hugeMax, dailyMax, MILESTONES);
        assertEquals(10, day2.pointsAwarded());
        assertEquals(20, data.points());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void partiallyRemainingBudgetTrimsTheAward() {
        PlayerData data = data();
        int dailyMax = 10;

        data.record(8, UNCAPPED, MAX, dailyMax, MILESTONES);
        RecordResult result = data.record(5, UNCAPPED, MAX, dailyMax, MILESTONES);

        assertEquals(2, result.pointsAwarded());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void exhaustedBudgetAwardsNothingAndNoMilestone() {
        PlayerData data = data();
        int dailyMax = 10;

        data.record(10, UNCAPPED, MAX, dailyMax, MILESTONES);
        RecordResult result = data.record(5, UNCAPPED, MAX, dailyMax, MILESTONES);

        assertEquals(0, result.pointsAwarded());
        assertEquals(0, result.milestonesReached());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void dayRollResetsDailyPointsButKeepsWeeklyPoints() {
        PlayerData data = data();
        data.record(10, UNCAPPED, MAX, 10, MILESTONES);

        data.roll(WEEK, "2026-09-10");

        assertEquals(0, data.dailyPoints());
        assertEquals(10, data.points());
    }

    @Test
    void weekRollResetsBothWeeklyAndDailyPoints() {
        PlayerData data = data();
        data.record(10, UNCAPPED, MAX, 10, MILESTONES);

        data.roll("2026-09-14", "2026-09-14");

        assertEquals(0, data.dailyPoints());
        assertEquals(0, data.points());
    }

    // Milestones must reflect what actually reached the weekly bar, not the
    // raw earned amount before the daily clamp trims it: a raw 20 would cross
    // two milestones (10 and 20), but a dailyMax of 5 trims it to 5.
    @Test
    void milestonesAreComputedFromTheDailyClampedAmount() {
        PlayerData data = data();

        RecordResult result = data.record(20, UNCAPPED, 1_000_000, 5, MILESTONES);

        assertEquals(5, result.pointsAwarded());
        assertEquals(0, result.milestonesReached());
    }

    @Test
    void dailyMaxLargerThanBarMaxStillClampsWeeklyAtBarMax() {
        PlayerData data = data();
        int barMax = 5;

        RecordResult result = data.record(20, UNCAPPED, barMax, 1000, MILESTONES);

        assertEquals(barMax, data.points());
        assertEquals(barMax, result.pointsAwarded());
    }

    // Pinning documented behaviour: dailyPoints tracks only the amount that
    // actually landed on the weekly total, not the (possibly larger) amount
    // earned before the weekly bar clamped it. A weekly-clamped award only
    // consumes as much daily budget as reached the bar.
    @Test
    void weeklyClampedAwardConsumesOnlyWhatReachedTheBar() {
        PlayerData data = data();
        int barMax = 5;

        RecordResult result = data.record(20, UNCAPPED, barMax, 1000, MILESTONES);

        assertEquals(barMax, data.points());
        assertEquals(barMax, result.pointsAwarded());
        assertEquals(5, data.dailyPoints());
    }

    @Test
    void zeroDailyBudgetAwardsNothing() {
        PlayerData data = data();

        RecordResult result = data.record(5, UNCAPPED, MAX, 0, MILESTONES);

        assertEquals(0, result.pointsAwarded());
        assertEquals(0, data.dailyPoints());
    }

    // dailyPoints already past dailyMax (e.g. read off disk after dailyMax was
    // lowered) must not drive the remaining budget negative and give an award
    // out of thin air.
    @Test
    void dailyPointsAlreadyOverTheLoweredMaxNeverGoesNegativeOrAwards() {
        PlayerData data = new PlayerData(0, 15, WEEK, DAY, 0, java.util.Map.of());

        RecordResult result = data.record(5, UNCAPPED, MAX, 10, MILESTONES);

        assertEquals(0, result.pointsAwarded());
        assertEquals(15, data.dailyPoints());
    }

    // ====================================
    // PlayerData.due: the static helper claim() and claimable() both go
    // through. Milestones [10, 20] throughout.
    // ====================================
    @Test
    void pointsExactlyOnAMilestoneOwesIt() {
        assertEquals(List.of(10), PlayerData.due(10, 0, MILESTONES));
    }

    @Test
    void reachingTheSecondMilestoneOwesOnlyWhatWasNotYetClaimed() {
        assertEquals(List.of(20), PlayerData.due(20, 10, MILESTONES));
    }

    // Config order is not guaranteed ascending; due() must sort its own
    // output regardless of the order milestones are given in.
    @Test
    void dueReturnsAscendingEvenWhenMilestonesAreUnsorted() {
        assertEquals(List.of(10, 20), PlayerData.due(30, 0, List.of(20, 10)));
    }

    @Test
    void everythingClaimedOwesNothingEvenWithPointsToSpare() {
        assertEquals(List.of(), PlayerData.due(50, 20, MILESTONES));
    }

    // A stale claimedPoints above the current points (e.g. milestones edited
    // down after a claim) must owe nothing, not a negative count
    @Test
    void claimedAboveCurrentPointsOwesNothingAndNeverGoesNegative() {
        assertEquals(List.of(), PlayerData.due(5, 100, MILESTONES));
    }

    @Test
    void anEmptyMilestoneListIsNeverDueAndNeverCountsAsReached() {
        PlayerData data = data();

        assertEquals(List.of(), PlayerData.due(100, 0, List.of()));
        assertEquals(0, data.claimable(List.of()));
        assertEquals(0, data.record(25, UNCAPPED, MAX, DAILY_MAX, List.of()).milestonesReached());
    }

    @Test
    void oneRecordCallJumpingPastBothMilestonesCountsBothAsReached() {
        PlayerData data = data();

        RecordResult result = data.record(25, UNCAPPED, 1_000_000, 1_000_000, MILESTONES);

        assertEquals(2, result.milestonesReached());
    }

    // The same two milestones must come due regardless of how the points were
    // earned: 10 in one day then 10 the next, versus 5 a day for four days.
    @Test
    void theSameTwoMilestonesComeDueRegardlessOfPace() {
        PlayerData playerA = new PlayerData(WEEK, DAY);
        record(playerA, UNCAPPED, 10);
        playerA.roll(WEEK, "2026-09-10");
        record(playerA, UNCAPPED, 10);

        PlayerData playerB = new PlayerData(WEEK, DAY);
        record(playerB, UNCAPPED, 5);
        playerB.roll(WEEK, "2026-09-10");
        record(playerB, UNCAPPED, 5);
        playerB.roll(WEEK, "2026-09-11");
        record(playerB, UNCAPPED, 5);
        playerB.roll(WEEK, "2026-09-12");
        record(playerB, UNCAPPED, 5);

        assertEquals(20, playerA.points());
        assertEquals(20, playerB.points());
        assertEquals(List.of(10, 20), PlayerData.due(playerA.points(), playerA.claimedPoints(), MILESTONES));
        assertEquals(List.of(10, 20), PlayerData.due(playerB.points(), playerB.claimedPoints(), MILESTONES));
    }

    // ====================================
    // The daily draw: up to TASKS_PER_DAY distinct loaded ids, wiped by both
    // rollovers so the next day hands out a fresh, fully hidden set.
    // ====================================
    private static List<String> ids(int count) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add("a" + i);
        }
        return ids;
    }

    @Test
    void aDrawIsSevenDistinctIdsOutOfMany() {
        List<String> drawn = PlayerData.draw(ids(30), new Random(7));

        assertEquals(PlayerData.TASKS_PER_DAY, drawn.size());
        assertEquals(drawn.size(), new HashSet<>(drawn).size());
        assertTrue(ids(30).containsAll(drawn));
    }

    @Test
    void aDrawOutOfFewerIdsTakesAllOfThem() {
        List<String> drawn = PlayerData.draw(ids(4), new Random(7));

        assertEquals(4, drawn.size());
        assertEquals(new HashSet<>(ids(4)), new HashSet<>(drawn));
        assertEquals(List.of(), PlayerData.draw(List.of(), new Random(7)));
    }

    @Test
    void tasksAreOnlyDrawnOnce() {
        PlayerData data = data();

        assertTrue(data.ensureTasks(ids(30), new Random(1)));
        List<String> first = List.copyOf(data.tasks());

        assertFalse(data.ensureTasks(ids(30), new Random(2)));
        assertEquals(first, data.tasks());
    }

    @Test
    void onlyARevealedTaskReadsAsRevealed() {
        PlayerData data = data();
        data.ensureTasks(ids(30), new Random(1));

        assertTrue(data.reveal(3));

        for (int slot = 0; slot < data.tasks().size(); slot++) {
            assertEquals(slot == 3, data.isRevealed(data.tasks().get(slot)), "slot " + slot);
        }
        assertFalse(data.reveal(3), "a second reveal of the same slot changes nothing");
        assertFalse(data.reveal(PlayerData.TASKS_PER_DAY), "an empty slot cannot be revealed");
    }

    @Test
    void aNewDayClearsTheDrawAndEveryRevealedFlag() {
        PlayerData data = data();
        data.ensureTasks(ids(30), new Random(1));
        data.reveal(0);

        assertTrue(data.roll(WEEK, "2026-09-10"));

        assertEquals(List.of(), data.tasks());
        assertEquals(Set.of(), data.revealed());
        assertTrue(data.ensureTasks(ids(30), new Random(2)), "the next day draws again");
    }

    @Test
    void aNewWeekClearsTheDrawToo() {
        PlayerData data = data();
        data.ensureTasks(ids(30), new Random(1));
        data.reveal(0);

        assertTrue(data.roll("2026-09-14", DAY));

        assertEquals(List.of(), data.tasks());
        assertEquals(Set.of(), data.revealed());
    }

    @Test
    void aResetClearsTheDraw() {
        PlayerData data = data();
        data.ensureTasks(ids(30), new Random(1));
        data.reveal(0);

        data.reset(WEEK, DAY);

        assertEquals(List.of(), data.tasks());
        assertEquals(Set.of(), data.revealed());
    }

    @Test
    void theStoredConstructorKeepsOnlyRevealedFlagsThatAreTasks() {
        PlayerData data = new PlayerData(0, 0, WEEK, DAY, 0, java.util.Map.of(),
            List.of("vote", "quest"), List.of("quest", "stranger"));

        assertEquals(List.of("vote", "quest"), data.tasks());
        assertTrue(data.isRevealed("quest"));
        assertFalse(data.isRevealed("stranger"));
    }

    // ====================================
    // An activity removed by /activity reload leaves a dead id in the draw.
    // The next use drops it, discards its revealed flag and tops the draw back
    // up out of what is still loaded - never below TASKS_PER_DAY while there
    // are ids left to draw.
    // ====================================
    @Test
    void aRemovedActivityIsDroppedAndTheDrawToppedBackUp() {
        PlayerData data = new PlayerData(0, 0, WEEK, DAY, 0, java.util.Map.of(),
            List.of("a0", "gone", "a1", "a2", "a3", "a4", "a5"), List.of("gone", "a1"));

        assertTrue(data.ensureTasks(ids(30), new Random(3)));

        assertEquals(PlayerData.TASKS_PER_DAY, data.tasks().size());
        assertFalse(data.tasks().contains("gone"), "a removed activity stays in the draw");
        assertEquals(data.tasks().size(), new HashSet<>(data.tasks()).size(), "the top-up repeats an id");
        assertTrue(ids(30).containsAll(data.tasks()));
        // The survivors keep their order; the refill lands at the end
        assertEquals(List.of("a0", "a1", "a2", "a3", "a4", "a5"), data.tasks().subList(0, 6));
    }

    @Test
    void aRefilledSlotIsUnrevealedAndTheDroppedFlagIsDiscarded() {
        PlayerData data = new PlayerData(0, 0, WEEK, DAY, 0, java.util.Map.of(),
            List.of("a0", "gone", "a1", "a2", "a3", "a4", "a5"), List.of("gone", "a1"));

        data.ensureTasks(ids(30), new Random(3));

        assertEquals(Set.of("a1"), data.revealed());
        assertFalse(data.isRevealed("gone"));
        assertFalse(data.isRevealed(data.tasks().get(6)), "the refilled slot is revealed");
    }

    // Nothing left to draw from: the draw just shrinks rather than looping
    @Test
    void aDrawCannotBeToppedUpPastWhatIsLoaded() {
        PlayerData data = new PlayerData(0, 0, WEEK, DAY, 0, java.util.Map.of(),
            List.of("a0", "gone", "a1"), List.of());

        assertTrue(data.ensureTasks(ids(2), new Random(3)));
        assertEquals(List.of("a0", "a1"), data.tasks());
    }

    @Test
    void aDrawnListCannotBeMutatedByItsCaller() {
        List<String> drawn = PlayerData.draw(ids(30), new Random(7));

        assertThrows(UnsupportedOperationException.class, () -> drawn.add("a99"));
    }
}
