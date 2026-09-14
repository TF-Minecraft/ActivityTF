package tfmc.justin.activity.utils;

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

    private Bar() {
    }

    public static String render(int value, int max, int length) {
        double ratio = max <= 0 ? 0 : (double) value / max;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int filled = (int) Math.round(ratio * length);

        int r = (int) Math.round(R1 + (R2 - R1) * ratio);
        int g = (int) Math.round(G1 + (G2 - G1) * ratio);
        int b = (int) Math.round(B1 + (B2 - B1) * ratio);

        StringBuilder bar = new StringBuilder("#aaaaaa[");
        if (filled > 0) {
            bar.append(hex(r, g, b)).append("|".repeat(filled));
        }
        int empty = length - filled;
        if (empty > 0) {
            bar.append(hex(r / 3, g / 3, b / 3)).append("|".repeat(empty));
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
