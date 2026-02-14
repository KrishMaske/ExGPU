package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.UsageLedger;
import com.exgpu.exgpu.domain.enums.ChargeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of billing history.
 *
 * <p>{@code chargeType} matters to the reader, not just the system: a statement that shows a
 * $48 booking, a $24 refund and a metered usage row all as undifferentiated numbers is
 * unreadable. It is also what tells the UI which rows are money movements ({@code BOOKING},
 * {@code REFUND}) and which are informational ({@code USAGE}, always zero-cost since billing
 * moved to the booked window).
 *
 * <p>{@code tokenCost} is negative on refunds.
 */
public record UsageLedgerResponse(
        UUID id,
        UUID allocationId,
        UUID buyerId,
        long usageSeconds,
        BigDecimal tokenCost,
        ChargeType chargeType,
        String idempotencyKey,
        Instant createdAt
) {
    public static UsageLedgerResponse from(UsageLedger ledger) {
        return new UsageLedgerResponse(
                ledger.getId(), ledger.getAllocationId(), ledger.getBuyerId(),
                ledger.getUsageSeconds(), ledger.getTokenCost(), ledger.getChargeType(),
                ledger.getIdempotencyKey(), ledger.getCreatedAt()
        );
    }
}
