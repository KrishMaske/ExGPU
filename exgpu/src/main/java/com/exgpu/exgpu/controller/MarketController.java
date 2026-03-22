package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.dto.DemandListingResponse;
import com.exgpu.exgpu.dto.SupplyListingResponse;
import com.exgpu.exgpu.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The public shop window: GPU capacity that is currently rentable.
 *
 * <p>This is the one part of the API readable without an account — someone evaluating the
 * platform has to be able to see what is on offer and at what price before signing up.
 * Because of that, everything here is projected through {@link SupplyListingResponse}, which
 * carries the offer (price, GPUs available, time window) and nothing that identifies the
 * provider behind it.
 */
@RestController
@RequestMapping("/market")
public class MarketController {

    private final OrderService orderService;

    public MarketController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Rentable listings, cheapest first. Excludes filled capacity and past windows.
     *
     * <p>Readable anonymously, but personalised when a token is present: your own listings are
     * hidden, because the engine will not let you rent from yourself.
     */
    @GetMapping("/supply")
    public List<SupplyListingResponse> getAvailableSupply() {
        return orderService.findAvailableSupply(CurrentUser.idOrNull());
    }

    /**
     * Open buy requests a provider could fill, best-paying first.
     *
     * <p>Requires authentication, unlike supply. Supply is a shop window that has to be
     * browsable before signing up; demand is only actionable by someone who can actually
     * place a sell order, and it is the half of the book where knowing who wants what has
     * more competitive value.
     */
    @GetMapping("/demand")
    public List<DemandListingResponse> getOpenDemand() {
        return orderService.findOpenDemand(CurrentUser.id());
    }
}
