package tfmc.justin.activity.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

public final class Weeks {

    private Weeks() {
    }

    public static String weekKey(LocalDateTime now, DayOfWeek resetDay, int resetHour) {
        LocalDateTime boundary = now.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(resetDay))
            .atTime(resetHour, 0);

        if (now.isBefore(boundary)) {
            boundary = boundary.minusWeeks(1);
        }

        return boundary.toLocalDate().toString();
    }

    public static String dayKey(LocalDate date) {
        return date.toString();
    }
}
