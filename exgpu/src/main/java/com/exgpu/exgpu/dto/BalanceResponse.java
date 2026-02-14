package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.TokenBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID ownerId,
        BigDecimal balance,
        long version,
        Instant updatedAt
) {
    public static BalanceResponse from(TokenBalance tb) {
        return new BalanceResponse(tb.getBuyerId(), tb.getBalance(), tb.getVersion(), tb.getUpdatedAt());
    }

    /**
     * The balance of a user who has never topped up: zero tokens, no row in the database.
     *
     * <p>{@code version} is -1 rather than 0 to make "this account does not exist yet"
     * distinguishable from "a real row that has never been updated", and {@code updatedAt}
     * is null for the same reason. Nothing writes this back — it is a read-time projection.
     */
    public static BalanceResponse zeroFor(UUID ownerId) {
        return new BalanceResponse(ownerId, BigDecimal.ZERO, -1L, null);
    }
}
