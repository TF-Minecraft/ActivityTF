package net.tfminecraft.activitytf.listeners;

import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.config.ActivityConfiguration.Keys;
import net.tfminecraft.activitytf.listeners.FractionCarry.Credit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FractionCarryTest {

    private static final Keys DAY = new Keys("2026-W38", "2026-09-17");

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

    @Test
    void carryAccumulatesPerPlayerAndActivity() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID one = java.util.UUID.randomUUID();
        java.util.UUID two = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(one, "market_sale", 0.5, DAY));

        assertEquals(0, carry.add(one, "other", 0.5, DAY));
        assertEquals(0, carry.add(two, "market_sale", 0.5, DAY));

        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        carry.forget(one);
        assertEquals(0, carry.add(one, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(two, "market_sale", 0.5, DAY));
    }

    @Test
    void poisonedValuesCannotCorruptTheCarry() {
        assertEquals(0.5, FractionCarry.credit(0.5, Double.NaN).carry(), 1e-9);
        assertEquals(0.5, FractionCarry.credit(0.5, -1e9).carry(), 1e-9);
        assertEquals(0, FractionCarry.credit(0.5, Double.NaN).amount());
    }

    @Test
    void aMultiDenarSaleCreditsItsWholePartAndKeepsTheChange() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(2, carry.add(player, "market_sale", 2.75, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.25, DAY));
    }

    @Test
    void aWorthlessMarketSaleLeavesLaterSalesUnaffected() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.0, DAY));
        assertEquals(0, carry.add(player, "market_sale", -10.0, DAY));
        assertEquals(0, carry.add(player, "market_sale", Double.NaN, DAY));

        assertEquals(0, carry.add(player, "market_sale", 0.5, DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.5, DAY));
    }

    @Test
    void anInfiniteMarketSaleClampsAndLeavesACleanCarryBehind() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(Integer.MAX_VALUE, carry.add(player, "market_sale", Double.POSITIVE_INFINITY, DAY));

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

    @Test
    void centSizedSalesAccumulateExactly() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        int total = 0;
        for (int i = 0; i < 250; i++) {
            total += carry.add(player, "market_sale", 0.10, DAY);
        }
        assertEquals(25, total);

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

    @Test
    void aRefusedActivityLosesItsCarryButTheOthersKeepTheirs() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        assertEquals(0, carry.add(player, "casino_win", 0.9, DAY));

        carry.forget(player, "market_sale");

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
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

    @Test
    void aFractionNeverCrossesADayBoundary() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        assertEquals(0, carry.add(player, "market_sale", 0.9, NEXT_DAY));
        assertEquals(1, carry.add(player, "market_sale", 0.1, NEXT_DAY));
    }

    @Test
    void aFractionNeverCrossesAWeekBoundaryEither() {
        FractionCarry carry = new FractionCarry();
        java.util.UUID player = java.util.UUID.randomUUID();
        Keys nextWeek = new Keys("2026-W39", DAY.day());

        assertEquals(0, carry.add(player, "market_sale", 0.9, DAY));
        assertEquals(0, carry.add(player, "market_sale", 0.9, nextWeek));
        assertEquals(1, carry.add(player, "market_sale", 0.1, nextWeek));
    }

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
