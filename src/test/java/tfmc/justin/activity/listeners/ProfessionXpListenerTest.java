package tfmc.justin.activity.listeners;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.listeners.ProfessionXpListener.Credit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Only the XP conversion: the listener itself needs a running server
class ProfessionXpListenerTest {

    @Test
    void wholeAndFractionalXpFloors() {
        assertEquals(1, ProfessionXpListener.credit(0, 1.0).amount());
        assertEquals(1, ProfessionXpListener.credit(0, 1.9).amount());
        assertEquals(500, ProfessionXpListener.credit(0, 500.4).amount());
    }

    @Test
    void nothingWorthRecordingIsZero() {
        assertEquals(0, ProfessionXpListener.credit(0, 0.9).amount());
        assertEquals(0, ProfessionXpListener.credit(0, 0).amount());
        assertEquals(0, ProfessionXpListener.credit(0, -50).amount());
        assertEquals(0, ProfessionXpListener.credit(0, Double.NaN).amount());
        assertEquals(0, ProfessionXpListener.credit(0, Double.NEGATIVE_INFINITY).amount());
    }

    @Test
    void absurdValuesClampInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, ProfessionXpListener.credit(0, 1e18).amount());
        assertEquals(Integer.MAX_VALUE, ProfessionXpListener.credit(0, Double.POSITIVE_INFINITY).amount());
        assertEquals(0, ProfessionXpListener.credit(0.5, Double.POSITIVE_INFINITY).carry());
    }

    @Test
    void theFractionIsCarriedNotDiscarded() {
        Credit first = ProfessionXpListener.credit(0, 5.5);
        assertEquals(5, first.amount());
        assertEquals(0.5, first.carry(), 1e-9);

        Credit second = ProfessionXpListener.credit(first.carry(), 5.5);
        assertEquals(6, second.amount());
        assertEquals(0.0, second.carry(), 1e-9);
    }

    // A profession tuned below 1 xp per action must still credit eventually
    @Test
    void repeatedSubOneGainsEventuallyCredit() {
        double carry = 0;
        int total = 0;
        for (int i = 0; i < 10; i++) {
            Credit credit = ProfessionXpListener.credit(carry, 0.5);
            carry = credit.carry();
            total += credit.amount();
        }
        assertEquals(5, total);
    }

    // A poisoned value contributes nothing and leaves the carry intact
    @Test
    void poisonedValuesCannotCorruptTheCarry() {
        assertEquals(0.5, ProfessionXpListener.credit(0.5, Double.NaN).carry(), 1e-9);
        assertEquals(0.5, ProfessionXpListener.credit(0.5, -1e9).carry(), 1e-9);
        assertEquals(0, ProfessionXpListener.credit(0.5, Double.NaN).amount());
    }

    @Test
    void carryNeverReachesAWholePoint() {
        double carry = 0;
        for (int i = 0; i < 50; i++) {
            Credit credit = ProfessionXpListener.credit(carry, 0.7);
            carry = credit.carry();
            org.junit.jupiter.api.Assertions.assertTrue(carry >= 0 && carry < 1, "carry out of range: " + carry);
        }
    }
}
