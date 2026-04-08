package com.exgpu.exgpu.domain.enums;

/**
 * The recurrence vocabulary for a seller's recurring listing (D7).
 *
 * <p>Deliberately three fixed patterns rather than an RFC 5545 RRULE subset: the entire
 * product surface is "repeat this listing", and a parser for adversarial recurrence rules is
 * not warranted for that. Stored as-is in {@code orders.recurrence_pattern VARCHAR(20)} (added
 * in V1); the occurrence count and timezone live in their own typed sibling columns rather than
 * being packed into the string. Adding a fourth pattern (e.g. {@code WEEKENDS},
 * {@code BIWEEKLY}) is one enum constant plus one CHECK-constraint value, not a parser change.
 */
public enum RecurrencePattern {
    DAILY,
    WEEKDAYS,
    WEEKLY
}
