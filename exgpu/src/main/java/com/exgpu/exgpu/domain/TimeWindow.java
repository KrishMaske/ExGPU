package com.exgpu.exgpu.domain;
import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Embeddable;

@Embeddable
public class TimeWindow {
    private Instant start;
    private Instant end;

    protected TimeWindow() {}

    public TimeWindow(Instant start, Instant end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }
        this.start = start;
        this.end = end;
    }

    public boolean overlaps(TimeWindow other) {
        return !(this.end.isBefore(other.start) || this.start.isAfter(other.end));
    }

    public TimeWindow intersection(TimeWindow other) {
        return new TimeWindow(max(this.start, other.start), min(this.end, other.end));
    }

    public boolean contains(TimeWindow other) {
        return !this.start.isAfter(other.start) && !this.end.isBefore(other.end);
    }

    private Instant max(Instant start2, Instant start3) {
        if (start2.isAfter(start3)) {
            return start2;
        } 
        else {
            return start3;
        }
    }

    private Instant min(Instant end2, Instant end3) {
        if (end2.isBefore(end3)) {
            return end2;
        } 
        else {
            return end3;
        }
    }

    public long durationMinutes() {
        return Duration.between(start, end).toMinutes();
    }

    public Instant getStart() {
        return start;
    }
    public Instant getEnd() {
        return end;
    }

    @Override
    public String toString() {
        return "TimeWindow{" +
                "start=" + start +
                ", end=" + end +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TimeWindow that = (TimeWindow) o;

        return start.equals(that.start) && end.equals(that.end);
    }

    @Override
    public int hashCode() {
        return 31 * start.hashCode() + end.hashCode();
    }

}
