package com.exgpu.exgpu.kafka;

import java.util.UUID;

/**
 * Kafka value for an incoming usage event. Mirrors {@link com.exgpu.exgpu.dto.SubmitUsageEventRequest}:
 * a producer reports only the event id, the allocation, and the seconds used. The payer and the
 * price are resolved server-side from the matched allocation, never taken from the message.
 */
public record UsageEventMessage(
        String eventId,
        UUID allocationId,
        long usageSeconds
) {}
