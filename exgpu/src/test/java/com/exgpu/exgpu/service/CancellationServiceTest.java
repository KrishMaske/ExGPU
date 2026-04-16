package com.exgpu.exgpu.service;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.AllocationStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.dto.CancellationQuote;
import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import com.exgpu.exgpu.repository.UsageLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6 — cancellation must reach {@link MatchingEngine}'s in-memory book (B2), not just Postgres.
 */
class CancellationServiceTest {

    private AllocationRepository allocationRepository;
    private OrderRepository orderRepository;
    private UsageLedgerRepository usageLedgerRepository;
    private BillingService billingService;
    private AccessLeaseService accessLeaseService;
    private MatchingEngine matchingEngine;
    private CancellationService service;

    @BeforeEach
    void setUp() {
        allocationRepository = mock(AllocationRepository.class);
        orderRepository = mock(OrderRepository.class);
        usageLedgerRepository = mock(UsageLedgerRepository.class);
        billingService = mock(BillingService.class);
        accessLeaseService = mock(AccessLeaseService.class);
        matchingEngine = mock(MatchingEngine.class);

        service = new CancellationService(allocationRepository, orderRepository, usageLedgerRepository,
                billingService, accessLeaseService,
                mock(com.exgpu.exgpu.realtime.RealtimeEventPublisher.class),
                new ExgpuMetrics(new SimpleMeterRegistry()), matchingEngine);
    }

    @Test
    void cancel_ofAFullyFilledSellOrder_restoresCapacityAndCallsMatchingEngineRestore() {
        UUID buyerId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        // Plenty of notice (> 8h) so the tier doesn't matter for this assertion.
        Instant start = Instant.now().plus(Duration.ofHours(10));
        Instant end = start.plus(Duration.ofHours(1));

        Allocation allocation = Allocation.builder()
                .id(allocationId)
                .buyOrderId(UUID.randomUUID())
                .sellOrderId(sellOrderId)
                .buyerId(buyerId)
                .quantity(5)
                .executionPrice(BigDecimal.ONE)
                .window(new TimeWindow(start, end))
                .status(AllocationStatus.ACTIVE)
                .build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(allocation));

        Order sellOrder = Order.builder()
                .id(sellOrderId)
                .ownerId(UUID.randomUUID())
                .side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE)
                .quantity(5)
                .filledQuantity(5)
                .status(OrderStatus.FILLED)
                .window(new TimeWindow(start, end))
                .priorityTimestamp(Instant.now())
                .build();
        when(orderRepository.findById(sellOrderId)).thenReturn(Optional.of(sellOrder));
        when(billingService.refundBooking(any(), any())).thenReturn(BigDecimal.TEN);

        CancellationQuote result = service.cancel(allocationId, buyerId);

        assertThat(result.alreadyCancelled()).isTrue();
        assertThat(sellOrder.getFilledQuantity()).isZero();
        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.OPEN);
        verify(matchingEngine).restore(sellOrder);
    }

    @Test
    void cancel_ofAPartiallyFilledSellOrder_restoresOnlyTheCancelledPortion() {
        UUID buyerId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        Instant start = Instant.now().plus(Duration.ofHours(10));
        Instant end = start.plus(Duration.ofHours(1));

        Allocation allocation = Allocation.builder()
                .id(allocationId).buyOrderId(UUID.randomUUID()).sellOrderId(sellOrderId)
                .buyerId(buyerId).quantity(3).executionPrice(BigDecimal.ONE)
                .window(new TimeWindow(start, end)).status(AllocationStatus.ACTIVE).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(allocation));

        // The sell order sold 8 total; this cancellation only returns 3 of them.
        Order sellOrder = Order.builder()
                .id(sellOrderId).ownerId(UUID.randomUUID()).side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE).quantity(10).filledQuantity(8)
                .status(OrderStatus.PARTIALLY_FILLED)
                .window(new TimeWindow(start, end)).priorityTimestamp(Instant.now()).build();
        when(orderRepository.findById(sellOrderId)).thenReturn(Optional.of(sellOrder));
        when(billingService.refundBooking(any(), any())).thenReturn(BigDecimal.ONE);

        service.cancel(allocationId, buyerId);

        assertThat(sellOrder.getFilledQuantity()).isEqualTo(5);
        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        verify(matchingEngine).restore(sellOrder);
    }
}
