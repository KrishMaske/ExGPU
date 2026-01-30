package com.exgpu.exgpu.domain;

import com.exgpu.exgpu.domain.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One counterparty (maker) fill recorded by the matching engine during a single
 * {@code submitOrder} call.
 *
 * <p>{@code CLAUDE.md} names {@code Fill} as a wanted domain model that did not exist yet —
 * this fills that gap rather than inventing an unrelated type.
 *
 * <p>Two things read this record:
 * <ul>
 *   <li>{@link com.exgpu.exgpu.repository.OrderRepository#applyFill} — a conditional bulk
 *       UPDATE keyed on {@code orderId} and {@code quantityBefore} (the pre-fill quantity),
 *       used instead of a blind {@code save()} of the detached counterparty entity (D10).</li>
 *   <li>{@link com.exgpu.exgpu.engine.MatchingEngine#rollback} — compensates the in-memory
 *       book if the surrounding transaction rolls back (D8). {@code order} is kept as a direct
 *       reference (not just an id) so rollback mutates the exact instance the book may still
 *       be holding, without having to re-derive it from {@code MatchResult#getUpdatedOrders()}.</li>
 * </ul>
 *
 * <p>Only counterparties are recorded here — never the incoming order. The incoming order is a
 * managed JPA entity in the caller's own transaction, so Hibernate dirty-checking flushes its
 * mutations normally; it needs none of this machinery (D10).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fill {

    private UUID orderId;

    /** The live Order instance the engine mutated — see class Javadoc. */
    private Order order;

    private int quantityBefore;

    private int quantityFilled;

    private OrderStatus newStatus;

    /**
     * The allocation this fill produced. Explicit rather than left as an implicit "same index
     * in both lists" convention between {@link MatchResult#getAllocations()} and
     * {@link MatchResult#getFills()} — callers that need to correlate the two (drop an
     * allocation and compensate its fill together, per D9/D10) can do so directly off either
     * object.
     */
    private Allocation allocation;
}
