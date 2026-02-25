package com.exgpu.exgpu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A usage event carries only what telemetry can legitimately assert: which event it is
 * (for idempotency), which allocation it belongs to, and how many seconds were used.
 *
 * The payer (buyerId) and the price are NOT accepted from the caller — billing derives
 * them from the matched allocation, so a producer cannot bill a different buyer or change
 * the agreed price.
 */
public record SubmitUsageEventRequest(
        @NotBlank(message = "eventId is required")
        @Size(max = 255, message = "eventId must be at most 255 characters")
        String eventId,
        @NotNull(message = "allocationId is required")
        UUID allocationId,
        // Upper bound (366 days) keeps the value sane and prevents the cumulative window
        // check (alreadyBilled + usageSeconds) from overflowing a long.
        @Min(value = 1, message = "usageSeconds must be at least 1")
        @Max(value = 31_622_400L, message = "usageSeconds is too large")
        long usageSeconds
) {}
