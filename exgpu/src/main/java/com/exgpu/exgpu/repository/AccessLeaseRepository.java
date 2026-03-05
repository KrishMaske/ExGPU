package com.exgpu.exgpu.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.exgpu.exgpu.domain.AccessLease;
import com.exgpu.exgpu.domain.enums.LeaseStatus;

@Repository
public interface AccessLeaseRepository extends JpaRepository<AccessLease, UUID> {

    Optional<AccessLease> findByAllocationId(UUID allocationId);

    List<AccessLease> findByBuyerIdOrderByWindowStartDesc(UUID buyerId);

    List<AccessLease> findByAllocationIdIn(List<UUID> allocationIds);

    boolean existsByAllocationId(UUID allocationId);

    /**
     * Opens access for every lease whose window has arrived.
     *
     * <p>Idempotent by construction: the {@code status = PENDING} predicate is part of the
     * UPDATE, so the second run matches zero rows. That also makes it safe under concurrency
     * — two schedulers racing produce one winner and one no-op, with the database's row locks
     * doing the arbitration rather than application-level coordination.
     *
     * <p>{@code activated_at} is only set when it is still null, so a replay cannot rewrite
     * the original activation time.
     *
     * @return number of leases actually opened by this call
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AccessLease l
               SET l.status = com.exgpu.exgpu.domain.enums.LeaseStatus.ACTIVE,
                   l.activatedAt = COALESCE(l.activatedAt, :now),
                   l.version = l.version + 1
             WHERE l.status = com.exgpu.exgpu.domain.enums.LeaseStatus.PENDING
               AND l.windowStart <= :now
               AND l.windowEnd   >  :now
            """)
    int activateDueLeases(@Param("now") Instant now);

    /**
     * Closes access for every lease whose window has passed.
     *
     * <p>Covers PENDING as well as ACTIVE: a short window can open and close between two
     * scheduler ticks, and such a lease must still reach EXPIRED rather than being stranded
     * in PENDING forever.
     *
     * @return number of leases actually expired by this call
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AccessLease l
               SET l.status = com.exgpu.exgpu.domain.enums.LeaseStatus.EXPIRED,
                   l.endedAt = COALESCE(l.endedAt, :now),
                   l.version = l.version + 1
             WHERE l.status IN (com.exgpu.exgpu.domain.enums.LeaseStatus.PENDING,
                                com.exgpu.exgpu.domain.enums.LeaseStatus.ACTIVE)
               AND l.windowEnd <= :now
            """)
    int expireEndedLeases(@Param("now") Instant now);

    /**
     * Revokes every still-live lease belonging to one buyer — the KillCompute path.
     *
     * <p>Also idempotent: already-terminal leases are excluded, so re-firing KillCompute for
     * a buyer whose access is already gone changes nothing and reports zero.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AccessLease l
               SET l.status = com.exgpu.exgpu.domain.enums.LeaseStatus.REVOKED,
                   l.endedAt = COALESCE(l.endedAt, :now),
                   l.revokeReason = :reason,
                   l.version = l.version + 1
             WHERE l.buyerId = :buyerId
               AND l.status IN (com.exgpu.exgpu.domain.enums.LeaseStatus.PENDING,
                                com.exgpu.exgpu.domain.enums.LeaseStatus.ACTIVE)
            """)
    int revokeAllForBuyer(@Param("buyerId") UUID buyerId,
                          @Param("reason") com.exgpu.exgpu.domain.enums.RevokeReason reason,
                          @Param("now") Instant now);

    /**
     * Revokes the lease for one allocation — the cancellation path.
     *
     * <p>Conditional on the lease still being live, so cancelling an already-ended rental
     * changes nothing and reports zero rather than rewriting a terminal row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AccessLease l
               SET l.status = com.exgpu.exgpu.domain.enums.LeaseStatus.REVOKED,
                   l.endedAt = COALESCE(l.endedAt, :now),
                   l.revokeReason = :reason,
                   l.version = l.version + 1
             WHERE l.allocationId = :allocationId
               AND l.status IN (com.exgpu.exgpu.domain.enums.LeaseStatus.PENDING,
                                com.exgpu.exgpu.domain.enums.LeaseStatus.ACTIVE)
            """)
    int revokeForAllocation(@Param("allocationId") UUID allocationId,
                            @Param("reason") com.exgpu.exgpu.domain.enums.RevokeReason reason,
                            @Param("now") Instant now);

    /** Leases in a given state — used by the scheduler to emit "access is open now" notifications. */
    List<AccessLease> findByStatusAndActivatedAtAfter(LeaseStatus status, Instant after);

    long countByStatus(LeaseStatus status);
}
