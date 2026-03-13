package com.exgpu.exgpu.realtime;

/**
 * The kinds of backend activity broadcast to WebSocket subscribers on /topic/events.
 * These describe things that already happened in existing flows — emitting them does not
 * change matching or billing behavior.
 */
public enum RealtimeEventType {
    /**
     * Public, identity-free signal that the order book moved, so the anonymous marketplace
     * page can refetch its listings. Carries no payload by design — anything describing
     * <em>who</em> traded or <em>what</em> it cost belongs on a per-user destination.
     */
    MARKET_UPDATED,
    ORDER_SUBMITTED,
    ORDER_FILLED,
    /** The owner cancelled a resting order (or a series parent cancelled its children). */
    ORDER_CANCELLED,
    /** An order's window passed while it still had unfilled capacity; the sweep closed it. */
    ORDER_EXPIRED,
    ALLOCATION_CREATED,
    USAGE_BILLED,
    BALANCE_UPDATED,
    DUPLICATE_USAGE_EVENT,
    COMPUTE_KILLED,
    /** A rental's window opened — the buyer can now fetch an access key. */
    ACCESS_GRANTED,
    /** A rental's access ended, either at its window end or by early revocation. */
    ACCESS_REVOKED,
    DLQ_EVENT_CREATED
}
