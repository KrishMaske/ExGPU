package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.domain.enums.RecurrencePattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID ownerId,
        OrderSide side,
        OrderStatus status,
        BigDecimal pricePerGpuHour,
        int quantity,
        int filledQuantity,
        int remainingQuantity,
        Instant windowStart,
        Instant windowEnd,
        Instant priorityTimestamp,
        Instant createdAt,
        boolean recurring,
        RecurrencePattern recurrencePattern,
        UUID parentOrderId,
        Integer occurrenceCount
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOwnerId(),
                order.getSide(),
                order.getStatus(),
                order.getPricePerGpuHour(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.remainingQuantity(),
                order.getWindow().getStart(),
                order.getWindow().getEnd(),
                order.getPriorityTimestamp(),
                order.getCreatedAt(),
                order.isRecurring(),
                order.getRecurrencePattern() == null ? null : RecurrencePattern.valueOf(order.getRecurrencePattern()),
                order.getParentOrderId(),
                order.getRecurrenceCount()
        );
    }
}
