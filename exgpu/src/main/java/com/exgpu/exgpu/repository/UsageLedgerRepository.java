package com.exgpu.exgpu.repository;

import com.exgpu.exgpu.domain.UsageLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsageLedgerRepository extends JpaRepository<UsageLedger, UUID> {

    Optional<UsageLedger> findByIdempotencyKey(String idempotencyKey);

    /** This user's billing history. Scoped by buyer so one account can never read another's spend. */
    List<UsageLedger> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId);

    /**
     * Total metered seconds reported against an allocation.
     *
     * <p>Restricted to {@code USAGE} rows. A {@code BOOKING} row records the full window
     * length, so counting it here would make the cumulative cap reject the very first usage
     * event on every allocation; {@code REFUND} rows carry zero seconds and are irrelevant.
     *
     * <p>COALESCE returns 0 rather than null when nothing has been reported, so callers can
     * treat the result as a plain long.
     */
    @Query("""
            SELECT COALESCE(SUM(u.usageSeconds), 0) FROM UsageLedger u
             WHERE u.allocationId = :allocationId
               AND u.chargeType = com.exgpu.exgpu.domain.enums.ChargeType.USAGE
            """)
    long sumUsageSecondsByAllocationId(@Param("allocationId") UUID allocationId);
}
