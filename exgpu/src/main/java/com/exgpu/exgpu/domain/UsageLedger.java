package com.exgpu.exgpu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import com.exgpu.exgpu.domain.enums.ChargeType;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_ledger")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UsageLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "allocation_id", nullable = false)
    private UUID allocationId;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    // Wall-clock seconds of usage being billed for this allocation. The GPU count is
    // applied separately (via allocation.quantity) in the cost formula, so this is NOT
    // already-multiplied "GPU-seconds" — hence the name usageSeconds, not gpuSeconds.
    @Column(name = "usage_seconds", nullable = false)
    private long usageSeconds;

    @Column(name = "token_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal tokenCost;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 20)
    @Builder.Default
    private ChargeType chargeType = ChargeType.USAGE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
