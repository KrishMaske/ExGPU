package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.dto.CreateOrderRequest;
import com.exgpu.exgpu.dto.FillDemandRequest;
import com.exgpu.exgpu.dto.OrderResponse;
import com.exgpu.exgpu.dto.PlaceOrderResponse;
import com.exgpu.exgpu.service.AllocationService;
import com.exgpu.exgpu.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Order endpoints, all scoped to the authenticated caller.
 *
 * <p>Every read here answers "what are <em>my</em> orders?" — there is deliberately no
 * endpoint that returns the whole book, because owner ids and unfilled positions are other
 * people's business. Public, anonymized supply lives on {@link MarketController} instead.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final AllocationService allocationService;

    public OrderController(OrderService orderService, AllocationService allocationService) {
        this.orderService = orderService;
        this.allocationService = allocationService;
    }

    /** Places an order owned by the caller. The body carries no owner id — see CreateOrderRequest. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceOrderResponse placeOrder(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.placeOrder(request, CurrentUser.id());
    }

    /**
     * The caller's orders, optionally narrowed to one side — {@code ?side=BUY} for rentals
     * they have requested, {@code ?side=SELL} for capacity they have listed.
     */
    @GetMapping("/me")
    public List<OrderResponse> getMyOrders(@RequestParam(required = false) OrderSide side) {
        UUID me = CurrentUser.id();
        return side == null ? orderService.findMine(me) : orderService.findMineBySide(me, side);
    }

    @GetMapping("/{id}/allocations")
    public List<AllocationResponse> getAllocationsForOrder(@PathVariable UUID id) {
        return allocationService.findByOrderIdForOwner(id, CurrentUser.id());
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.findByIdForOwner(id, CurrentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id));
    }

    /**
     * Cancels a resting order the caller owns. {@code 404} if it is not the caller's — same
     * "never confirm an id exists" policy as {@link #getOrder}. {@code 409} if it is already
     * {@code FILLED}/{@code EXPIRED}/{@code CANCELLED}. On a recurring listing's series
     * parent, cancels the whole series (B5/D6).
     */
    @DeleteMapping("/{id}")
    public OrderResponse cancelOrder(@PathVariable UUID id) {
        return orderService.cancelOrder(id, CurrentUser.id());
    }

    /**
     * Fills an open buy request with the caller’s GPUs.
     *
     * <p>Places a SELL mirroring the request’s price and window, so it matches immediately.
     * The terms are read from the stored request rather than the client, and filling your own
     * request is rejected — you cannot rent from yourself.
     */
    @PostMapping("/demand/{buyOrderId}/fill")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceOrderResponse fillDemand(@PathVariable UUID buyOrderId,
                                         @Valid @RequestBody FillDemandRequest request) {
        return orderService.fillDemand(buyOrderId, request.gpus(), CurrentUser.id());
    }
}
