package tfmc.justin.activity.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarTest {

    private static String render(int points) {
        return Bar.render(points, 20, "#", ".", "", "");
    }

    @Test
    void emptyBarIsAllEmptyGlyphs() {
        assertEquals(".".repeat(20), render(0));
    }

    @Test
    void fullBarIsAllFilledGlyphs() {
        assertEquals("#".repeat(20), render(100));
    }

    @Test
    void partialBarRoundsDown() {
        assertEquals("#".repeat(12) + ".".repeat(8), render(63));
    }

    @Test
    void lengthIsConstantAcrossEveryValue() {
        for (int points = -10; points <= 110; points++) {
            assertEquals(20, render(points).length(), "points=" + points);
        }
    }

    @Test
    void colorsPrefixEachRun() {
        assertEquals("&a" + "#".repeat(10) + "&7" + ".".repeat(10),
            Bar.render(50, 20, "#", ".", "&a", "&7"));
    }

    @Test
    void ninetyNinePointsFloorsInsteadOfRounding() {
        assertEquals("#".repeat(19) + ".".repeat(1), render(99));
    }

    @Test
    void lengthOfOneProducesASingleGlyph() {
        assertEquals("#", Bar.render(100, 1, "#", ".", "", ""));
        assertEquals(".", Bar.render(0, 1, "#", ".", "", ""));
    }

    @Test
    void lengthOfZeroProducesAnEmptyString() {
        assertEquals("", Bar.render(50, 0, "#", ".", "", ""));
    }

    @Test
    void pointsAboveOneHundredClampToAFullBar() {
        assertEquals("#".repeat(20), render(150));
    }

    @Test
    void pointsBelowZeroClampToAnEmptyBar() {
        assertEquals(".".repeat(20), render(-50));
    }
}
