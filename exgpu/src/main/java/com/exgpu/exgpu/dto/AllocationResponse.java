package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.enums.AllocationStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A matched rental, as shown on a rental or supply card.
 *
 * <p>{@code status} and {@code createdAt} come straight from the entity. {@code lifecycle}
 * is derived at read time from the window and the clock, because the stored status does not
 * yet transition on its own — an allocation whose window has passed is still {@code ACTIVE}
 * in the database. Deriving it here keeps the UI honest without pretending the backend has a
 * scheduler it does not have.
 */
public record AllocationResponse(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        int quantity,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal executionPrice,
        AllocationStatus status,
        Instant createdAt,
        String lifecycle,
        long windowSeconds,
        BigDecimal maxCost
) {
    /** Window state relative to now: what a user actually means by "is this running?". */
    public enum Lifecycle {
        SCHEDULED, RUNNING, ENDED
    }

    public static AllocationResponse from(Allocation allocation) {
        Instant start = allocation.getWindow().getStart();
        Instant end = allocation.getWindow().getEnd();
        Instant now = Instant.now();

        Lifecycle lifecycle = now.isBefore(start) ? Lifecycle.SCHEDULED
                : now.isAfter(end) ? Lifecycle.ENDED
                : Lifecycle.RUNNING;

        long seconds = Duration.between(start, end).getSeconds();

        // The most this rental can ever cost: the whole window, billed at the agreed price.
        // Actual spend is usage-driven and will normally be lower.
        BigDecimal maxCost = allocation.getExecutionPrice() == null
                ? null
                : allocation.getExecutionPrice()
                        .multiply(BigDecimal.valueOf(seconds))
                        .multiply(BigDecimal.valueOf(allocation.getQuantity()))
                        .divide(BigDecimal.valueOf(3600), 6, java.math.RoundingMode.HALF_UP);

        return new AllocationResponse(
                allocation.getId(),
                allocation.getBuyOrderId(),
                allocation.getSellOrderId(),
                allocation.getQuantity(),
                start,
                end,
                allocation.getExecutionPrice(),
                allocation.getStatus(),
                allocation.getCreatedAt(),
                lifecycle.name(),
                seconds,
                maxCost
        );
    }
}
