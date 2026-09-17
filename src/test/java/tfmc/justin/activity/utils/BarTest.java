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
    // The milestone markers: one segment each, one before
    // round(milestone / max * length) - the segment that fills the instant
    // the milestone is reached - in their own colour: gold while ahead, aqua
    // once filled, independent of the run's gradient.
    // ====================================
    @Test
    void milestonesMarkTheirSegment() {
        // max 20, length 10: 10 points marks segment index 4, 20 points would
        // land on index 10 - off the end, so it marks the last segment instead
        String bar = Bar.render(0, 20, 10, List.of(10, 20));

        assertEquals("||||┃||||┃", segments(bar));
    }

    @Test
    void aReachedMarkerIsAquaAndAnAheadMarkerIsGold() {
        // value == milestone (10): that marker has filled; 20 is still ahead
        assertEquals("#aaaaaa[#558000||||#55ffff┃#1c2a00||||#ffd700┃#aaaaaa]",
            Bar.render(10, 20, 10, List.of(10, 20)));
    }

    @Test
    void aMarkerOneSegmentShortOfFilledIsStillGold() {
        // 8/20 fills 4 segments; the 10 marker is index 4, the next to fill
        assertEquals("#aaaaaa[#666600||||#ffd700┃#222200|||||#aaaaaa]",
            Bar.render(8, 20, 10, List.of(10)));
    }

    @Test
    void aMarkerTheRoundedFillCoversIsStillGoldUntilReached() {
        // 9/20 rounds to 5 filled segments, covering the 10 marker at index 4
        assertEquals("#aaaaaa[#5e7300||||#ffd700┃#1f2600|||||#aaaaaa]",
            Bar.render(9, 20, 10, List.of(10)));
    }

    @Test
    void aSharedSegmentIsReachedOnlyOnceEveryMilestoneOnItIs() {
        // length 2, max 20: 9 and 10 both land on index 0
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

    // max 50, length 40: 10/50*40 = 8.0 and 20/50*40 = 16.0, one segment
    // earlier than that (the segment that fills when the milestone is
    // reached) lands on indices 7 and 15
    @Test
    void milestonesMarkTheirComputedSegmentIndex() {
        String bar = segments(Bar.render(0, 50, 40, List.of(10, 20)));

        assertEquals('┃', bar.charAt(7));
        assertEquals('┃', bar.charAt(15));
        // every other segment stays the plain pipe
        assertEquals(38, bar.chars().filter(c -> c == '|').count());
    }

    @Test
    void aMilestoneExactlyAtMaxMarksTheLastSegment() {
        String bar = segments(Bar.render(0, 50, 40, List.of(50)));

        assertEquals("|".repeat(39) + "┃", bar);
    }

    // Pinning documented behaviour: a milestone above max (e.g. bar.max was
    // lowered after it was configured) is clamped to the last segment rather
    // than falling off the end of the array.
    @Test
    void aMilestoneAboveMaxClampsToTheLastSegment() {
        String bar = segments(Bar.render(0, 50, 40, List.of(1000)));

        assertEquals("|".repeat(39) + "┃", bar);
    }

    @Test
    void theRunColourResumesAfterAMarker() {
        // Bar is entirely full; 12/20*10 = 6.0, one segment earlier, so the
        // marker lands at index 5 and the green run picks up again after it
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
