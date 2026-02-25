package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.UsageLedger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UsageEventResponse(
        UUID ledgerId,
        UUID allocationId,
        UUID buyerId,
        long usageSeconds,
        BigDecimal cost,
        BigDecimal remainingBalance,
        boolean computeKilled,
        boolean duplicate,
        Instant createdAt
) {
    public static UsageEventResponse of(UsageLedger ledger, BigDecimal remainingBalance,
                                        boolean computeKilled, boolean duplicate) {
        return new UsageEventResponse(
                ledger.getId(), ledger.getAllocationId(), ledger.getBuyerId(),
                ledger.getUsageSeconds(), ledger.getTokenCost(),
                remainingBalance, computeKilled, duplicate, ledger.getCreatedAt()
        );
    }
}
