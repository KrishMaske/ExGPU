package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One unfilled buy order, as shown to providers looking for demand to fill.
 *
 * <p>The mirror image of {@link SupplyListingResponse}: it publishes the terms of the request
 * — how many GPUs, the most the buyer will pay, and when — and deliberately omits
 * {@code ownerId}, so a provider sees an opportunity rather than a person.
 *
 * <p>{@code maxRevenue} is what filling the whole remaining quantity at the buyer's bid would
 * earn. It is the number a provider actually decides on, so the server computes it once
 * rather than leaving every client to re-derive it.
 */
public record DemandListingResponse(
        UUID requestId,
        BigDecimal maxPricePerGpuHour,
        int gpusWanted,
        Instant windowStart,
        Instant windowEnd,
        long windowHours,
        BigDecimal maxRevenue
) {
    public static DemandListingResponse from(Order buyOrder) {
        Instant start = buyOrder.getWindow().getStart();
        Instant end = buyOrder.getWindow().getEnd();
        long seconds = java.time.Duration.between(start, end).getSeconds();
        int wanted = buyOrder.remainingQuantity();

        BigDecimal maxRevenue = buyOrder.getPricePerGpuHour()
                .multiply(BigDecimal.valueOf(seconds))
                .multiply(BigDecimal.valueOf(wanted))
                .divide(BigDecimal.valueOf(3600), 4, java.math.RoundingMode.HALF_UP);

        return new DemandListingResponse(
                buyOrder.getId(),
                buyOrder.getPricePerGpuHour(),
                wanted,
                start,
                end,
                Math.max(1, seconds / 3600),
                maxRevenue
        );
    }
}
