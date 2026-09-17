package tfmc.justin.activity.utils;

import java.util.List;
import java.util.Locale;

// ====================================
// Marketblock's demand bar: '|' segments between gray brackets, the whole run
// tinted by how full it is - dark red at empty, green at full, with the unfilled
// segments in the same colour at a third of the brightness.
//
// Returns raw '#rrggbb' codes rather than colorized text so it stays free of
// Bukkit and can be unit tested. Callers pass it through Utils.colorize.
// ====================================
public final class Bar {

    // Endpoint colours matching the reference DemandFormatter: dark red at
    // empty, green at full.
    private static final int R1 = 0xaa, G1 = 0x00, B1 = 0x00;
    private static final int R2 = 0x00, G2 = 0xff, B2 = 0x00;

    // ====================================
    // A milestone segment. A marker drawn as '|' in the run's colour is
    // invisible, which is the one thing it must not be, so it is the heavy
    // vertical bar in a fixed colour of its own: gold while the milestone is
    // still ahead, aqua once the value has reached it. Reached is judged on
    // the value, not the rounded fill, which can cover a marker a point early.
    // ====================================
    private static final char SEGMENT = '|';
    private static final char MARKER = '┃';
    static final String MARKER_AHEAD = "#ffd700";
    static final String MARKER_REACHED = "#55ffff";

    private Bar() {
    }

    public static String render(int value, int max, int length) {
        return render(value, max, length, List.of());
    }

    // ====================================
    // The same bar with a marker on the segment each milestone sits at, so a
    // player can see where the rewards are. The marker sits one segment
    // before round(milestone / max * length): that is the segment which
    // fills the instant the milestone is reached, rather than the first
    // segment still to come. A milestone landing exactly on the end of the
    // bar marks the last segment rather than falling off it.
    // ====================================
    public static String render(int value, int max, int length, List<Integer> milestones) {
        double ratio = max <= 0 ? 0 : (double) value / max;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int filled = (int) Math.round(ratio * length);

        int r = (int) Math.round(R1 + (R2 - R1) * ratio);
        int g = (int) Math.round(G1 + (G2 - G1) * ratio);
        int b = (int) Math.round(B1 + (B2 - B1) * ratio);

        // The highest milestone on each segment, 0 for none: a shared segment
        // only shows as reached once every milestone on it is
        int[] markers = new int[length];
        if (max > 0 && length > 0) {
            for (Integer milestone : milestones) {
                if (milestone == null || milestone < 1) {
                    continue;
                }
                int position = (int) Math.round((double) milestone / max * length) - 1;
                int index = Math.max(0, Math.min(length - 1, position));
                markers[index] = Math.max(markers[index], milestone);
            }
        }

        String fill = hex(r, g, b);
        String dim = hex(r / 3, g / 3, b / 3);
        StringBuilder bar = new StringBuilder("#aaaaaa[");
        String current = null;
        for (int i = 0; i < length; i++) {
            String colour = markers[i] == 0 ? (i < filled ? fill : dim)
                : value >= markers[i] ? MARKER_REACHED : MARKER_AHEAD;
            if (!colour.equals(current)) {
                bar.append(colour);
                current = colour;
            }
            bar.append(markers[i] == 0 ? SEGMENT : MARKER);
        }
        return bar.append("#aaaaaa]").toString();
    }

    private static String hex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
