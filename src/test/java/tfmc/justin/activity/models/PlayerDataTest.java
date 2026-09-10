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

    private static final ActivityDef VOTE = new ActivityDef("vote", "Vote", Material.PAPER, 1, 1, 5);
    private static final ActivityDef QUEST = new ActivityDef("quest", "Quest", Material.BOOK, 1, 1, 5);
    private static final ActivityDef INSTRUMENT = new ActivityDef("instrument", "Notes", Material.NOTE_BLOCK, 20, 1, 1);
    private static final ActivityDef UNCAPPED = new ActivityDef("free", "Free", Material.STONE, 1, 1, 0);

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

        data.setClaimed(1);

        assertEquals(0, data.claimable(EVERY));
        record(data, UNCAPPED, 10);
        assertEquals(1, data.claimable(EVERY));
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
        PlayerData data = data();
        ActivityDef rich = new ActivityDef("rich", "Rich", Material.STONE, 1, 1_000_000, 0);

        assertEquals(Integer.MAX_VALUE, data.worth(Integer.MAX_VALUE, rich));
    }

    @Test
    void newWeekWipesEverything() {
        PlayerData data = data();
        record(data, UNCAPPED, 10);
        data.setClaimed(1);

        assertTrue(data.roll("2026-09-14", "2026-09-14"));

        assertEquals(0, data.points());
        assertEquals(0, data.claimed());
        assertEquals(0, data.count("free"));
    }

    @Test
    void newDayWipesOnlyTheDailyCounters() {
        PlayerData data = data();
        record(data, VOTE, 5);
        data.setClaimed(1);

        assertTrue(data.roll(WEEK, "2026-09-10"));

        assertEquals(5, data.points());
        assertEquals(1, data.claimed());
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
        data.setClaimed(1);

        data.reset("2026-09-14", "2026-09-14");

        assertEquals(0, data.points());
        assertEquals(0, data.claimed());
        assertEquals(0, data.count("free"));
        assertEquals("2026-09-14", data.weekKey());
    }
}
