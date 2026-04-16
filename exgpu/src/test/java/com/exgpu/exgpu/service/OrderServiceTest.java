package com.exgpu.exgpu.service;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.MatchStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.domain.enums.RecurrencePattern;
import com.exgpu.exgpu.dto.CreateOrderRequest;
import com.exgpu.exgpu.dto.OrderResponse;
import com.exgpu.exgpu.dto.PlaceOrderResponse;
import com.exgpu.exgpu.dto.RecurrenceSpec;
import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.engine.TimeSliceLockManager;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService} against a REAL {@link MatchingEngine} (so book state is genuinely
 * observable) with everything else mocked — the same shape as {@code BillingServiceTest} /
 * {@code AccessLeaseServiceTest}. Covers the Test plan's R1–R4 (compensating rollback and
 * payer routing), E3 (cancellation), and A3/A4 (recurring placement), plus the D3 window cap.
 */
class OrderServiceTest {

    private OrderRepository orderRepository;
    private AllocationRepository allocationRepository;
    private RealtimeEventPublisher events;
    private AccessLeaseService accessLeaseService;
    private BillingService billingService;
    private MatchingEngine matchingEngine;
    private SimpleMeterRegistry registry;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        allocationRepository = mock(AllocationRepository.class);
        events = mock(RealtimeEventPublisher.class);
        accessLeaseService = mock(AccessLeaseService.class);
        billingService = mock(BillingService.class);
        registry = new SimpleMeterRegistry();
        matchingEngine = new MatchingEngine(new TimeSliceLockManager(), new ExgpuMetrics(registry));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
        when(orderRepository.applyFill(any(), anyInt(), anyInt(), any())).thenReturn(1);
        when(orderRepository.findById(any())).thenReturn(Optional.empty());
        when(allocationRepository.saveAll(any())).thenAnswer(inv -> {
            List<com.exgpu.exgpu.domain.Allocation> list = inv.getArgument(0);
            for (com.exgpu.exgpu.domain.Allocation a : list) {
                if (a.getId() == null) a.setId(UUID.randomUUID());
            }
            return list;
        });
        when(billingService.canAffordBooking(any())).thenReturn(true);
        when(billingService.chargeForBooking(any())).thenReturn(BigDecimal.TEN);

        orderService = new OrderService(matchingEngine, orderRepository, allocationRepository,
                new ExgpuMetrics(registry), events, accessLeaseService, billingService, 24, 90);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Instant future(long hoursFromNow) {
        return Instant.now().plus(Duration.ofHours(hoursFromNow));
    }

    private Order restingOrder(OrderSide side, int qty, Instant start, Instant end) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(side)
                .pricePerGpuHour(BigDecimal.ONE)
                .quantity(qty)
                .window(new TimeWindow(start, end))
                .priorityTimestamp(Instant.now())
                .build();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /**
     * Must be called AFTER the code under test has run — {@code getSynchronizations()} is a
     * snapshot taken at call time, not a live view, so reading it before {@code placeOrder}
     * registers anything would silently simulate a rollback against an empty list.
     */
    private void simulateRollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    // ── R1 — B1 compensating rollback ─────────────────────────────────────────

    @Test
    void rollback_restoresTheMakersFilledQuantityAndBookPresence_andRemovesTheIncomingOrder() {
        Instant start = future(1);
        Instant end = future(2);
        Order sell = restingOrder(OrderSide.SELL, 10, start, end);
        matchingEngine.submitOrder(sell);

        beginTransaction();
        CreateOrderRequest buyRequest = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 10, start, end, null);
        PlaceOrderResponse response = orderService.placeOrder(buyRequest, UUID.randomUUID());

        assertThat(response.matchStatus()).isEqualTo(MatchStatus.FULL_FILL);
        assertThat(matchingEngine.sellBookSize()).isZero();
        assertThat(matchingEngine.buyBookSize()).isZero();

        simulateRollback();

        assertThat(sell.getFilledQuantity()).isZero();
        assertThat(sell.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(matchingEngine.sellBookSize()).as("maker restored to the book").isEqualTo(1);
        assertThat(matchingEngine.buyBookSize()).as("incoming order removed").isZero();
    }

    // ── R2 — B1b payer routing ────────────────────────────────────────────────

    @Test
    void payerIsIncomingBuyer_unaffordable_throws402_nothingPersisted() {
        Instant start = future(1);
        Instant end = future(2);
        Order sell = restingOrder(OrderSide.SELL, 10, start, end);
        matchingEngine.submitOrder(sell);

        when(billingService.canAffordBooking(any())).thenReturn(false);

        beginTransaction();
        CreateOrderRequest buyRequest = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 10, start, end, null);

