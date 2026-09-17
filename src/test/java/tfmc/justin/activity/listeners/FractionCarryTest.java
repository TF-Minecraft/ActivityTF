package tfmc.justin.activity.listeners;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.listeners.FractionCarry.Credit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Only the fraction arithmetic: the listeners themselves need a running server
class FractionCarryTest {

    @Test
    void wholeAndFractionalXpFloors() {
        assertEquals(1, FractionCarry.credit(0, 1.0).amount());
        assertEquals(1, FractionCarry.credit(0, 1.9).amount());
        assertEquals(500, FractionCarry.credit(0, 500.4).amount());
    }

    @Test
    void nothingWorthRecordingIsZero() {
        assertEquals(0, FractionCarry.credit(0, 0.9).amount());
        assertEquals(0, FractionCarry.credit(0, 0).amount());
        assertEquals(0, FractionCarry.credit(0, -50).amount());
        assertEquals(0, FractionCarry.credit(0, Double.NaN).amount());
        assertEquals(0, FractionCarry.credit(0, Double.NEGATIVE_INFINITY).amount());
    }

    @Test
    void absurdValuesClampInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, FractionCarry.credit(0, 1e18).amount());
        assertEquals(Integer.MAX_VALUE, FractionCarry.credit(0, Double.POSITIVE_INFINITY).amount());
        assertEquals(0, FractionCarry.credit(0.5, Double.POSITIVE_INFINITY).carry());
    }

    @Test
    void theFractionIsCarriedNotDiscarded() {
        Credit first = FractionCarry.credit(0, 5.5);
        assertEquals(5, first.amount());
        assertEquals(0.5, first.carry(), 1e-9);

        Credit second = FractionCarry.credit(first.carry(), 5.5);
        assertEquals(6, second.amount());
        assertEquals(0.0, second.carry(), 1e-9);
    }

    // A profession tuned below 1 xp per action must still credit eventually
    @Test
    void repeatedSubOneGainsEventuallyCredit() {
        double carry = 0;
        int total = 0;
        for (int i = 0; i < 10; i++) {
            Credit credit = FractionCarry.credit(carry, 0.5);
            carry = credit.carry();
            total += credit.amount();
        }
        assertEquals(5, total);
    }

    // What the market listener relies on: sub-denar sales accumulate per
    // player per activity instead of being rounded away
    @Test
    void carryAccumulatesPerPlayerAndActivity() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID one = java.util.UUID.randomUUID();
        java.util.UUID two = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(one, "market_sale", 0.5));
        assertEquals(1, carry.add(one, "market_sale", 0.5));

        // A different activity keeps its own leftover
        assertEquals(0, carry.add(one, "other", 0.5));
        // ...and so does a different player
        assertEquals(0, carry.add(two, "market_sale", 0.5));

        // Forgetting a player drops only their leftover
        assertEquals(0, carry.add(one, "market_sale", 0.5));
        carry.forget(one);
        assertEquals(0, carry.add(one, "market_sale", 0.5));
        assertEquals(1, carry.add(two, "market_sale", 0.5));
    }

    // A poisoned value contributes nothing and leaves the carry intact
    @Test
    void poisonedValuesCannotCorruptTheCarry() {
        assertEquals(0.5, FractionCarry.credit(0.5, Double.NaN).carry(), 1e-9);
        assertEquals(0.5, FractionCarry.credit(0.5, -1e9).carry(), 1e-9);
        assertEquals(0, FractionCarry.credit(0.5, Double.NaN).amount());
    }

    // A market sale worth several denar and change: the whole part is
    // credited immediately and the change keeps accumulating
    @Test
    void aMultiDenarSaleCreditsItsWholePartAndKeepsTheChange() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(2, carry.add(player, "market_sale", 2.75));
        assertEquals(1, carry.add(player, "market_sale", 0.25));
    }

    // A poisoned market sale (free, refunded, or a bad double from the
    // source plugin) must not eat into or fabricate the player's carry
    @Test
    void aWorthlessMarketSaleLeavesLaterSalesUnaffected() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.0));
        assertEquals(0, carry.add(player, "market_sale", -10.0));
        assertEquals(0, carry.add(player, "market_sale", Double.NaN));

        // The carry is still exactly what a fresh player would have: two
        // ordinary half-denar sales still take two sales to earn one point
        assertEquals(0, carry.add(player, "market_sale", 0.5));
        assertEquals(1, carry.add(player, "market_sale", 0.5));
    }

    // +Infinity is not worthless - it is clamped to Integer.MAX_VALUE rather
    // than credited as nothing (see absurdValuesClampInsteadOfOverflowing) -
    // but it must still leave a clean 0 carry behind for whatever the player
    // sells next, not some unrepresentable fractional remainder
    @Test
    void anInfiniteMarketSaleClampsAndLeavesACleanCarryBehind() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(Integer.MAX_VALUE, carry.add(player, "market_sale", Double.POSITIVE_INFINITY));

        // A fresh-looking pair of half sales still takes two to earn one point
        assertEquals(0, carry.add(player, "market_sale", 0.5));
        assertEquals(1, carry.add(player, "market_sale", 0.5));
    }

    @Test
    void carryNeverReachesAWholePoint() {
        double carry = 0;
        for (int i = 0; i < 50; i++) {
            Credit credit = FractionCarry.credit(carry, 0.7);
            carry = credit.carry();
            org.junit.jupiter.api.Assertions.assertTrue(carry >= 0 && carry < 1, "carry out of range: " + carry);
        }
    }

    // Cent-denominated sales must not drift: summed as doubles, 250 additions
    // of 0.10 fall short of 25 and the player never reaches the goal
    @Test
    void centSizedSalesAccumulateExactly() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        int total = 0;
        for (int i = 0; i < 250; i++) {
            total += carry.add(player, "market_sale", 0.10);
        }
        assertEquals(25, total);

        // ...and nothing is left over: a 0.9 sale on a zero carry credits
        // nothing, where a drifted 0.999... carry would credit a point
        assertEquals(0, carry.add(player, "market_sale", 0.9));
        assertEquals(1, carry.add(player, "market_sale", 0.1));
    }

    @Test
    void tenthsSumToAWholePointWithoutDrift() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.1));
        assertEquals(0, carry.add(player, "market_sale", 0.1));
        assertEquals(0, carry.add(player, "market_sale", 0.1));
        assertEquals(1, carry.add(player, "market_sale", 0.7));
    }

    // ====================================
    // The gate refusing an activity drops what it had banked, so a fraction
    // from a day the task was revealed cannot pay out days later when it is
    // drawn and revealed again. Other activities keep theirs.
    // ====================================
    @Test
    void aRefusedActivityLosesItsCarryButTheOthersKeepTheirs() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.9));
        assertEquals(0, carry.add(player, "casino_win", 0.9));

        carry.forget(player, "market_sale");

        // Banked fraction gone: 0.9 on a clean carry is still short of a point
        assertEquals(0, carry.add(player, "market_sale", 0.9));
        // Untouched: 0.9 + 0.9 is worth one
        assertEquals(1, carry.add(player, "casino_win", 0.9));
    }

    @Test
    void forgettingAnActivityNobodyBankedIsHarmless() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        carry.forget(player, "market_sale");
        assertEquals(0, carry.add(player, "market_sale", 0.5));
        carry.forget(player, "casino_win");
        assertEquals(1, carry.add(player, "market_sale", 0.5));
    }
}
