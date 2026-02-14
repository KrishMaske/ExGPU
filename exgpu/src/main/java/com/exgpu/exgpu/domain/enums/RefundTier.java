package com.exgpu.exgpu.domain.enums;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * How much of a booking is returned when a buyer cancels, based on how much notice they give.
 *
 * <p>The tiers exist because a cancellation costs the provider more the later it lands: hours
 * pulled off the market at short notice are unlikely to be resold before the window opens.
 * Notice is measured to the <em>start of the window</em>, not to the moment of booking.
 */
public enum RefundTier {
    /** 8 hours or more of notice — the capacity can realistically be resold. */
    FULL(new BigDecimal("1.00"), Duration.ofHours(8)),
    /** Between 4 and 8 hours — split the loss with the provider. */
    PARTIAL(new BigDecimal("0.50"), Duration.ofHours(4)),
    /** Under 4 hours, or the window has already started — the provider keeps the booking. */
    NONE(BigDecimal.ZERO, Duration.ZERO);

    private final BigDecimal rate;
    private final Duration minimumNotice;

    RefundTier(BigDecimal rate, Duration minimumNotice) {
        this.rate = rate;
        this.minimumNotice = minimumNotice;
    }

    /** Fraction of the booking charge returned, 0.00–1.00. */
    public BigDecimal rate() {
        return rate;
    }

    public Duration minimumNotice() {
        return minimumNotice;
    }

    /**
     * The tier that applies for a window starting at {@code windowStart}, cancelled at
     * {@code now}.
     *
     * <p>A window that has already started always yields {@link #NONE}: the notice period is
     * negative, so it cannot clear any threshold.
     */
    public static RefundTier forNotice(Instant now, Instant windowStart) {
        Duration notice = Duration.between(now, windowStart);
        if (notice.compareTo(FULL.minimumNotice) >= 0) return FULL;
        if (notice.compareTo(PARTIAL.minimumNotice) >= 0) return PARTIAL;
        return NONE;
    }
}
