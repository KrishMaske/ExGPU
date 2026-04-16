package com.exgpu.exgpu.scheduler;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.OrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** E2 — the expiry sweep: both halves fire, the metric moves, and a repeat tick is a no-op. */
class OrderExpirySchedulerTest {

    private OrderRepository orderRepository;
    private MatchingEngine matchingEngine;
    private RealtimeEventPublisher events;
    private SimpleMeterRegistry registry;
    private OrderExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        matchingEngine = mock(MatchingEngine.class);
        events = mock(RealtimeEventPublisher.class);
        registry = new SimpleMeterRegistry();
        scheduler = new OrderExpiryScheduler(orderRepository, matchingEngine, events, new ExgpuMetrics(registry));
    }

    private Order order(UUID id, UUID ownerId) {
        Instant now = Instant.now();
        return Order.builder()
                .id(id).ownerId(ownerId).side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE).quantity(5)
                .window(new TimeWindow(now.minusSeconds(7200), now.minusSeconds(3600)))
                .priorityTimestamp(now)
                .build();
    }

    @Test
    void tick_sweepsBothHalves_incrementsMetric_publishesExpiryAndMarketEvents() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(matchingEngine.expireBefore(any())).thenReturn(List.of(id));
        when(orderRepository.expirePastWindows(any())).thenReturn(1);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, ownerId)));

        scheduler.tick();

        verify(matchingEngine).expireBefore(any());
        verify(orderRepository).expirePastWindows(any());
        verify(events).publishToUser(eq(ownerId), eq(RealtimeEventType.ORDER_EXPIRED), any(), any(), any());
        verify(events).publishMarket(eq(RealtimeEventType.MARKET_UPDATED), any(), any(), any());
        assertThat(registry.get("exgpu_orders_expired_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void tick_withNothingToExpire_isANoOp_publishesNothing() {
        when(matchingEngine.expireBefore(any())).thenReturn(List.of());
        when(orderRepository.expirePastWindows(any())).thenReturn(0);

        scheduler.tick();

        verify(events, never()).publishToUser(any(), eq(RealtimeEventType.ORDER_EXPIRED), any(), any(), any());
        verify(events, never()).publishMarket(any(), any(), any(), any());
        assertThat(registry.get("exgpu_orders_expired_total").counter().count()).isZero();
    }

    @Test
    void secondTick_atTheSameEffectiveState_isIdempotent() {
        // First tick finds one row; the repository-level idempotency is what
        // OrderRepository.expirePastWindows itself guarantees (conditional UPDATE), so here we
        // simulate the SECOND tick seeing zero from both halves — the scheduler must not act as
        // though anything happened.
        when(matchingEngine.expireBefore(any())).thenReturn(List.of(UUID.randomUUID()));
        when(orderRepository.expirePastWindows(any())).thenReturn(1);
        scheduler.tick();

        when(matchingEngine.expireBefore(any())).thenReturn(List.of());
        when(orderRepository.expirePastWindows(any())).thenReturn(0);
        scheduler.tick();

        assertThat(registry.get("exgpu_orders_expired_total").counter().count()).isEqualTo(1.0);
    }
}
