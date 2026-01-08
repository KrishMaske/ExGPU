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
                '}';
    }
}