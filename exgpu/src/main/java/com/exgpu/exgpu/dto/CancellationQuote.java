package com.exgpu.exgpu.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What cancelling a rental would cost or return.
 *
 * <p>Doubles as the preview shown before confirming and the receipt returned after — the
 * shape is identical, so the UI renders one component for both and the buyer sees the same
 * numbers they were promised.
 *
 * @param tier            FULL, PARTIAL or NONE
 * @param refundRate      fraction of the booking returned, 0.00–1.00
 * @param bookingCharge   what was originally charged for this booking
 * @param refundAmount    what would be (or was) returned
 * @param noticeSeconds   time until the window opens; 0 once it has started
 * @param explanation     plain-language reason for the tier, for direct display
 */
public record CancellationQuote(
        UUID allocationId,
        boolean alreadyCancelled,
        boolean cancellable,
        String tier,
        BigDecimal refundRate,
        BigDecimal bookingCharge,
        BigDecimal refundAmount,
        long noticeSeconds,
        Instant windowStart,
        String explanation
) {}
