package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.RecurrencePattern;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one seller-specified occurrence plus a {@link RecurrencePattern} into the concrete
 * list of windows a recurring listing expands to (D6/D7/A4).
 *
 * <p>Pure: no clock, no Spring, no I/O — a function of its five arguments only, which is what
 * makes it unit-testable across a real DST transition without a mocked clock.
 *
 * <p><b>Why wall-clock, not a fixed {@code Duration}:</b> "every weekday 09:00–17:00" is a
 * wall-clock concept. Advancing by a fixed {@code Duration.ofDays(1)} on the UTC instant would
 * silently shift every occurrence by an hour across a DST boundary, so a multi-week series
 * would list the wrong hours for the occurrences after the transition. Expanding with
 * {@link ZonedDateTime} + {@link LocalTime} in the caller's IANA zone keeps every occurrence at
 * the same local wall-clock time, which is what a seller listing "9 to 5" actually means.
 */
public final class RecurrenceExpander {

    private RecurrenceExpander() {
    }

    /**
     * @param firstStart  start of the first occurrence
     * @param firstEnd    end of the first occurrence (same or later calendar day — an
     *                    overnight window is supported, but is expected to be rare)
     * @param pattern     {@code DAILY} advances one day per occurrence; {@code WEEKDAYS}
     *                    advances one day per occurrence but skips Saturday/Sunday <em>without
     *                    consuming an occurrence</em> — a skipped weekend day does not count
     *                    toward {@code occurrences}; {@code WEEKLY} advances seven days per
     *                    occurrence
     * @param occurrences total windows to produce, {@code [2, 60]}
     * @param zone        IANA zone the wall-clock start/end times are held fixed in across
     *                    every occurrence
     * @return exactly {@code occurrences} windows, in chronological order
     */
    public static List<TimeWindow> expand(java.time.Instant firstStart, java.time.Instant firstEnd,
                                          RecurrencePattern pattern, int occurrences, ZoneId zone) {
        if (occurrences < 2 || occurrences > 60) {
            throw new IllegalArgumentException("occurrences must be between 2 and 60, was " + occurrences);
        }

        ZonedDateTime zStart = firstStart.atZone(zone);
        ZonedDateTime zEnd = firstEnd.atZone(zone);
        LocalTime startTime = zStart.toLocalTime();
        LocalTime endTime = zEnd.toLocalTime();
        // Usually 0 (a same-day window); supports an overnight window ending the next day.
        long endDayOffset = ChronoUnit.DAYS.between(zStart.toLocalDate(), zEnd.toLocalDate());

        int stepDays = pattern == RecurrencePattern.WEEKLY ? 7 : 1;

        List<TimeWindow> windows = new ArrayList<>(occurrences);
        LocalDate cursor = zStart.toLocalDate();
        int produced = 0;

        while (produced < occurrences) {
            boolean skip = pattern == RecurrencePattern.WEEKDAYS && isWeekend(cursor);
            if (!skip) {
                ZonedDateTime occStart = ZonedDateTime.of(cursor, startTime, zone);
                ZonedDateTime occEnd = ZonedDateTime.of(cursor.plusDays(endDayOffset), endTime, zone);
                windows.add(new TimeWindow(occStart.toInstant(), occEnd.toInstant()));
                produced++;
            }
            cursor = cursor.plusDays(stepDays);
        }

        return windows;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
