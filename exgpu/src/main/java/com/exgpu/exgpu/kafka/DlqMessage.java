package com.exgpu.exgpu.kafka;

public record DlqMessage(
        String eventId,
        String originalPayload,
        String errorType,
        String errorMessage,
        String failedAt
) {}
