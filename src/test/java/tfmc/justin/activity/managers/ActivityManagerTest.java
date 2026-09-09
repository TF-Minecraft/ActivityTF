package tfmc.justin.activity.managers;

import org.junit.jupiter.api.Test;

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
}
