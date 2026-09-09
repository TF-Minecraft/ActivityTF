package tfmc.justin.activity.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

// ====================================
// Week and day keys. Pure date maths so the rollover rules can be tested
// without a server: every entry point compares a stored key against the
// key for "now", which is cheaper and less fragile than a reset scheduler.
// ====================================
public final class Weeks {

    private Weeks() {
    }

    // The date of the reset boundary the given moment falls after
    public static String weekKey(LocalDateTime now, DayOfWeek resetDay, int resetHour) {
        LocalDateTime boundary = now.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(resetDay))
            .atTime(resetHour, 0);

        // Reset day but still before the reset hour - the week that is running
        // is the previous one
        if (now.isBefore(boundary)) {
            boundary = boundary.minusWeeks(1);
        }

        return boundary.toLocalDate().toString();
    }

    public static String dayKey(LocalDate date) {
        return date.toString();
    }
}
