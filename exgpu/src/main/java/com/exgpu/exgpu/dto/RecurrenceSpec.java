package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.enums.RecurrencePattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Turns a single {@link CreateOrderRequest} into a recurring seller listing (D6/D7).
 *
 * <p>Optional and nullable on {@code CreateOrderRequest} — an absent {@code recurrence} field
 * places an ordinary, non-recurring order exactly as before.
 *
 * @param pattern     how occurrences repeat
 * @param occurrences how many concrete children to create; {@code [2, 60]}, also enforced by a
 *                     DB CHECK so the bound holds even for a row written by something other
 *                     than this validated path
 * @param zoneId       IANA zone id occurrences are expanded in — a wall-clock concept ("every
 *                     weekday 09:00–17:00"), so this must be a real zone rather than a fixed
 *                     UTC offset for the DST correctness in D7. Null defaults to {@code "UTC"}.
 */
public record RecurrenceSpec(
        @NotNull(message = "pattern is required")
        RecurrencePattern pattern,

        @Min(value = 2, message = "occurrences must be at least 2")
        @Max(value = 60, message = "occurrences must be at most 60")
        int occurrences,

        String zoneId
) {}
