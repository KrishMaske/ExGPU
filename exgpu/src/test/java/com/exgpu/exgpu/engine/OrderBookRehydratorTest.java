package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 — rebuilding the book at startup. {@code MatchingEngine} is mocked here so the test is
 * purely about what the rehydrator selects and passes on, not the engine's own load/restore
 * behaviour (covered by {@code MatchingEngineLifecycleTest}).
 */
class OrderBookRehydratorTest {

    private OrderRepository orderRepository;
    private MatchingEngine matchingEngine;
    private OrderBookRehydrator rehydrator;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        matchingEngine = mock(MatchingEngine.class);
        rehydrator = new OrderBookRehydrator(orderRepository, matchingEngine);
    }

    private Order order(OrderStatus status, Instant start, Instant end) {
        return Order.builder()
                .id(UUID.randomUUID()).ownerId(UUID.randomUUID()).side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE).quantity(5).status(status)
                .window(new TimeWindow(start, end))
                .priorityTimestamp(Instant.now())
                .build();
    }

    @Test
    void rehydrate_loadsEveryLiveOrder_viaEngineLoad_notSubmitOrder() {
        Instant future = Instant.now().plusSeconds(3600);
        Order open = order(OrderStatus.OPEN, future, future.plusSeconds(3600));
        Order partial = order(OrderStatus.PARTIALLY_FILLED, future, future.plusSeconds(3600));
        when(orderRepository.findByStatusIn(eq(List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)), any()))
                .thenReturn(List.of(open, partial));

        rehydrator.rehydrate();

        verify(matchingEngine).load(anyList());
        verify(matchingEngine, never()).submitOrder(any());
    }

    @Test
    void rehydrate_skipsOrdersWhoseWindowHasAlreadyEnded() {
        Instant now = Instant.now();
        Order stale = order(OrderStatus.OPEN, now.minusSeconds(7200), now.minusSeconds(3600));
        Order live = order(OrderStatus.OPEN, now.plusSeconds(3600), now.plusSeconds(7200));
        when(orderRepository.findByStatusIn(eq(List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)), any()))
                .thenReturn(List.of(stale, live));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Order>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        rehydrator.rehydrate();

        verify(matchingEngine).load(captor.capture());
        assertThat(captor.getValue()).containsExactly(live);
    }
}
