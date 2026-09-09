package tfmc.justin.activity.utils;

// ====================================
// The 0-100 progress bar. Returns raw colour codes rather than colorized
// text so it stays free of Bukkit and can be unit tested.
// ====================================
public final class Bar {

    private Bar() {
    }

    public static String render(int points, int length, String filledChar, String emptyChar,
                                String filledColor, String emptyColor) {
        int clamped = Math.max(0, Math.min(100, points));
        int filled = clamped * length / 100;

        return filledColor + filledChar.repeat(filled)
            + emptyColor + emptyChar.repeat(length - filled);
    }
}
