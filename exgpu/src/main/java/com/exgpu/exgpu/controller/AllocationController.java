package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.service.AllocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Allocations the caller is a party to.
 *
 * <p>The previous {@code GET /allocations} returned every allocation on the exchange —
 * every trade, its price, and its counterparties. It now returns only the caller's own, as
 * the union of what they rent and what they supply.
 */
@RestController
@RequestMapping("/allocations")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @GetMapping
    public List<AllocationResponse> getMyAllocations() {
        UUID me = CurrentUser.id();

        // A user can be on both sides of the same allocation only in the degenerate case of
        // trading with themselves; dedupe by id so it appears once either way.
        List<AllocationResponse> combined = new ArrayList<>(allocationService.findMyRentals(me));
        for (AllocationResponse supplied : allocationService.findMySupply(me)) {
            if (combined.stream().noneMatch(a -> a.id().equals(supplied.id()))) {
                combined.add(supplied);
            }
        }
        combined.sort(Comparator.comparing(AllocationResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return combined;
    }
}
