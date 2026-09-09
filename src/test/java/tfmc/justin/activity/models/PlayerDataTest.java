package tfmc.justin.activity.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    private static final String WEEK = "2026-09-07";
    private static final String DAY = "2026-09-09";

    private PlayerData data() {
        return new PlayerData(WEEK, DAY);
    }

    @Test
    void progressBelowTheGoalAwardsNothing() {
        PlayerData data = data();

        RecordResult result = data.record("instrument", 19, 20, 50);

        assertFalse(result.goalJustMet());
        assertEquals(0, result.pointsAwarded());
        assertEquals(0, data.points());
        assertEquals(19, data.count("instrument"));
    }

    @Test
    void crossingTheGoalAwardsPointsExactlyOnce() {
        PlayerData data = data();

        data.record("instrument", 19, 20, 50);
        RecordResult crossing = data.record("instrument", 1, 20, 50);
        RecordResult after = data.record("instrument", 5, 20, 50);

        assertTrue(crossing.goalJustMet());
        assertEquals(50, crossing.pointsAwarded());
        assertFalse(after.goalJustMet());
        assertEquals(0, after.pointsAwarded());
        assertEquals(50, data.points());
    }

    @Test
    void pointsClampAtOneHundred() {
        PlayerData data = data();

        data.record("vote", 1, 1, 50);
        data.record("geiger", 3, 3, 50);
        data.record("instrument", 20, 20, 50);

        assertEquals(100, data.points());
    }

    @Test
    void hitHundredFiresOnlyOnTheCrossing() {
        PlayerData data = data();

        assertFalse(data.record("vote", 1, 1, 50).hitHundred());
        assertTrue(data.record("geiger", 3, 3, 50).hitHundred());
        assertFalse(data.record("instrument", 20, 20, 50).hitHundred());
    }

    @Test
    void jumpingPastTheGoalInOneCallAwardsPointsOnce() {
        PlayerData data = data();

        RecordResult jump = data.record("geiger", 5, 3, 50);

        assertTrue(jump.goalJustMet());
        assertEquals(50, jump.pointsAwarded());
        assertEquals(5, data.count("geiger"));

        RecordResult further = data.record("geiger", 1, 3, 50);

        assertFalse(further.goalJustMet());
        assertEquals(0, further.pointsAwarded());
        assertEquals(6, data.count("geiger"));
    }

    @Test
    void recordingAnActivityThatAlreadyMetItsGoalTodayAwardsNothingButStillCounts() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);

        RecordResult again = data.record("vote", 1, 1, 50);

        assertFalse(again.goalJustMet());
        assertEquals(0, again.pointsAwarded());
        assertEquals(2, data.count("vote"));
    }

    @Test
    void recordingAGoalCrossingWhilePointsAreAlreadyAtOneHundredAwardsNoPoints() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);
        data.record("geiger", 3, 3, 50);
        assertEquals(100, data.points());

        RecordResult result = data.record("instrument", 20, 20, 50);

        assertTrue(result.goalJustMet());
        assertEquals(0, result.pointsAwarded());
        assertFalse(result.hitHundred());
        assertEquals(100, data.points());
    }

    @Test
    void pointsAwardedReflectsTheClampedDeltaWhenAGoalPushesPastOneHundred() {
        PlayerData data = data();
        data.addPoints(80);

        RecordResult result = data.record("vote", 1, 1, 50);

        assertEquals(20, result.pointsAwarded());
        assertEquals(100, data.points());
        assertTrue(result.hitHundred());
    }

    @Test
    void addPointsClampsNegativeValuesAtZero() {
        PlayerData data = data();

        data.addPoints(-50);

        assertEquals(0, data.points());
    }

    @Test
    void addPointsClampsPositiveValuesAtOneHundred() {
        PlayerData data = data();

        data.addPoints(150);

        assertEquals(100, data.points());
    }

    @Test
    void addPointsCrossingOneHundredAcrossMultipleCallsClampsAndLeavesRewardedUntouched() {
        PlayerData data = data();

        data.addPoints(80);
        data.addPoints(30);

        assertEquals(100, data.points());
        assertFalse(data.rewarded());
    }

    @Test
    void ahugeAmountSaturatesInsteadOfWrappingNegative() {
        PlayerData data = data();

        data.record("vote", Integer.MAX_VALUE, 1, 50);
        RecordResult again = data.record("vote", Integer.MAX_VALUE, 1, 50);

        assertEquals(Integer.MAX_VALUE, data.count("vote"));
        assertFalse(again.goalJustMet());
        assertEquals(0, again.pointsAwarded());
        assertEquals(50, data.points());
    }

    @Test
    void saturationHoldsAcrossManyOversizedRecords() {
        PlayerData data = data();

        for (int i = 0; i < 5; i++) {
            data.record("geiger", Integer.MAX_VALUE, 3, 50);
        }

        assertTrue(data.count("geiger") > 0);
        assertEquals(Integer.MAX_VALUE, data.count("geiger"));
    }

    @Test
    void newWeekWipesEverything() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);
        data.setRewarded(true);

        assertTrue(data.roll("2026-09-14", "2026-09-14"));

        assertEquals(0, data.points());
        assertFalse(data.rewarded());
        assertEquals(0, data.count("vote"));
    }

    @Test
    void newDayWipesOnlyTheDailyCounters() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);
        data.setRewarded(true);

        assertTrue(data.roll(WEEK, "2026-09-10"));

        assertEquals(50, data.points());
        assertTrue(data.rewarded());
        assertEquals(0, data.count("vote"));
    }

    @Test
    void sameWeekAndDayChangeNothing() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);

        assertFalse(data.roll(WEEK, DAY));

        assertEquals(1, data.count("vote"));
        assertEquals(50, data.points());
    }

    @Test
    void pendingRewardSurvivesADayRollAndIsClearedByAWeekRoll() {
        PlayerData data = data();
        data.setPendingReward(true);

        data.roll(WEEK, "2026-09-10");
        assertTrue(data.pendingReward());

        data.roll("2026-09-14", "2026-09-14");
        assertFalse(data.pendingReward());
    }

    @Test
    void resetClearsTheWeekAndMovesTheKeys() {
        PlayerData data = data();
        data.record("vote", 1, 1, 50);
        data.setRewarded(true);
        data.setPendingReward(true);

        data.reset("2026-09-14", "2026-09-14");

        assertEquals(0, data.points());
        assertFalse(data.rewarded());
        assertFalse(data.pendingReward());
        assertEquals(0, data.count("vote"));
        assertEquals("2026-09-14", data.weekKey());
    }
}