        assertThatThrownBy(() -> orderService.placeOrder(buyRequest, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("402");

        verify(allocationRepository, times(0)).saveAll(any());
        verify(billingService, times(0)).chargeForBooking(any());
    }

    @Test
    void payerIsRestingCounterparty_unaffordable_dropsJustThatAllocation_placementStillSucceeds() {
        Instant start = future(1);
        Instant end = future(2);
        Order restingBuy = restingOrder(OrderSide.BUY, 10, start, end);
        matchingEngine.submitOrder(restingBuy);

        when(billingService.canAffordBooking(any())).thenReturn(false);

        CreateOrderRequest sellRequest = new CreateOrderRequest(
                OrderSide.SELL, BigDecimal.ONE, 10, start, end, null);
        PlaceOrderResponse response = orderService.placeOrder(sellRequest, UUID.randomUUID());

        assertThat(response.matchStatus()).isEqualTo(MatchStatus.NO_MATCH);
        assertThat(response.allocations()).isEmpty();
        assertThat(restingBuy.getFilledQuantity()).isZero();
        assertThat(restingBuy.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(matchingEngine.buyBookSize()).as("resting order stays in the book").isEqualTo(1);
        verify(allocationRepository, times(0)).saveAll(any());
        assertThat(registry.get("exgpu_booking_charge_failures_total").counter().count()).isEqualTo(1.0);
    }

    // ── R3 — D10 conditional update ───────────────────────────────────────────

    @Test
    void applyFillReturningZero_bookDbDivergence_dropsTheAllocation_compensatesTheEngine_noExceptionEscapes() {
        Instant start = future(1);
        Instant end = future(2);
        Order sell = restingOrder(OrderSide.SELL, 10, start, end);
        matchingEngine.submitOrder(sell);

        when(orderRepository.applyFill(any(), anyInt(), anyInt(), any())).thenReturn(0);

        CreateOrderRequest buyRequest = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 10, start, end, null);
        PlaceOrderResponse response = orderService.placeOrder(buyRequest, UUID.randomUUID());

        assertThat(response.allocations()).isEmpty();
        assertThat(response.matchStatus()).isEqualTo(MatchStatus.NO_MATCH);
        assertThat(sell.getFilledQuantity()).isZero();
        assertThat(matchingEngine.sellBookSize()).isEqualTo(1);
    }

    // ── R4 — metrics and events are post-commit ───────────────────────────────

    @Test
    void onRolledBackPlacement_matchesCounterNeverMoves_andRealtimeNeverFires() {
        Instant start = future(1);
        Instant end = future(2);
        Order sell = restingOrder(OrderSide.SELL, 10, start, end);
        matchingEngine.submitOrder(sell);

        beginTransaction();
        CreateOrderRequest buyRequest = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 10, start, end, null);
        orderService.placeOrder(buyRequest, UUID.randomUUID());

        simulateRollback();

        assertThat(registry.get("exgpu_matches_total").counter().count()).isZero();
        verify(events, times(0)).publishToUsers(eq(RealtimeEventType.ALLOCATION_CREATED),
                any(), any(), any(), any());
        verify(events, times(0)).publishToUser(any(), eq(RealtimeEventType.ORDER_FILLED),
                any(), any(), any());
    }

    // ── D3 — 24-hour window cap ────────────────────────────────────────────────

