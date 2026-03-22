package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One rentable GPU listing, as shown on the public marketplace.
 *
 * <p>This is intentionally <em>not</em> {@link OrderResponse}. The market endpoint is
 * readable without an account, so the projection drops {@code ownerId} — a browsing visitor
 * can see what is for rent and at what price, but not who is selling it or how to correlate
 * listings back to one provider. It also drops the order's internal lifecycle fields
 * ({@code status}, {@code filledQuantity}, {@code priorityTimestamp}), which describe the
 * order book's mechanics rather than the offer.
 *
 * <p>{@code availableGpus} is the remaining unfilled quantity, which is what a renter can
 * actually take — not the original listed quantity.
 */
public record SupplyListingResponse(
        UUID listingId,
        BigDecimal pricePerGpuHour,
        int availableGpus,
        Instant windowStart,
        Instant windowEnd,
        long windowHours,
        BigDecimal estimatedCostPerGpu
) {
    public static SupplyListingResponse from(Order order) {
        Instant start = order.getWindow().getStart();
        Instant end = order.getWindow().getEnd();
        long seconds = java.time.Duration.between(start, end).getSeconds();
        long hours = Math.max(1, seconds / 3600);

        BigDecimal estimated = order.getPricePerGpuHour()
                .multiply(BigDecimal.valueOf(seconds))
                .divide(BigDecimal.valueOf(3600), 4, java.math.RoundingMode.HALF_UP);

        return new SupplyListingResponse(
                order.getId(),
                order.getPricePerGpuHour(),
                order.remainingQuantity(),
                start,
                end,
                hours,
                estimated
        );
    }
}
