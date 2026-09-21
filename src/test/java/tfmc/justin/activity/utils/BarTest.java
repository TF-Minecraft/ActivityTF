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

    private static String segments(String bar) {
        return bar.replaceAll("#[0-9a-f]{6}", "").replace("[", "").replace("]", "");
    }

    @Test
    void emptyBarIsAllDimSegments() {
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

    @Test
    void milestonesMarkTheirSegment() {
        String bar = Bar.render(0, 20, 10, List.of(10, 20));

        assertEquals("||||┃||||┃", segments(bar));
    }

    @Test
    void aReachedMarkerIsAquaAndAnAheadMarkerIsGold() {
        assertEquals("#aaaaaa[#558000||||#55ffff┃#1c2a00||||#ffd700┃#aaaaaa]",
            Bar.render(10, 20, 10, List.of(10, 20)));
    }

    @Test
    void aMarkerOneSegmentShortOfFilledIsStillGold() {
        assertEquals("#aaaaaa[#666600||||#ffd700┃#222200|||||#aaaaaa]",
            Bar.render(8, 20, 10, List.of(10)));
    }

    @Test
    void aMarkerTheRoundedFillCoversIsStillGoldUntilReached() {
        assertEquals("#aaaaaa[#5e7300||||#ffd700┃#1f2600|||||#aaaaaa]",
            Bar.render(9, 20, 10, List.of(10)));
    }

    @Test
    void aSharedSegmentIsReachedOnlyOnceEveryMilestoneOnItIs() {
        assertTrue(Bar.render(9, 20, 2, List.of(9, 10)).contains(Bar.MARKER_AHEAD + "┃"));
        assertTrue(Bar.render(10, 20, 2, List.of(9, 10)).contains(Bar.MARKER_REACHED + "┃"));
    }

    @Test
    void markerColoursDoNotFollowTheFillGradient() {
        for (int value = 0; value <= 20; value++) {
            String bar = Bar.render(value, 20, 10, List.of(10));
            String expected = value >= 10 ? Bar.MARKER_REACHED : Bar.MARKER_AHEAD;
            assertTrue(bar.contains(expected + "┃"), "value " + value + ": " + bar);
        }
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

    @Test
    void milestonesMarkTheirComputedSegmentIndex() {
        String bar = segments(Bar.render(0, 50, 40, List.of(10, 20)));

        assertEquals('┃', bar.charAt(7));
        assertEquals('┃', bar.charAt(15));
        assertEquals(38, bar.chars().filter(c -> c == '|').count());
    }

    @Test
    void aMilestoneExactlyAtMaxMarksTheLastSegment() {
        String bar = segments(Bar.render(0, 50, 40, List.of(50)));

        assertEquals("|".repeat(39) + "┃", bar);
    }

    @Test
    void aMilestoneAboveMaxClampsToTheLastSegment() {
        String bar = segments(Bar.render(0, 50, 40, List.of(1000)));

        assertEquals("|".repeat(39) + "┃", bar);
    }

    @Test
    void theRunColourResumesAfterAMarker() {
        assertEquals("#aaaaaa[#00ff00" + "|".repeat(5) + "#55ffff┃#00ff00" + "|".repeat(4) + "#aaaaaa]",
            Bar.render(20, 20, 10, List.of(12)));
    }

    @Test
    void emptyMilestoneListMatchesTheThreeArgOverload() {
        assertEquals(Bar.render(10, 20, 10), Bar.render(10, 20, 10, List.of()));
        assertEquals(Bar.render(0, 20, 10), Bar.render(0, 20, 10, List.of()));
    }

    @Test
    void lengthOneStillPlacesTheMarker() {
        assertEquals("┃", segments(Bar.render(0, 20, 1, List.of(10))));
        assertEquals("┃", segments(Bar.render(0, 20, 1, List.of(1))));
    }
}
