package com.exgpu.exgpu.domain.enums;

public enum OrderStatus {
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    EXPIRED,
    CANCELLED,

    /**
     * A recurring seller listing's series header — not one of the five states {@code
     * CLAUDE.md} describes for an order's lifecycle, and deliberately so.
     *
     * <p>{@code CLAUDE.md}'s lifecycle list ({@code Placed, PartiallyFilled, Filled, Expired,
     * Cancelled}) describes the states of a <em>matchable</em> order — something that can sit
     * in the book and trade. A {@code TEMPLATE} row never does either of those things: it is
     * the parent {@code orders} row created for a recurring SELL listing (see
     * {@code RecurrencePattern}), carrying {@code recurring}, {@code recurrencePattern},
     * {@code recurrenceCount} and {@code recurrenceZone} plus the series envelope window, while
     * the concrete, individually-matchable occurrences are ordinary child rows with
     * {@code parentOrderId} pointing back here.
     *
     * <p>This is a deliberate, documented deviation, not drift. {@link
     * com.exgpu.exgpu.domain.Order#isMatchable()} already returns false for anything outside
     * {@code OPEN}/{@code PARTIALLY_FILLED}, and every query that selects the live book or
     * market ({@code OrderRepository.findAvailableSupply}, {@code findOpenDemand},
     * {@code findByStatusIn}) already filters on those two statuses. A {@code TEMPLATE} row is
     * therefore automatically excluded from the book, the marketplace, and startup rehydration
     * with zero query changes — while still surfacing through {@code findByOwnerId} so a
     * seller sees their series header in "My Orders". The alternative considered was a
     * separate {@code order_series} table with its own entity, repository and migration; a
     * sixth status on the existing table was judged the smaller, more consistent change.
     *
     * <p>{@code filledQuantity} on a {@code TEMPLATE} row stays 0 and is meaningless — fills
     * only ever happen on children, since the parent never enters the book.
     */
    TEMPLATE
}
