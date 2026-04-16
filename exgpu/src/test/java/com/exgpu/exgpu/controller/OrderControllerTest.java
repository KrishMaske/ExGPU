package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.domain.enums.MatchStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.dto.OrderResponse;
import com.exgpu.exgpu.dto.PlaceOrderResponse;
import com.exgpu.exgpu.service.AllocationService;
import com.exgpu.exgpu.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import com.exgpu.exgpu.config.SecurityConfig;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
// Without this, @WebMvcTest falls back to Boot's DEFAULT security (CSRF on, httpBasic),
// so these assertions would describe a filter chain the app does not actually run.
@Import(SecurityConfig.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  OrderService orderService;
    @MockBean  AllocationService allocationService;

    // SecurityConfig builds a real JwtDecoder pointed at a JWKS URL. Slice tests must never
    // reach the network, and the jwt() post-processor injects an already-decoded token, so
    // the decoder is stubbed out and never invoked.
    @MockBean  JwtDecoder jwtDecoder;

    private static final Instant START = Instant.parse("2026-06-01T08:00:00Z");
    private static final Instant END   = Instant.parse("2026-06-01T10:00:00Z");

    /** The signed-in caller. CurrentUser reads this from the token's 'sub' claim. */
    private static final UUID ME = UUID.randomUUID();

    private RequestPostProcessor asMe() {
        return jwt().jwt(builder -> builder.subject(ME.toString()).claim("email", "me@example.com"));
    }

    private OrderResponse sampleOrder(UUID id, OrderSide side) {
        return new OrderResponse(id, ME, side, OrderStatus.OPEN,
                BigDecimal.valueOf(1.50), 10, 0, 10, START, END, Instant.now(), Instant.now(),
                false, null, null, null);
    }

    private AllocationResponse sampleAllocation(UUID buyOrderId, UUID sellOrderId, int qty) {
        return new AllocationResponse(
                UUID.randomUUID(), buyOrderId, sellOrderId, qty, START, END,
                BigDecimal.valueOf(1.50), com.exgpu.exgpu.domain.enums.AllocationStatus.ACTIVE,
                Instant.now(), "SCHEDULED", 7200L, BigDecimal.valueOf(30));
    }

    private PlaceOrderResponse noMatchResponse(UUID id) {
        return new PlaceOrderResponse(sampleOrder(id, OrderSide.BUY), MatchStatus.NO_MATCH, 0, List.of());
    }

    private PlaceOrderResponse fullFillResponse(UUID id) {
        return new PlaceOrderResponse(
                new OrderResponse(id, ME, OrderSide.BUY, OrderStatus.FILLED,
                        BigDecimal.valueOf(1.50), 10, 10, 0, START, END, Instant.now(), Instant.now(),
                        false, null, null, null),
                MatchStatus.FULL_FILL, 10, List.of(sampleAllocation(id, UUID.randomUUID(), 10)));
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Test
    void anyEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/orders/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postOrders_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "BUY",
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 10,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /orders ──────────────────────────────────────────────────────────

    @Test
    void postOrders_validBuyRequest_returns201WithNoMatch() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.placeOrder(any(), eq(ME))).thenReturn(noMatchResponse(id));

        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "BUY",
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 10,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.id").value(id.toString()))
                .andExpect(jsonPath("$.matchStatus").value("NO_MATCH"))
                .andExpect(jsonPath("$.totalMatchedQuantity").value(0))
                .andExpect(jsonPath("$.allocations").isArray());
    }

    /**
     * The owner comes from the token, never the body. An ownerId in the payload is ignored
     * rather than honoured — this is the regression guard for the old trust model, where any
     * caller could place an order in someone else's name.
     */
    @Test
    void postOrders_ownerIdInBody_isIgnoredInFavourOfToken() throws Exception {
        UUID id = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        when(orderService.placeOrder(any(), eq(ME))).thenReturn(noMatchResponse(id));

        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "BUY",
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 10,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z",
                                  "ownerId": "%s"
                                }
                                """.formatted(someoneElse)))
                .andExpect(status().isCreated());

        // placeOrder was stubbed only for ME; had the body's ownerId been used, the stub
        // would not have matched and the response body would have been empty.
        org.mockito.Mockito.verify(orderService).placeOrder(any(), eq(ME));
    }

    @Test
    void postOrders_matchingOrderExists_returnsFullFillWithAllocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.placeOrder(any(), eq(ME))).thenReturn(fullFillResponse(id));

        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "BUY",
                                  "pricePerGpuHour": 2.00,
                                  "quantity": 10,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchStatus").value("FULL_FILL"))
                .andExpect(jsonPath("$.totalMatchedQuantity").value(10))
                .andExpect(jsonPath("$.allocations[0].quantity").value(10));
    }

    @Test
    void postOrders_missingSide_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 10,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.side").exists());
    }

    @Test
    void postOrders_quantityZero_returns400() throws Exception {
        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "SELL",
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 0,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    /**
     * A4 — the occurrence bound is Bean-Validated on {@code RecurrenceSpec} itself
     * ({@code @Max(60)}), so this rejects at the MVC layer before ever reaching
     * {@code orderService} — the mocked service is never consulted.
     */
    @Test
    void postOrders_recurrenceOccurrencesTooHigh_returns400() throws Exception {
        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "SELL",
                                  "pricePerGpuHour": 1.50,
                                  "quantity": 5,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z",
                                  "recurrence": { "pattern": "DAILY", "occurrences": 61, "zoneId": "UTC" }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void postOrders_negativePricePerUnit_returns400() throws Exception {
        mockMvc.perform(post("/orders").with(asMe())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "side": "BUY",
                                  "pricePerGpuHour": -1.00,
                                  "quantity": 5,
                                  "startTime": "2026-06-01T08:00:00Z",
                                  "endTime":   "2026-06-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pricePerGpuHour").exists());
    }

    // ── GET /orders/{id} ──────────────────────────────────────────────────────

    @Test
    void getOrder_ownedByCaller_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.findByIdForOwner(id, ME))
                .thenReturn(Optional.of(sampleOrder(id, OrderSide.SELL)));

        mockMvc.perform(get("/orders/{id}", id).with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.side").value("SELL"));
    }

    @Test
    void getOrder_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.findByIdForOwner(id, ME)).thenReturn(Optional.empty());

        mockMvc.perform(get("/orders/{id}", id).with(asMe()))
                .andExpect(status().isNotFound());
    }

    /** Someone else's order is indistinguishable from a nonexistent one. */
    @Test
    void getOrder_ownedBySomeoneElse_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.findByIdForOwner(id, ME)).thenReturn(Optional.empty());

        mockMvc.perform(get("/orders/{id}", id).with(asMe()))
                .andExpect(status().isNotFound());
    }

    // ── GET /orders/{id}/allocations ─────────────────────────────────────────

    @Test
    void getOrderAllocations_ownedByCaller_returnsAllocations() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(allocationService.findByOrderIdForOwner(orderId, ME))
                .thenReturn(List.of(sampleAllocation(orderId, UUID.randomUUID(), 5)));

        mockMvc.perform(get("/orders/{id}/allocations", orderId).with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].buyOrderId").value(orderId.toString()))
                .andExpect(jsonPath("$[0].quantity").value(5));
    }

    @Test
    void getOrderAllocations_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(allocationService.findByOrderIdForOwner(id, ME))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Order not found: " + id));

        mockMvc.perform(get("/orders/{id}/allocations", id).with(asMe()))
                .andExpect(status().isNotFound());
    }

    // ── GET /orders/me ────────────────────────────────────────────────────────

    @Test
    void getMyOrders_returnsOnlyCallersOrders() throws Exception {
        when(orderService.findMine(ME)).thenReturn(List.of(sampleOrder(UUID.randomUUID(), OrderSide.BUY)));

        mockMvc.perform(get("/orders/me").with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getMyOrders_withSideFilter_delegatesToSideQuery() throws Exception {
        when(orderService.findMineBySide(ME, OrderSide.SELL))
                .thenReturn(List.of(sampleOrder(UUID.randomUUID(), OrderSide.SELL)));

        mockMvc.perform(get("/orders/me").param("side", "SELL").with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].side").value("SELL"));
    }

    // ── DELETE /orders/{id} (E4) ─────────────────────────────────────────────

    @Test
    void deleteOrder_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteOrder_ownedByCaller_returns200WithCancelledOrder() throws Exception {
        UUID id = UUID.randomUUID();
        OrderResponse cancelled = new OrderResponse(id, ME, OrderSide.SELL, OrderStatus.CANCELLED,
                BigDecimal.valueOf(1.50), 10, 0, 10, START, END, Instant.now(), Instant.now(),
                false, null, null, null);
        when(orderService.cancelOrder(id, ME)).thenReturn(cancelled);

        mockMvc.perform(delete("/orders/{id}", id).with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void deleteOrder_notCallers_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.cancelOrder(id, ME)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Order not found: " + id));

        mockMvc.perform(delete("/orders/{id}", id).with(asMe()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOrder_alreadyFilled_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.cancelOrder(id, ME)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Order cannot be cancelled: currently FILLED"));

        mockMvc.perform(delete("/orders/{id}", id).with(asMe()))
                .andExpect(status().isConflict());
    }
}
