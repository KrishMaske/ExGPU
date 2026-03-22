package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.enums.MatchStatus;

import java.util.List;

public record PlaceOrderResponse(
        OrderResponse order,
        MatchStatus matchStatus,
        int totalMatchedQuantity,
        List<AllocationResponse> allocations
) {}
