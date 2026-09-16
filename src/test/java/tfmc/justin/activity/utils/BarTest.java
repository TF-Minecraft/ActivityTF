package tfmc.justin.activity.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarTest {

    private static String render(int points) {
        return Bar.render(points, 20, 10);
    }

    // Everything between the gray brackets, colour codes stripped
    private static String segments(String bar) {
        return bar.replaceAll("#[0-9a-f]{6}", "").replace("[", "").replace("]", "");
    }

    @Test
    void emptyBarIsAllDimSegments() {
        // 0xaa0000 at a third of the brightness
        assertEquals("#aaaaaa[#380000" + "|".repeat(10) + "#aaaaaa]", render(0));
    }

    @Test
    void fullBarIsAllGreenSegments() {
        assertEquals("#aaaaaa[#00ff00" + "|".repeat(10) + "#aaaaaa]", render(20));
    }

    @Test
    void halfBarInterpolatesAndDimsTheEmptyRun() {
        assertEquals("#aaaaaa[#558000" + "|".repeat(5) + "#1c2a00" + "|".repeat(5) + "#aaaaaa]", render(10));
    }

    @Test
    void halfASegmentRoundsUp() {
        // 1/20 of 10 segments is exactly 0.5, which Math.round takes up to 1
        assertEquals("#aaaaaa[#a20d00|#360400" + "|".repeat(9) + "#aaaaaa]", render(1));
    }

    @Test
    void segmentCountIsConstantAcrossEveryValue() {
        for (int points = -10; points <= 30; points++) {
            assertEquals(10, segments(render(points)).length(), "points=" + points);
        }
    }

    @Test
    void pointsOutsideRangeClamp() {
        assertEquals(render(20), render(150));
        assertEquals(render(0), render(-50));
    }

    @Test
    void maxOfZeroRendersEmptyInsteadOfDividingByZero() {
        assertEquals(render(0), Bar.render(5, 0, 10));
    }

    @Test
    void bracketsAlwaysWrapTheBar() {
        String bar = render(7);
        assertEquals("#aaaaaa[", bar.substring(0, 8));
        assertEquals("#aaaaaa]", bar.substring(bar.length() - 8));
    }

    @Test
    void zeroLengthBarIsJustTheBrackets() {
        assertEquals("#aaaaaa[#aaaaaa]", Bar.render(10, 20, 0));
    }

    @Test
    void barSegmentCountMatchesRequestedLength() {
        String bar = Bar.render(20, 40, 40);
        long pipes = bar.chars().filter(c -> c == '|').count();
        assertEquals(40, pipes);
    }

    @Test
    void colorizedBarHasNoLeftoverHexMarkersButHasHexColorCodes() {
        String colorized = Utils.colorize(Bar.render(10, 20, 10));

        assertFalse(colorized.contains("#"), "no raw '#' should remain: " + colorized);
        assertTrue(colorized.contains("§x"), "expected bungee hex color sequences: " + colorized);
    }

    // ====================================
    // The milestone markers: one segment each, at round(milestone / max *
    // length), keeping the colour of the run they land in.
    // ====================================
    @Test
    void milestonesMarkTheirSegment() {
        // max 20, length 10: 10 points marks segment index 5, 20 points lands
        // on index 10 - off the end, so it marks the last segment instead
        String bar = Bar.render(0, 20, 10, List.of(10, 20));

        assertEquals("|||||┃|||┃", segments(bar));
    }

    @Test
    void aMarkerKeepsTheColourOfItsPosition() {
        // Half full: both markers sit in the unfilled run and keep its colour
        assertEquals("#aaaaaa[#558000" + "|".repeat(5) + "#1c2a00┃|||┃#aaaaaa]",
            Bar.render(10, 20, 10, List.of(10, 20)));
    }

    @Test
    void milestonesOutsideTheBarAreIgnored() {
        assertEquals(render(0), Bar.render(0, 20, 10, List.of(0, -5)));
        assertEquals("#aaaaaa[#aaaaaa]", Bar.render(10, 20, 0, List.of(10)));
    }

    @Test
    void theSegmentCountIsUnchangedByMarkers() {
        assertEquals(10, segments(Bar.render(7, 20, 10, List.of(5, 10, 15, 20))).length());
    }
}
