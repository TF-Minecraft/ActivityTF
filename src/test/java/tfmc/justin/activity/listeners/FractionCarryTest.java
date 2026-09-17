package tfmc.justin.activity.listeners;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.config.ActivityConfiguration.Keys;
import tfmc.justin.activity.listeners.FractionCarry.Credit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Only the fraction arithmetic: the listeners themselves need a running server
class FractionCarryTest {

    // The cycle every fraction in these tests is banked on
    private static final Keys DAY = new Keys("2026-W38", "2026-09-17");

    // The same week, the next day
    private static final Keys NEXT_DAY = new Keys("2026-W38", "2026-09-18");

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

        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(one, "market_sale", 0.5, DAY));

        // A different activity keeps its own leftover
        assertEquals(0, carry.add(one, "other", 0.5, DAY));
        // ...and so does a different player
        assertEquals(0, carry.add(two, "market_sale", 0.5, DAY));

        // Forgetting a player drops only their leftover
        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        carry.forget(one);
        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(two, "market_sale", 0.5, DAY));
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

        assertEquals(2, carry.add(player, "market_sale", 2.75, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.25, DAY));
    }

    // A poisoned market sale (free, refunded, or a bad double from the
    // source plugin) must not eat into or fabricate the player's carry
    @Test
    void aWorthlessMarketSaleLeavesLaterSalesUnaffected() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.0, DAY));
        assertEquals(0, carry.add(player, "market_sale", -10.0, DAY));
        assertEquals(0, carry.add(player, "market_sale", Double.NaN, DAY));

        // The carry is still exactly what a fresh player would have: two
        // ordinary half-denar sales still take two sales to earn one point
        assertEquals(0, carry.add(player, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.5, DAY));
    }

    // +Infinity is not worthless - it is clamped to Integer.MAX_VALUE rather
    // than credited as nothing (see absurdValuesClampInsteadOfOverflowing) -
    // but it must still leave a clean 0 carry behind for whatever the player
    // sells next, not some unrepresentable fractional remainder
    @Test
    void anInfiniteMarketSaleClampsAndLeavesACleanCarryBehind() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(Integer.MAX_VALUE, carry.add(player, "market_sale", Double.POSITIVE_INFINITY, DAY));

        // A fresh-looking pair of half sales still takes two to earn one point
        assertEquals(0, carry.add(player, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.5, DAY));
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
            total += carry.add(player, "market_sale", 0.10, DAY);
        }
        assertEquals(25, total);

        // ...and nothing is left over: a 0.9 sale on a zero carry credits
        // nothing, where a drifted 0.999... carry would credit a point
        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.1, DAY));
    }

    @Test
    void tenthsSumToAWholePointWithoutDrift() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.1, DAY));
        assertEquals(0, carry.add(player, "market_sale", 0.1, DAY));
        assertEquals(0, carry.add(player, "market_sale", 0.1, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.7, DAY));
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

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        assertEquals(0, carry.add(player, "casino_win", 0.9, DAY));

        carry.forget(player, "market_sale");

        // Banked fraction gone: 0.9 on a clean carry is still short of a point
        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        // Untouched: 0.9 + 0.9 is worth one
        assertEquals(1, carry.add(player, "casino_win", 0.9, DAY));
    }

    @Test
    void forgettingAnActivityNobodyBankedIsHarmless() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        carry.forget(player, "market_sale");
        assertEquals(0, carry.add(player, "market_sale", 0.5, DAY));
        carry.forget(player, "casino_win");
        assertEquals(1, carry.add(player, "market_sale", 0.5, DAY));
    }

    // ====================================
    // The daily counters a fraction feeds are wiped at the rollover, so the
    // fraction must not outlive the day it was banked on: a player online
    // across midnight would otherwise be paid today for yesterday's leftover.
    // ====================================
    @Test
    void aFractionNeverCrossesADayBoundary() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        // 0.9 + 0.9 would be worth one on the same day
        assertEquals(0, carry.add(player, "market_sale", 0.9, NEXT_DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.1, NEXT_DAY));
    }

    // ====================================
    // The week key flips at the configured reset hour, which wipes the daily
    // counters while the day key is unchanged - a fraction banked minutes
    // before the reset must not pay out into the new week.
    // ====================================
    @Test
    void aFractionNeverCrossesAWeekBoundaryEither() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();
        Keys nextWeek = new Keys("2026-W39", DAY.day());

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        // Same day, new week: the bank is gone, so 0.9 is still short of one
        assertEquals(0, carry.add(player, "market_sale", 0.9, nextWeek));
        assertEquals(1, carry.add(player, "market_sale", 0.1, nextWeek));
    }

    // Every player's bank is dropped, not just the one whose event ran first
    @Test
    void theRolloverDropsEveryPlayersFraction() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID one = java.util.UUID.randomUUID();
        java.util.UUID two = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        assertEquals(0, carry.add(two, "market_sale", 0.5, DAY));

        assertEquals(0, carry.add(one, "market_sale", 0.5, NEXT_DAY));
        assertEquals(0, carry.add(two, "market_sale", 0.5, NEXT_DAY));
    }
}