    @Test
    void windowLongerThan24Hours_rejectedWith400() {
        Instant start = future(1);
        Instant end = start.plus(Duration.ofHours(25));
        CreateOrderRequest request = new CreateOrderRequest(
                OrderSide.SELL, BigDecimal.ONE, 5, start, end, null);

        assertThatThrownBy(() -> orderService.placeOrder(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("24 hours");
    }

    @Test
    void windowOfExactly24Hours_isAccepted() {
        Instant start = future(1);
        Instant end = start.plus(Duration.ofHours(24));
        CreateOrderRequest request = new CreateOrderRequest(
                OrderSide.SELL, BigDecimal.ONE, 5, start, end, null);

        PlaceOrderResponse response = orderService.placeOrder(request, UUID.randomUUID());
        assertThat(response.order().windowStart()).isEqualTo(start);
    }

    /**
     * Regression guard: fillDemand mirrors an EXISTING order's window (possibly one that
     * predates the 24h cap, or was rehydrated from a pre-existing long-window row) rather than
     * a freshly chosen one, so it must not be rejected by the cap that only governs new
     * placement choices.
     */
    @Test
    void fillDemand_onAPreExistingDemandWiderThan24Hours_isStillFillable() {
        Order longDemand = restingOrder(OrderSide.BUY, 5, future(1), future(1).plus(Duration.ofHours(48)));
        matchingEngine.submitOrder(longDemand);
        when(orderRepository.findById(longDemand.getId())).thenReturn(Optional.of(longDemand));

        PlaceOrderResponse response = orderService.fillDemand(
                longDemand.getId(), 5, UUID.randomUUID());

        assertThat(response.matchStatus()).isEqualTo(MatchStatus.FULL_FILL);
    }

    // ── E3 — cancellation ──────────────────────────────────────────────────────

    @Test
    void cancelOrder_openOrder_marksCancelled_andRemovesFromBook() {
        Order order = restingOrder(OrderSide.SELL, 5, future(1), future(2));
        matchingEngine.submitOrder(order);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = orderService.cancelOrder(order.getId(), order.getOwnerId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
        assertThat(matchingEngine.sellBookSize()).isZero();
    }

    @Test
    void cancelOrder_alreadyFilled_throws409() {
        Order order = restingOrder(OrderSide.SELL, 5, future(1), future(2));
        order.setStatus(OrderStatus.FILLED);
        order.setFilledQuantity(5);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), order.getOwnerId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void cancelOrder_notOwnedByCaller_throws404() {
        Order order = restingOrder(OrderSide.SELL, 5, future(1), future(2));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void cancelOrder_unknownId_throws404() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(id, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void cancelOrder_templateParent_cancelsOpenChildren_leavesFilledChildAlone_cancelsParent() {
        UUID owner = UUID.randomUUID();
        Order parent = Order.builder()
                .id(UUID.randomUUID()).ownerId(owner).side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE).quantity(5)
                .window(new TimeWindow(future(1), future(200)))
                .status(OrderStatus.TEMPLATE).recurring(true)
                .priorityTimestamp(Instant.now()).build();

        Order openChild = restingOrder(OrderSide.SELL, 5, future(1), future(2));
        openChild.setOwnerId(owner);
        openChild.setParentOrderId(parent.getId());
        matchingEngine.submitOrder(openChild);

        Order filledChild = restingOrder(OrderSide.SELL, 5, future(3), future(4));
        filledChild.setOwnerId(owner);
        filledChild.setParentOrderId(parent.getId());
        filledChild.setStatus(OrderStatus.FILLED);
        filledChild.setFilledQuantity(5);

        when(orderRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(orderRepository.findByParentOrderId(parent.getId())).thenReturn(List.of(openChild, filledChild));

        OrderResponse response = orderService.cancelOrder(parent.getId(), owner);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(openChild.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(filledChild.getStatus()).as("allocated child keeps its allocation")
                .isEqualTo(OrderStatus.FILLED);
        assertThat(matchingEngine.sellBookSize()).isZero();
    }

    // ── A3/A4 — recurring listings ────────────────────────────────────────────

    @Test
    void placeOrder_recurringSell_createsTemplateParentPlusChildren_onlyChildrenEnterTheBook() {
        RecurrenceSpec spec = new RecurrenceSpec(RecurrencePattern.DAILY, 3, "UTC");
        Instant start = future(1);
        Instant end = start.plus(Duration.ofHours(1));
        CreateOrderRequest request = new CreateOrderRequest(
                OrderSide.SELL, BigDecimal.ONE, 5, start, end, spec);

        PlaceOrderResponse response = orderService.placeOrder(request, UUID.randomUUID());

        assertThat(response.order().recurring()).isTrue();
        assertThat(response.order().status()).isEqualTo(OrderStatus.TEMPLATE);
        assertThat(response.order().occurrenceCount()).isEqualTo(3);
        assertThat(matchingEngine.sellBookSize()).isEqualTo(3);
        verify(orderRepository, times(4)).save(any()); // 1 parent + 3 children
    }

    @Test
    void placeOrder_recurringOnBuy_rejectedWith400() {
        RecurrenceSpec spec = new RecurrenceSpec(RecurrencePattern.DAILY, 3, "UTC");
        Instant start = future(1);
        CreateOrderRequest request = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 5, start, start.plus(Duration.ofHours(1)), spec);

        assertThatThrownBy(() -> orderService.placeOrder(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SELL-only");
    }

    @Test
    void placeOrder_recurringPartialFill_leavesOtherOccurrencesFullyOpen() {
        RecurrenceSpec spec = new RecurrenceSpec(RecurrencePattern.DAILY, 3, "UTC");
        Instant start = future(1);
        Instant end = start.plus(Duration.ofHours(1));
        CreateOrderRequest sellSeries = new CreateOrderRequest(
                OrderSide.SELL, BigDecimal.ONE, 5, start, end, spec);
        orderService.placeOrder(sellSeries, UUID.randomUUID());

        assertThat(matchingEngine.sellBookSize()).isEqualTo(3);

        // Fill only the FIRST occurrence's window with a matching buy.
        CreateOrderRequest buyRequest = new CreateOrderRequest(
                OrderSide.BUY, BigDecimal.ONE, 5, start, end, null);
        PlaceOrderResponse fillResult = orderService.placeOrder(buyRequest, UUID.randomUUID());

        assertThat(fillResult.matchStatus()).isEqualTo(MatchStatus.FULL_FILL);
        // The other two occurrences are untouched — one order left in the book per remaining
        // day, each still fully OPEN (this order consumed exactly one occurrence).
        assertThat(matchingEngine.sellBookSize()).isEqualTo(2);
    }
}
