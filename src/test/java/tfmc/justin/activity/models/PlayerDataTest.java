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
        return data.record(amount, def, MAX, EVERY);
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
        PlayerData data = new PlayerData(100, WEEK, DAY, 0, java.util.Map.of());

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
        PlayerData data = new PlayerData(20, WEEK, DAY, 10, java.util.Map.of());

        // 20 / 5 - 10 / 5 = 4 - 2 = 2, not 20 / 5 = 4 and not 3
        assertEquals(2, data.claimable(5));
    }

    @Test
    void pointsShortOfAThresholdDoNotOweIt() {
        PlayerData data = new PlayerData(45, WEEK, DAY, 0, java.util.Map.of());

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
}
