package tfmc.justin.activity.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    private static final String WEEK = "2026-09-07";
    private static final String DAY = "2026-09-09";
    private static final int MAX = 20;
    private static final int DAILY_MAX = 1000;
    private static final int EVERY = 10;

    private static final ActivityDef VOTE = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5, null);
    private static final ActivityDef QUEST = new ActivityDef("quest", "Quest", Material.BOOK, null, 1, 1, 5, null);
    private static final ActivityDef INSTRUMENT =
        new ActivityDef("instrument", "Notes", Material.NOTE_BLOCK, null, 20, 1, 1, null);
    private static final ActivityDef UNCAPPED = new ActivityDef("free", "Free", Material.STONE, null, 1, 1, 0, null);

    private PlayerData data() {
        return new PlayerData(WEEK, DAY);
    }

    private RecordResult record(PlayerData data, ActivityDef def, int amount) {
        return data.record(amount, def, MAX, DAILY_MAX, EVERY);
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
        assertEquals(2, data.claimable(EVERY));
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
        assertEquals(2, data.claimable(EVERY));
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

        assertEquals(0, data.claimable(EVERY));
        record(data, UNCAPPED, 10);
        assertEquals(1, data.claimable(EVERY));
    }

    // A paid threshold stays paid: with a milestone counter, halving
    // reward-every used to make every already-claimed milestone claimable again
    @Test
    void loweringRewardEveryDoesNotReviveAPaidClaim() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints(10);

        assertEquals(0, data.claimable(EVERY / 2));
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
        ActivityDef rich = new ActivityDef("rich", "Rich", Material.STONE, null, 1, 1_000_000, 0, null);

        assertEquals(Integer.MAX_VALUE, rich.worth(Integer.MAX_VALUE));
    }

    @Test
    void clampPullsLegacyPointsDownToTheBar() {
        PlayerData data = new PlayerData(100, 0, WEEK, DAY, 0, java.util.Map.of());

        assertTrue(data.clamp(MAX));
        assertEquals(MAX, data.points());
        assertEquals(2, data.claimable(EVERY));
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
    void claimableFollowsTheDivisionFormulaDirectly() {
        PlayerData data = data();
        record(data, UNCAPPED, 15);
        data.setClaimedPoints(10);

        // 15 / 5 - 10 / 5 = 3 - 2 = 1
        assertEquals(1, data.claimable(5));
    }

    @Test
    void doublingRewardEveryAfterAFullClaimDoesNotReviveIt() {
        PlayerData data = data();
        record(data, UNCAPPED, MAX);
        // Claimed everything at EVERY (10): claimedPoints = (20/10)*10 = 20
        data.setClaimedPoints((data.points() / EVERY) * EVERY);

        assertEquals(0, data.claimable(EVERY * 2));
    }

    @Test
    void nonDivisorRewardEveryAfterAFullClaimDoesNotReviveIt() {
        PlayerData data = data();
        record(data, UNCAPPED, MAX);
        data.setClaimedPoints((data.points() / EVERY) * EVERY);

        // 7 does not evenly divide bar.max (20)
        assertEquals(0, data.claimable(7));
    }

    @Test
    void changingRewardEveryStillExposesAReachableUnclaimedMilestone() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimedPoints((data.points() / EVERY) * EVERY);
        record(data, UNCAPPED, 7);

        // points=17, claimed=10. Under every=7: 17/7 - 10/7 = 2 - 1 = 1
        assertEquals(1, data.claimable(7));
    }

    // ====================================
    // claimable() is the gap between two thresholds, not points/every: a
    // player who has already been paid for part of the bar must be owed only
    // what is left, or every claim would hand out the whole bar again.
    // ====================================
    @Test
    void partlyClaimedPointsOnlyOweTheRemainder() {
        PlayerData data = new PlayerData(20, 0, WEEK, DAY, 10, java.util.Map.of());

        // 20 / 5 - 10 / 5 = 4 - 2 = 2, not 20 / 5 = 4 and not 3
        assertEquals(2, data.claimable(5));
    }

    @Test
    void pointsShortOfAThresholdDoNotOweIt() {
        PlayerData data = new PlayerData(45, 0, WEEK, DAY, 0, java.util.Map.of());

        // 45 / 20 - 0 / 20 = 2: the last 5 points have not reached the third
        assertEquals(2, data.claimable(20));
    }

    @Test
    void claimableNeverGoesNegativeWhenClaimedPointsExceedsPoints() {
        PlayerData data = data();
        record(data, UNCAPPED, 5);

        data.setClaimedPoints(100);

        assertEquals(0, data.claimable(EVERY));
    }

    // Points past the daily max are lost: they must not reach the weekly bar
    // today, nor leak into it once the day rolls over.
    @Test
    void pointsPastTheDailyMaxAreLost() {
        PlayerData data = data();

        assertEquals(3, data.record(36, UNCAPPED, MAX, 3, EVERY).pointsAwarded());
        assertEquals(3, data.dailyPoints());
        assertEquals(3, data.points());

        assertEquals(0, data.record(10, UNCAPPED, MAX, 3, EVERY).pointsAwarded());
        assertEquals(3, data.points());

        data.roll(WEEK, "2026-09-10");
        assertEquals(0, data.dailyPoints());
        assertEquals(3, data.record(25, UNCAPPED, MAX, 3, EVERY).pointsAwarded());
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

        RecordResult day1 = data.record(36, UNCAPPED, hugeMax, dailyMax, EVERY);
        assertEquals(10, day1.pointsAwarded());
        assertEquals(10, data.points());
        assertEquals(10, data.dailyPoints());

        data.roll(WEEK, "2026-09-10");

        RecordResult day2 = data.record(25, UNCAPPED, hugeMax, dailyMax, EVERY);
        assertEquals(10, day2.pointsAwarded());
        assertEquals(20, data.points());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void partiallyRemainingBudgetTrimsTheAward() {
        PlayerData data = data();
        int dailyMax = 10;

        data.record(8, UNCAPPED, MAX, dailyMax, EVERY);
        RecordResult result = data.record(5, UNCAPPED, MAX, dailyMax, EVERY);

        assertEquals(2, result.pointsAwarded());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void exhaustedBudgetAwardsNothingAndNoMilestone() {
        PlayerData data = data();
        int dailyMax = 10;

        data.record(10, UNCAPPED, MAX, dailyMax, EVERY);
        RecordResult result = data.record(5, UNCAPPED, MAX, dailyMax, EVERY);

        assertEquals(0, result.pointsAwarded());
        assertEquals(0, result.milestonesReached());
        assertEquals(10, data.dailyPoints());
    }

    @Test
    void dayRollResetsDailyPointsButKeepsWeeklyPoints() {
        PlayerData data = data();
        data.record(10, UNCAPPED, MAX, 10, EVERY);

        data.roll(WEEK, "2026-09-10");

        assertEquals(0, data.dailyPoints());
        assertEquals(10, data.points());
    }

    @Test
    void weekRollResetsBothWeeklyAndDailyPoints() {
        PlayerData data = data();
        data.record(10, UNCAPPED, MAX, 10, EVERY);

        data.roll("2026-09-14", "2026-09-14");

        assertEquals(0, data.dailyPoints());
        assertEquals(0, data.points());
    }

    // Milestones must reflect what actually reached the weekly bar, not the
    // raw earned amount before the daily clamp trims it: a raw 20 would cross
    // two thresholds at EVERY=10, but a dailyMax of 5 trims it to 5.
    @Test
    void milestonesAreComputedFromTheDailyClampedAmount() {
        PlayerData data = data();

        RecordResult result = data.record(20, UNCAPPED, 1_000_000, 5, EVERY);

        assertEquals(5, result.pointsAwarded());
        assertEquals(0, result.milestonesReached());
    }

    @Test
    void dailyMaxLargerThanBarMaxStillClampsWeeklyAtBarMax() {
        PlayerData data = data();
        int barMax = 5;

        RecordResult result = data.record(20, UNCAPPED, barMax, 1000, EVERY);

        assertEquals(barMax, data.points());
        assertEquals(barMax, result.pointsAwarded());
    }

    // Pinning documented behaviour: dailyPoints tracks the full amount earned
    // before the weekly bar clamps it, not the (possibly smaller) amount that
    // actually landed on the weekly total. A weekly-clamped award still burns
    // its full daily budget.
    @Test
    void weeklyClampedAwardStillConsumesItsFullDailyBudget() {
        PlayerData data = data();
        int barMax = 5;

        RecordResult result = data.record(20, UNCAPPED, barMax, 1000, EVERY);

        assertEquals(barMax, data.points());
        assertEquals(barMax, result.pointsAwarded());
        assertEquals(20, data.dailyPoints());
    }

    @Test
    void zeroDailyBudgetAwardsNothing() {
        PlayerData data = data();

        RecordResult result = data.record(5, UNCAPPED, MAX, 0, EVERY);

        assertEquals(0, result.pointsAwarded());
        assertEquals(0, data.dailyPoints());
    }

    // dailyPoints already past dailyMax (e.g. read off disk after dailyMax was
    // lowered) must not drive the remaining budget negative and give an award
    // out of thin air.
    @Test
    void dailyPointsAlreadyOverTheLoweredMaxNeverGoesNegativeOrAwards() {
        PlayerData data = new PlayerData(0, 15, WEEK, DAY, 0, java.util.Map.of());

        RecordResult result = data.record(5, UNCAPPED, MAX, 10, EVERY);

        assertEquals(0, result.pointsAwarded());
        assertEquals(15, data.dailyPoints());
    }
}
