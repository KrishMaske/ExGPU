package com.exgpu.exgpu.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A top-up request. As with orders, the account credited is the authenticated caller —
 * never a UUID supplied in the body — so nobody can fund or inspect another user's balance.
 */
public record CreateBalanceRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
        // Cap matches the NUMERIC(18,6) column, so an over-large amount is a 400, not a 500.
        @DecimalMax(value = "999999999999.999999", message = "amount is too large")
        BigDecimal amount
) {}
