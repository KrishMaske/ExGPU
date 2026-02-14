package com.exgpu.exgpu.domain.enums;

/**
 * What a ledger row represents.
 *
 * <p>Billing is per <em>booked window</em>, not per observed usage: reserving capacity
 * withdraws it from the market whether or not the buyer runs anything, so the provider has
 * sold those hours either way.
 */
public enum ChargeType {
    /** Full-window charge taken when an allocation is created. Positive amount. */
    BOOKING,
    /**
     * Telemetry-derived metering. Recorded for observability and for the Kafka pipeline, but
     * no longer deducts from the balance — the booking charge already covered the window.
     */
    USAGE,
    /** Money returned on cancellation. Negative amount. */
    REFUND
}
