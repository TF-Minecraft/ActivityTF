package tfmc.justin.activity.utils;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeksTest {

    @Test
    void midWeekResolvesToThatMonday() {
        // Wednesday 2026-09-09
        assertEquals("2026-09-07", Weeks.weekKey(LocalDateTime.of(2026, 9, 9, 14, 30), DayOfWeek.MONDAY, 0));
    }

    @Test
    void resetMomentItselfStartsTheNewWeek() {
        assertEquals("2026-09-07", Weeks.weekKey(LocalDateTime.of(2026, 9, 7, 0, 0), DayOfWeek.MONDAY, 0));
    }

    @Test
    void beforeTheResetHourStillCountsAsThePreviousWeek() {
        assertEquals("2026-08-31", Weeks.weekKey(LocalDateTime.of(2026, 9, 7, 3, 0), DayOfWeek.MONDAY, 4));
    }

    @Test
    void sundayResetFromSaturdayResolvesToThePreviousSunday() {
        // Saturday 2026-09-12
        assertEquals("2026-09-06", Weeks.weekKey(LocalDateTime.of(2026, 9, 12, 10, 0), DayOfWeek.SUNDAY, 0));
    }

    @Test
    void weekCanStartInThePreviousYear() {
        // Friday 2027-01-01
        assertEquals("2026-12-28", Weeks.weekKey(LocalDateTime.of(2027, 1, 1, 12, 0), DayOfWeek.MONDAY, 0));
    }

    @Test
    void lateResetHourAtExactlyTheBoundaryStartsTheNewWeek() {
        // Monday 2026-09-07 23:00, reset hour 23
        assertEquals("2026-09-07", Weeks.weekKey(LocalDateTime.of(2026, 9, 7, 23, 0), DayOfWeek.MONDAY, 23));
    }

    @Test
    void oneMinuteBeforeALateResetHourIsStillThePreviousWeek() {
        // Monday 2026-09-07 22:59, reset hour 23
        assertEquals("2026-08-31", Weeks.weekKey(LocalDateTime.of(2026, 9, 7, 22, 59), DayOfWeek.MONDAY, 23));
    }

    @Test
    void sevenDaysAfterABoundaryStartsTheNextWeek() {
        assertEquals("2026-09-14", Weeks.weekKey(LocalDateTime.of(2026, 9, 14, 0, 0), DayOfWeek.MONDAY, 0));
    }

    @Test
    void dayKeyIsTheIsoDate() {
        assertEquals("2026-09-09", Weeks.dayKey(LocalDateTime.of(2026, 9, 9, 23, 59).toLocalDate()));
    }
}
