package tfmc.justin.activity.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarTest {

    private static String render(int points) {
        return Bar.render(points, 20, 10, "#", ".", "", "");
    }

    @Test
    void emptyBarIsAllEmptyGlyphs() {
        assertEquals(".".repeat(10), render(0));
    }

    @Test
    void fullBarIsAllFilledGlyphs() {
        assertEquals("#".repeat(10), render(20));
    }

    @Test
    void partialBarRoundsDown() {
        assertEquals("#".repeat(6) + ".".repeat(4), render(13));
    }

    @Test
    void lengthIsConstantAcrossEveryValue() {
        for (int points = -10; points <= 30; points++) {
            assertEquals(10, render(points).length(), "points=" + points);
        }
    }

    @Test
    void colorsPrefixEachRun() {
        assertEquals("&a" + "#".repeat(5) + "&7" + ".".repeat(5),
            Bar.render(10, 20, 10, "#", ".", "&a", "&7"));
    }

    @Test
    void lengthOfOneProducesASingleGlyph() {
        assertEquals("#", Bar.render(20, 20, 1, "#", ".", "", ""));
        assertEquals(".", Bar.render(0, 20, 1, "#", ".", "", ""));
    }

    @Test
    void lengthOfZeroProducesAnEmptyString() {
        assertEquals("", Bar.render(10, 20, 0, "#", ".", "", ""));
    }

    @Test
    void pointsOutsideRangeClamp() {
        assertEquals("#".repeat(10), render(150));
        assertEquals(".".repeat(10), render(-50));
    }

    @Test
    void maxOfZeroRendersEmptyInsteadOfDividingByZero() {
        assertEquals(".".repeat(10), Bar.render(5, 0, 10, "#", ".", "", ""));
    }
}
