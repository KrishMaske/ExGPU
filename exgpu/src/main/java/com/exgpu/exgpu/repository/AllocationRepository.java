package com.exgpu.exgpu.repository;

import com.exgpu.exgpu.domain.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AllocationRepository extends JpaRepository<Allocation, UUID> {

    @Query("SELECT a FROM Allocation a WHERE a.buyOrderId = :orderId OR a.sellOrderId = :orderId ORDER BY a.createdAt ASC")
    List<Allocation> findByOrderId(@Param("orderId") UUID orderId);

    /** What this user is renting. {@code buyerId} is denormalized onto the allocation at match time. */
    List<Allocation> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId);

    /**
     * What this user is supplying. There is no {@code sellerId} column — the allocation only
     * records {@code sellOrderId} — so this resolves the owner through the originating SELL
     * order rather than denormalizing a second identity column.
     */
    @Query("""
            SELECT a FROM Allocation a
            WHERE a.sellOrderId IN (SELECT o.id FROM Order o WHERE o.ownerId = :sellerId)
            ORDER BY a.createdAt DESC
            """)
    List<Allocation> findBySellerId(@Param("sellerId") UUID sellerId);

    /**
     * True when the allocation belongs to this user on either side. Used to authorize access
     * to a single allocation without leaking whether an id exists for someone else.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM Allocation a
            WHERE a.id = :allocationId
              AND (a.buyerId = :userId
                   OR a.sellOrderId IN (SELECT o.id FROM Order o WHERE o.ownerId = :userId))
            """)
    boolean isPartyTo(@Param("allocationId") UUID allocationId, @Param("userId") UUID userId);
}
