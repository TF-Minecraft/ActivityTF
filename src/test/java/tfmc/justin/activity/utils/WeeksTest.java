package tfmc.justin.activity.utils;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
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

    // One clock, two keys: on both sides of the reset boundary the day key
    // must belong to the week key derived from the same moment, i.e.
    // weekKey(dayKey's date) still resolves to that same week key.
    @Test
    void dayAndWeekKeysStayConsistentAcrossTheMidnightBoundary() {
        LocalDateTime beforeMidnight = LocalDateTime.of(2026, 9, 6, 23, 59, 59);
        LocalDateTime afterMidnight = LocalDateTime.of(2026, 9, 7, 0, 0, 1);

        String weekBefore = Weeks.weekKey(beforeMidnight, DayOfWeek.MONDAY, 0);
        String dayBefore = Weeks.dayKey(beforeMidnight.toLocalDate());
        String weekAfter = Weeks.weekKey(afterMidnight, DayOfWeek.MONDAY, 0);
        String dayAfter = Weeks.dayKey(afterMidnight.toLocalDate());

        assertEquals("2026-08-31", weekBefore);
        assertEquals("2026-09-06", dayBefore);
        assertEquals("2026-09-07", weekAfter);
        assertEquals("2026-09-07", dayAfter);

        // The day key, re-fed as "now" at the start of that day, must resolve
        // to the same week key that was derived alongside it.
        assertEquals(weekBefore, Weeks.weekKey(LocalDate.parse(dayBefore).atStartOfDay(), DayOfWeek.MONDAY, 0));
        assertEquals(weekAfter, Weeks.weekKey(LocalDate.parse(dayAfter).atStartOfDay(), DayOfWeek.MONDAY, 0));
    }
}
