package com.exgpu.exgpu.domain;

import com.exgpu.exgpu.domain.enums.MatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchResult {

    @Builder.Default
    private List<Allocation> allocations = new ArrayList<>();

    @Builder.Default
    private List<Order> updatedOrders = new ArrayList<>();

    /**
     * One entry per counterparty (maker) actually mutated during matching. Populated by
     * {@link com.exgpu.exgpu.engine.MatchingEngine#submitOrder}; empty for a NO_MATCH result.
     * See {@link Fill} for what this is used for.
     */
    @Builder.Default
    private List<Fill> fills = new ArrayList<>();

    /**
     * The order that was submitted to produce this result — kept so
     * {@link com.exgpu.exgpu.engine.MatchingEngine#rollback} can unwind it (remove it from the
     * book) without the caller having to pass it separately. Null for a result that never
     * reached the engine.
     */
    private Order incoming;

    private MatchStatus status;

    public boolean hasAllocations() {
        return allocations != null && !allocations.isEmpty();
    }

    public int totalMatchedQuantity() {
        return allocations.stream()
                .mapToInt(Allocation::getQuantity)
                .sum();
    }

    @Override
    public String toString() {
        return "MatchResult{" +
                "status=" + status +
                ", allocations=" + allocations.size() +
                ", updatedOrders=" + updatedOrders.size() +
                ", fills=" + (fills == null ? 0 : fills.size()) +
                '}';
    }
}
