package tfmc.justin.activity.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Only the pure half of the manager: the rest needs a running server
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
        assertFalse(ActivityManager.canRunRewardCommand("give %player% diamond 3", "Bedrock Player"));
        assertFalse(ActivityManager.canRunRewardCommand("say %player% and %uuid%", "Bob; op Bob"));
        assertFalse(ActivityManager.canRunRewardCommand("give %player% diamond 3", null));
    }

    @Test
    void aCommandUsingOnlyTheUuidRunsForAnUnsafeName() {
        assertTrue(ActivityManager.canRunRewardCommand("lp user %uuid% parent add vip", "Bedrock Player"));
        assertTrue(ActivityManager.canRunRewardCommand("say done", "Bob; op Bob"));
        assertTrue(ActivityManager.canRunRewardCommand("lp user %uuid% parent add vip", null));
    }

    @Test
    void aCommandPastingTheNameRunsForASafeName() {
        assertTrue(ActivityManager.canRunRewardCommand("give %player% diamond 3", "Notch"));
        assertTrue(ActivityManager.canRunRewardCommand("give %player% diamond 3", ".BedrockBob"));
    }

    @Test
    void aNullCommandRunsNothing() {
        assertFalse(ActivityManager.canRunRewardCommand(null, "Notch"));
    }

    // ====================================
    // The claim arithmetic. A full claim burns every threshold the bar has
    // reached; a partial one gives back only what never went out.
    // ====================================
    @Test
    void aFullClaimBurnsEveryReachedThreshold() {
        assertEquals(20, ActivityManager.nextClaimedPoints(20, 10));
        // Points between thresholds do not pay for the next one
        assertEquals(20, ActivityManager.nextClaimedPoints(27, 10));
        assertEquals(0, ActivityManager.nextClaimedPoints(9, 10));
    }

    @Test
    void aPartialRollbackKeepsOnlyWhatWasPaid() {
        // Claimed 10 before, 3 milestones were due, 1 went out: 10 + 1 * 10
        assertEquals(20, ActivityManager.rollbackClaimedPoints(10, 1, 10));
        assertEquals(30, ActivityManager.rollbackClaimedPoints(10, 2, 10));
    }

    @Test
    void aRollbackNeverGoesBelowWhereItStarted() {
        for (int paid = 0; paid <= 3; paid++) {
            assertTrue(ActivityManager.rollbackClaimedPoints(10, paid, 10) >= 10);
        }
    }

    @Test
    void payingNothingRollsBackToExactlyWhereItStarted() {
        assertEquals(10, ActivityManager.rollbackClaimedPoints(10, 0, 10));
        assertEquals(0, ActivityManager.rollbackClaimedPoints(0, 0, 10));
    }

    // ====================================
    // The two bounds that make a partial payout safe, over every shape the
    // numbers can take - including a claimedBefore that is not a multiple of
    // every, which is what a reward-every change mid-week leaves behind:
    //
    //   never below claimedBefore  - a failure cannot hand back a milestone
    //                                that was paid before this click
    //   always below a full claim  - what did not go out stays claimable
    // ====================================
    @Test
    void aPartialRollbackStaysBetweenWhereItStartedAndAFullClaim() {
        for (int points : new int[] {0, 7, 10, 19, 20, 45}) {
            for (int every : new int[] {5, 10, 20}) {
                for (int claimedBefore : new int[] {0, 7, 10}) {
                    int due = points / every - claimedBefore / every;
                    if (due <= 0) {
                        continue;
                    }

                    int full = ActivityManager.nextClaimedPoints(points, every);
                    for (int paid = 0; paid < due; paid++) {
                        int rolledBack = ActivityManager.rollbackClaimedPoints(claimedBefore, paid, every);
                        String shape = "points=" + points + " every=" + every
                            + " claimedBefore=" + claimedBefore + " paid=" + paid;

                        assertTrue(rolledBack >= claimedBefore, shape);
                        assertTrue(rolledBack < full, shape);
                    }
                }
            }
        }
    }
}
