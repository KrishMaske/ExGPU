package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.RecurrencePattern;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecurrenceExpander} is a pure function — no clock, no Spring — so every case here is
 * exact and reproducible. See the Test plan's A1–A2.
 */
class RecurrenceExpanderTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    // ── A1 — expansion ─────────────────────────────────────────────────────────

    @Test
    void weekdaysTwentyOccurrences_fromAMonday_producesExactlyTwentyWeekdayWindows_spanningFourWeeks() {
        // 2026-06-01 is a Monday.
        Instant firstStart = Instant.parse("2026-06-01T09:00:00Z");
        Instant firstEnd = Instant.parse("2026-06-01T17:00:00Z");

        List<TimeWindow> windows = RecurrenceExpander.expand(
                firstStart, firstEnd, RecurrencePattern.WEEKDAYS, 20, UTC);

        assertThat(windows).hasSize(20);
        for (TimeWindow w : windows) {
            ZonedDateTime start = w.getStart().atZone(UTC);
            DayOfWeek dow = start.getDayOfWeek();
            assertThat(dow).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(start.toLocalTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(Duration.between(w.getStart(), w.getEnd())).isEqualTo(Duration.ofHours(8));
        }
        // 20 weekdays at 5/week is exactly 4 calendar weeks: Mon 2026-06-01 through
        // Fri 2026-06-26 (the 4th Friday), each week's Sat/Sun skipped.
        assertThat(windows.get(19).getStart().atZone(UTC).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 6, 26));
        Instant last = windows.get(19).getEnd();
        assertThat(Duration.between(firstStart, last).toDays()).isEqualTo(25);
    }

    @Test
    void weekdays_straddlingTwoWeekends_skipsBothWithoutConsumingAnOccurrence() {
        // Friday 2026-06-05 -> next occurrence must be Monday 2026-06-08, skipping Sat/Sun.
        Instant friday = Instant.parse("2026-06-05T09:00:00Z");
        Instant fridayEnd = Instant.parse("2026-06-05T10:00:00Z");

        List<TimeWindow> windows = RecurrenceExpander.expand(
                friday, fridayEnd, RecurrencePattern.WEEKDAYS, 2, UTC);

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).getStart().atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(windows.get(1).getStart().atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(windows.get(1).getStart().atZone(UTC).toLocalDate())
                .isEqualTo(windows.get(0).getStart().atZone(UTC).toLocalDate().plusDays(3));
    }

    @Test
    void dailySeven_advancesOneCalendarDayPerOccurrence() {
        Instant firstStart = Instant.parse("2026-06-01T09:00:00Z");
        Instant firstEnd = Instant.parse("2026-06-01T10:00:00Z");

        List<TimeWindow> windows = RecurrenceExpander.expand(
                firstStart, firstEnd, RecurrencePattern.DAILY, 7, UTC);

        assertThat(windows).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(windows.get(i).getStart()).isEqualTo(firstStart.plusSeconds(86_400L * i));
            assertThat(windows.get(i).getEnd()).isEqualTo(firstEnd.plusSeconds(86_400L * i));
        }
    }

    @Test
    void weeklyFour_advancesSevenCalendarDaysPerOccurrence() {
        Instant firstStart = Instant.parse("2026-06-01T09:00:00Z");
        Instant firstEnd = Instant.parse("2026-06-01T10:00:00Z");

        List<TimeWindow> windows = RecurrenceExpander.expand(
                firstStart, firstEnd, RecurrencePattern.WEEKLY, 4, UTC);

        assertThat(windows).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(windows.get(i).getStart()).isEqualTo(firstStart.plusSeconds(604_800L * i));
        }
        for (TimeWindow w : windows) {
            assertThat(w.getStart().atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        }
    }

    @Test
    void occurrencesBelowMinimum_throws() {
        Instant s = Instant.parse("2026-06-01T09:00:00Z");
        Instant e = Instant.parse("2026-06-01T10:00:00Z");
        assertThatThrownBy(() -> RecurrenceExpander.expand(s, e, RecurrencePattern.DAILY, 1, UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void occurrencesAboveMaximum_throws() {
        Instant s = Instant.parse("2026-06-01T09:00:00Z");
        Instant e = Instant.parse("2026-06-01T10:00:00Z");
        assertThatThrownBy(() -> RecurrenceExpander.expand(s, e, RecurrencePattern.DAILY, 61, UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── A2 — DST ───────────────────────────────────────────────────────────────

    @Test
    void daily_acrossTheLateMarchLondonDstTransition_keepsLocalWallClockTime_butShiftsUtcInstant() {
        ZoneId london = ZoneId.of("Europe/London");
        // 2026-03-27 is a Friday; the UK clocks spring forward on 2026-03-29 (last Sunday of
        // March), so a DAILY x5 run from Friday 2026-03-27 crosses the transition mid-series.
        ZonedDateTime firstStartLocal = ZonedDateTime.of(2026, 3, 27, 9, 0, 0, 0, london);
        ZonedDateTime firstEndLocal = ZonedDateTime.of(2026, 3, 27, 17, 0, 0, 0, london);

        List<TimeWindow> windows = RecurrenceExpander.expand(
                firstStartLocal.toInstant(), firstEndLocal.toInstant(), RecurrencePattern.DAILY, 5, london);

        assertThat(windows).hasSize(5);

        // Every occurrence starts at local 09:00, regardless of which side of the transition.
        for (TimeWindow w : windows) {
            assertThat(w.getStart().atZone(london).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(w.getEnd().atZone(london).toLocalTime()).isEqualTo(LocalTime.of(17, 0));
        }

        // Before the transition (27th, 28th): UTC 09:00 (GMT, UTC+0).
        assertThat(windows.get(0).getStart()).isEqualTo(Instant.parse("2026-03-27T09:00:00Z"));
        assertThat(windows.get(1).getStart()).isEqualTo(Instant.parse("2026-03-28T09:00:00Z"));
        // From the 29th onward (BST, UTC+1): local 09:00 is UTC 08:00 — the hour shift a naive
        // plus(Duration.ofDays(1)) on the UTC instant would silently fail to make.
        assertThat(windows.get(2).getStart()).isEqualTo(Instant.parse("2026-03-29T08:00:00Z"));
        assertThat(windows.get(3).getStart()).isEqualTo(Instant.parse("2026-03-30T08:00:00Z"));
        assertThat(windows.get(4).getStart()).isEqualTo(Instant.parse("2026-03-31T08:00:00Z"));
    }
}
