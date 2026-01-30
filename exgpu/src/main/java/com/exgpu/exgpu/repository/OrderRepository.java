package com.exgpu.exgpu.repository;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByStatusIn(List<OrderStatus> statuses, Sort sort);

    /** Everything one user has placed, both sides. Backs "My Orders". */
    List<Order> findByOwnerId(UUID ownerId, Sort sort);

    List<Order> findByOwnerIdAndSide(UUID ownerId, OrderSide side, Sort sort);

    /**
     * Rentable supply: SELL orders that still have unfilled capacity and whose window has
     * not already ended. This is the anonymous shop window, so callers must map it to a DTO
     * that omits {@code ownerId} — the listing is public, the seller's identity is not.
     */
    @Query("""
            SELECT o FROM Order o
            WHERE o.side = com.exgpu.exgpu.domain.enums.OrderSide.SELL
              AND o.status IN (com.exgpu.exgpu.domain.enums.OrderStatus.OPEN,
                               com.exgpu.exgpu.domain.enums.OrderStatus.PARTIALLY_FILLED)
              AND o.filledQuantity < o.quantity
              AND o.window.end > :now
            ORDER BY o.pricePerGpuHour ASC, o.priorityTimestamp ASC
            """)
    List<Order> findAvailableSupply(@Param("now") Instant now);

    /**
     * Unfilled BUY demand a provider could fill: buy orders with capacity still wanted and a
     * window that has not closed, best-paying first.
     *
     * <p>{@code ownerId <> :viewer} implements self-trade prevention at the listing level.
     * The engine already refuses to match an order against its owner, but showing a provider
     * their own buy request as a fillable opportunity would be a dead end they can click.
     */
    @Query("""
            SELECT o FROM Order o
            WHERE o.side = com.exgpu.exgpu.domain.enums.OrderSide.BUY
              AND o.status IN (com.exgpu.exgpu.domain.enums.OrderStatus.OPEN,
                               com.exgpu.exgpu.domain.enums.OrderStatus.PARTIALLY_FILLED)
              AND o.filledQuantity < o.quantity
              AND o.window.end > :now
              AND o.ownerId <> :viewer
            ORDER BY o.pricePerGpuHour DESC, o.priorityTimestamp ASC
            """)
    List<Order> findOpenDemand(@Param("now") Instant now, @Param("viewer") UUID viewer);

    /** Concrete occurrences of a recurring series, in creation order (D6). */
    List<Order> findByParentOrderId(UUID parentOrderId);

    /**
     * Conditional counterparty fill write (D10) — replaces {@code orderRepository.save()} of a
     * detached engine-book entity, which would blind-{@code merge} whatever the row currently
     * holds. Guarding on both {@code id} and the pre-fill quantity turns "the book disagreed
     * with the row" into a detectable rowcount-0 rather than a silent lost update.
     *
     * @return 1 if the row matched and was updated; 0 if the row has since diverged from the
     *         engine's view (already terminal, or {@code filledQuantity} no longer matches
     *         {@code expectedFilled}) — the caller must compensate the engine-side fill and
     *         drop the associated allocation rather than persist it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.filledQuantity = o.filledQuantity + :qty,
                   o.status = :newStatus
             WHERE o.id = :id
               AND o.filledQuantity = :expectedFilled
               AND o.status IN (com.exgpu.exgpu.domain.enums.OrderStatus.OPEN,
                                com.exgpu.exgpu.domain.enums.OrderStatus.PARTIALLY_FILLED)
            """)
    int applyFill(@Param("id") UUID id, @Param("qty") int qty,
                  @Param("expectedFilled") int expectedFilled,
                  @Param("newStatus") OrderStatus newStatus);

    /**
     * Conditional bulk expiry sweep (B4/D11) — the same idempotent-conditional-UPDATE idiom as
     * {@code AccessLeaseRepository.activateDueLeases}: the WHERE clause includes the state
     * being moved out of, so a repeated or late tick self-heals rather than double-applying.
     *
     * @return number of rows actually moved to EXPIRED by this call
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = com.exgpu.exgpu.domain.enums.OrderStatus.EXPIRED,
                   o.expiredAt = COALESCE(o.expiredAt, :now)
             WHERE o.status IN (com.exgpu.exgpu.domain.enums.OrderStatus.OPEN,
                                com.exgpu.exgpu.domain.enums.OrderStatus.PARTIALLY_FILLED)
               AND o.window.end <= :now
            """)
    int expirePastWindows(@Param("now") Instant now);
}
