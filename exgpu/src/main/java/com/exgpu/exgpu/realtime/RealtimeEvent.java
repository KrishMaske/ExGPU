package com.exgpu.exgpu.realtime;

import java.time.Instant;

/**
 * A single real-time activity event pushed to WebSocket clients.
 *
 * @param id        unique id for this event (lets the UI de-duplicate / key lists)
 * @param type      the event type, e.g. ORDER_FILLED
 * @param message   a short human-readable description for the activity feed/toast
 * @param entityId  the id of the related domain object (order, allocation, ledger, owner…)
 * @param payload   an arbitrary JSON-serializable detail object for the UI to render
 * @param createdAt server timestamp the event was produced
 */
public record RealtimeEvent(
        String id,
        RealtimeEventType type,
        String message,
        String entityId,
        Object payload,
        Instant createdAt
) {}
