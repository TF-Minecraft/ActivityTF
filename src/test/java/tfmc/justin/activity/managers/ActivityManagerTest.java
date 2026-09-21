package tfmc.justin.activity.managers;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RewardEntry;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityManagerTest {

    @Test
    void vanillaNamesAreSafe() {
        assertTrue(ActivityManager.isSafeCommandName("Notch"));
        assertTrue(ActivityManager.isSafeCommandName("a"));
        assertTrue(ActivityManager.isSafeCommandName("Some_Player_99"));
        assertTrue(ActivityManager.isSafeCommandName("SixteenCharsXXXX"));
    }

    @Test
    void floodgatePrefixedNamesAreSafe() {
        assertTrue(ActivityManager.isSafeCommandName(".BedrockBob"));
    }

    @Test
    void namesWithASpaceAreRejected() {
        assertFalse(ActivityManager.isSafeCommandName("Bedrock Player"));
        assertFalse(ActivityManager.isSafeCommandName(" Notch"));
        assertFalse(ActivityManager.isSafeCommandName("Notch "));
    }

    @Test
    void namesThatCouldCarryTheirOwnCommandAreRejected() {
        assertFalse(ActivityManager.isSafeCommandName("Bob; op Bob"));
        assertFalse(ActivityManager.isSafeCommandName("Bob\nop Bob"));
        assertFalse(ActivityManager.isSafeCommandName("Bob&op"));
    }

    @Test
    void emptyNullAndOversizedNamesAreRejected() {
        assertFalse(ActivityManager.isSafeCommandName(null));
        assertFalse(ActivityManager.isSafeCommandName(""));
        assertFalse(ActivityManager.isSafeCommandName("SeventeenCharsXXX"));
    }

    @Test
    void aCommandPastingTheNameIsSkippedForAnUnsafeName() {
        assertFalse(ActivityManager.canRunCommand("give %player% diamond 3", "Bedrock Player"));
        assertFalse(ActivityManager.canRunCommand("say %player% and %uuid%", "Bob; op Bob"));
        assertFalse(ActivityManager.canRunCommand("give %player% diamond 3", null));
    }

    @Test
    void aCommandUsingOnlyTheUuidRunsForAnUnsafeName() {
        assertTrue(ActivityManager.canRunCommand("lp user %uuid% parent add vip", "Bedrock Player"));
        assertTrue(ActivityManager.canRunCommand("say done", "Bob; op Bob"));
        assertTrue(ActivityManager.canRunCommand("lp user %uuid% parent add vip", null));
    }

    @Test
    void aCommandPastingTheNameRunsForASafeName() {
        assertTrue(ActivityManager.canRunCommand("give %player% diamond 3", "Notch"));
        assertTrue(ActivityManager.canRunCommand("give %player% diamond 3", ".BedrockBob"));
    }

    @Test
    void aNullCommandRunsNothing() {
        assertFalse(ActivityManager.canRunCommand(null, "Notch"));
    }

    @Test
    void unsafeNameKeepsOnlyEntriesWithAUuidOrNoPlaceholderCommand() {
        RewardEntry playerOnly = new RewardEntry(1, "player-only",
            List.of("give %player% diamond 3"), List.of());
        RewardEntry uuidOnly = new RewardEntry(1, "uuid-only",
            List.of("lp user %uuid% parent add vip"), List.of());
        RewardEntry mixed = new RewardEntry(1, "mixed",
            List.of("give %player% diamond 3", "say done"), List.of());
        List<RewardEntry> pool = List.of(playerOnly, uuidOnly, mixed);

        assertEquals(List.of(uuidOnly, mixed),
            ActivityManager.runnableEntries(pool, "Bedrock Player"));
    }

    @Test
    void safeNameKeepsEveryEntry() {
        RewardEntry playerOnly = new RewardEntry(1, "player-only",
            List.of("give %player% diamond 3"), List.of());
        RewardEntry uuidOnly = new RewardEntry(1, "uuid-only",
            List.of("lp user %uuid% parent add vip"), List.of());
        RewardEntry mixed = new RewardEntry(1, "mixed",
            List.of("give %player% diamond 3", "say done"), List.of());
        List<RewardEntry> pool = List.of(playerOnly, uuidOnly, mixed);

        assertEquals(pool, ActivityManager.runnableEntries(pool, "Notch"));
    }

    @Test
    void anEmptyPoolStaysEmpty() {
        assertEquals(List.of(), ActivityManager.runnableEntries(List.of(), "Notch"));
        assertEquals(List.of(), ActivityManager.runnableEntries(List.of(), "Bedrock Player"));
    }

    @Test
    void aPartialRollbackKeepsOnlyTheMilestonesThatPaid() {
        List<Integer> due = List.of(20, 30, 40);

        assertEquals(20, ActivityManager.rollbackClaimedPoints(10, 1, due));
        assertEquals(30, ActivityManager.rollbackClaimedPoints(10, 2, due));
    }

    @Test
    void aRollbackNeverGoesBelowWhereItStarted() {
        List<Integer> due = List.of(20, 30, 40);
        for (int paid = 0; paid <= 3; paid++) {
            assertTrue(ActivityManager.rollbackClaimedPoints(10, paid, due) >= 10);
        }
    }

    @Test
    void payingNothingRollsBackToExactlyWhereItStarted() {
        assertEquals(10, ActivityManager.rollbackClaimedPoints(10, 0, List.of(20, 30)));
        assertEquals(0, ActivityManager.rollbackClaimedPoints(0, 0, List.of(10)));
    }

    @Test
    void payingEverythingRollsForwardToTheLastDueMilestone() {
        List<Integer> due = List.of(20, 30, 40);

        assertEquals(40, ActivityManager.rollbackClaimedPoints(10, 3, due));
        assertEquals(40, ActivityManager.rollbackClaimedPoints(10, due.size(), due));
    }

    @Test
    void aPartialRollbackStaysBetweenWhereItStartedAndAFullClaim() {
        for (int points : new int[] {0, 7, 10, 19, 20, 45}) {
            for (List<Integer> milestones : List.of(List.of(5, 10, 15, 20), List.of(10, 20), List.of(20, 40))) {
                for (int claimedBefore : new int[] {0, 7, 10}) {
                    List<Integer> due = PlayerData.due(points, claimedBefore, milestones);
                    if (due.isEmpty()) {
                        continue;
                    }

                    int full = due.get(due.size() - 1);
                    for (int paid = 0; paid < due.size(); paid++) {
                        int rolledBack = ActivityManager.rollbackClaimedPoints(claimedBefore, paid, due);
                        String shape = "points=" + points + " milestones=" + milestones
                            + " claimedBefore=" + claimedBefore + " paid=" + paid;

                        assertTrue(rolledBack >= claimedBefore, shape);
                        assertTrue(rolledBack < full, shape);
                    }
                }
            }
        }
    }

    @Test
    void aWeightedDrawWalksTheCumulativeWeights() {
        RewardEntry common = new RewardEntry(3, "common", List.of("give %player% diamond 3"), List.of());
        RewardEntry rare = new RewardEntry(1, "rare", List.of("give %player% netherite_ingot 1"), List.of());
        List<RewardEntry> pool = List.of(common, rare);

        assertEquals(4, RewardEntry.totalWeight(pool));
        assertEquals(common, RewardEntry.pick(pool, 0));
        assertEquals(common, RewardEntry.pick(pool, 2));
        assertEquals(rare, RewardEntry.pick(pool, 3));
    }

    @Test
    void anEmptyPoolDrawsNothing() {
        assertNull(RewardEntry.pick(List.of(), 0));
    }

    @Test
    void aPositiveAfkSettingIsThatManyMinutes() {
        assertEquals(Duration.ofMinutes(5), ActivityManager.afkThreshold(5));
        assertEquals(Duration.ofMinutes(1), ActivityManager.afkThreshold(1));
        assertEquals(Duration.ofMinutes(120), ActivityManager.afkThreshold(120));
    }

    @Test
    void zeroOrLessDisablesTheIdleCheck() {
        assertNull(ActivityManager.afkThreshold(0));
        assertNull(ActivityManager.afkThreshold(-1));
        assertNull(ActivityManager.afkThreshold(Integer.MIN_VALUE));
    }
}
