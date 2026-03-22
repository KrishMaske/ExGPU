package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.enums.OrderSide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order as submitted by a client.
 *
 * <p>There is deliberately no {@code ownerId} field: the owner is taken from the verified
 * Supabase JWT via {@link com.exgpu.exgpu.config.CurrentUser}. Accepting it from the body
 * would let any caller place orders in someone else's name.
 *
 * <p>{@code recurrence} is optional — an absent field (the case for every existing client and
 * every literal JSON payload in the test suite) places one ordinary order exactly as before.
 * When present it turns this into a recurring SELL listing (D6); {@code OrderService} rejects
 * it on a BUY.
 */
public record CreateOrderRequest(

        @NotNull(message = "side is required")
        OrderSide side,

        @NotNull(message = "pricePerGpuHour is required")
        @DecimalMin(value = "0.0001", message = "pricePerGpuHour must be greater than 0")
        @DecimalMax(value = "999999.9999", message = "pricePerGpuHour is too large")
        BigDecimal pricePerGpuHour,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1_000_000, message = "quantity is too large")
        int quantity,

        @NotNull(message = "startTime is required")
        Instant startTime,

        @NotNull(message = "endTime is required")
        Instant endTime,

        @Valid
        RecurrenceSpec recurrence
) {}
