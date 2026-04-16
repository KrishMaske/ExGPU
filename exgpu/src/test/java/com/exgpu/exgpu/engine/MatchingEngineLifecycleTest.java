package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.Fill;
import com.exgpu.exgpu.domain.MatchResult;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the engine's non-matching surface added by this plan: {@code restore}/{@code load}
 * (B3), {@code remove} (B5), {@code expireBefore} (B4/D11/E1), and {@code rollback} (D8/D9/D10)
 * — none of which are exercised by the single-threaded matching cases in
 * {@code MatchingEngineTest}.
 */
class MatchingEngineLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(new TimeSliceLockManager(), new ExgpuMetrics(new SimpleMeterRegistry()));
    }

    private Order order(OrderSide side, int qty, TimeWindow window, OrderStatus status, int filled) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(side)
                .pricePerGpuHour(BigDecimal.ONE)
                .quantity(qty)
                .filledQuantity(filled)
                .status(status)
                .window(window)
                .priorityTimestamp(Instant.now())
                .build();
    }

    // ── restore / load (B3) ───────────────────────────────────────────────────

    @Test
    void restore_insertsMatchableOrder_withoutMatchingAgainstTheBook() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order sell = order(OrderSide.SELL, 10, window, OrderStatus.OPEN, 0);
        Order buy = order(OrderSide.BUY, 10, window, OrderStatus.OPEN, 0);

        engine.restore(sell);
        engine.restore(buy);

        // Would have matched immediately through submitOrder; restore must not do that.
        assertThat(engine.sellBookSize()).isEqualTo(1);
        assertThat(engine.buyBookSize()).isEqualTo(1);
        assertThat(sell.getFilledQuantity()).isZero();
        assertThat(buy.getFilledQuantity()).isZero();
    }

    @Test
    void restore_skipsAnOrderThatIsNotMatchable() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order filled = order(OrderSide.SELL, 10, window, OrderStatus.FILLED, 10);

        engine.restore(filled);

        assertThat(engine.sellBookSize()).isZero();
    }

    @Test
    void load_bulkRestoresEveryOrder_creatingNoAllocations() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        List<Order> orders = List.of(
                order(OrderSide.BUY, 5, window, OrderStatus.OPEN, 0),
                order(OrderSide.BUY, 3, window, OrderStatus.PARTIALLY_FILLED, 2),
                order(OrderSide.SELL, 4, window, OrderStatus.OPEN, 0));

        engine.load(orders);

        assertThat(engine.buyBookSize()).isEqualTo(2);
        assertThat(engine.sellBookSize()).isEqualTo(1);
        // No cross-matching happened even though a BUY and a SELL at compatible terms both
        // loaded into the same window.
        for (Order o : orders) {
            assertThat(o.getFilledQuantity()).isIn(0, 2); // unchanged from what was passed in
        }
    }

    @Test
    void load_isIdempotent_secondLoadOfSameOrdersDoesNotGrowTheBook() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        List<Order> orders = List.of(order(OrderSide.SELL, 5, window, OrderStatus.OPEN, 0));

        engine.load(orders);
        engine.load(orders);

        assertThat(engine.sellBookSize()).isEqualTo(1);
    }

    // ── remove (B5) ────────────────────────────────────────────────────────────

    @Test
    void remove_deletesAPresentOrder_andReturnsTrue() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order sell = order(OrderSide.SELL, 5, window, OrderStatus.OPEN, 0);
        engine.restore(sell);

        boolean removed = engine.remove(sell.getId());

        assertThat(removed).isTrue();
        assertThat(engine.sellBookSize()).isZero();
    }

    @Test
    void remove_returnsFalse_whenOrderIsNotInTheBook() {
        assertThat(engine.remove(UUID.randomUUID())).isFalse();
    }

    // ── expireBefore (B4/D11/E1) ──────────────────────────────────────────────

    @Test
    void expireBefore_removesOnlyOrdersWhoseWindowHasEnded_leavesLiveOnesUntouched() {
        TimeWindow past = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        TimeWindow future = new TimeWindow(T0.plus(10, ChronoUnit.HOURS), T0.plus(11, ChronoUnit.HOURS));
        Order stale = order(OrderSide.SELL, 5, past, OrderStatus.OPEN, 0);
        Order live = order(OrderSide.SELL, 5, future, OrderStatus.OPEN, 0);
        engine.load(List.of(stale, live));

        Instant sweepTime = T0.plus(5, ChronoUnit.HOURS); // after 'past' ends, before 'future' starts
        List<UUID> removed = engine.expireBefore(sweepTime);

        assertThat(removed).containsExactly(stale.getId());
        assertThat(engine.sellBookSize()).isEqualTo(1);
    }

    @Test
    void expireBefore_isIdempotent_secondSweepAtSameInstantRemovesNothing() {
        TimeWindow past = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order stale = order(OrderSide.SELL, 5, past, OrderStatus.OPEN, 0);
        engine.restore(stale);

        Instant now = T0.plus(2, ChronoUnit.HOURS);
        List<UUID> first = engine.expireBefore(now);
        List<UUID> second = engine.expireBefore(now);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    void expireBefore_boundaryInstant_windowEndEqualToNow_isExpired() {
        // TimeWindow.overlaps is closed on both ends elsewhere in this codebase; expiry uses
        // the same "boundary counts" convention: window.end == now is expired, not live.
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order sell = order(OrderSide.SELL, 5, window, OrderStatus.OPEN, 0);
        engine.restore(sell);

        List<UUID> removed = engine.expireBefore(window.getEnd());

        assertThat(removed).containsExactly(sell.getId());
    }

    // ── rollback (D8/D9/D10) ───────────────────────────────────────────────────

    @Test
    void rollback_decrementsCounterpartyFilledQuantity_andRecomputesStatus() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order sell = order(OrderSide.SELL, 10, window, OrderStatus.PARTIALLY_FILLED, 4);

        Fill fill = Fill.builder()
                .orderId(sell.getId())
                .order(sell)
                .quantityBefore(0)
                .quantityFilled(4)
                .newStatus(OrderStatus.PARTIALLY_FILLED)
                .build();

        engine.rollback(MatchResult.builder().fills(List.of(fill)).build());

        assertThat(sell.getFilledQuantity()).isZero();
        assertThat(sell.getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rollback_reinsertsAFullyFilledCounterparty_thatWasRemovedFromTheBook() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        // Simulates a maker that was fully consumed by the match (removed from the book) and
        // is now being unwound because the surrounding transaction rolled back.
        Order sell = order(OrderSide.SELL, 10, window, OrderStatus.FILLED, 10);
        assertThat(engine.sellBookSize()).isZero(); // never inserted — it arrived already FILLED

        Fill fill = Fill.builder()
                .orderId(sell.getId())
                .order(sell)
                .quantityBefore(0)
                .quantityFilled(10)
                .newStatus(OrderStatus.FILLED)
                .build();

        engine.rollback(MatchResult.builder().fills(List.of(fill)).build());

        assertThat(sell.getFilledQuantity()).isZero();
        assertThat(sell.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(engine.sellBookSize()).isEqualTo(1);
    }

    @Test
    void rollback_removesTheIncomingOrderFromTheBookEntirely() {
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order buy = order(OrderSide.BUY, 10, window, OrderStatus.OPEN, 0);
        engine.restore(buy);
        assertThat(engine.buyBookSize()).isEqualTo(1);

        engine.rollback(MatchResult.builder().fills(List.of()).incoming(buy).build());

        assertThat(engine.buyBookSize()).isZero();
    }

    @Test
    void rollback_withNoIncomingSet_onlyTouchesTheListedFills_asUsedForASingleDroppedAllocation() {
        // This is the shape OrderService builds for D9/D10: compensate exactly one fill
        // without unwinding the rest of the match.
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));
        Order counterpartyA = order(OrderSide.BUY, 10, window, OrderStatus.PARTIALLY_FILLED, 3);
        Order counterpartyB = order(OrderSide.BUY, 10, window, OrderStatus.PARTIALLY_FILLED, 5);
        engine.restore(counterpartyA);
        engine.restore(counterpartyB);

        Fill onlyFillA = Fill.builder()
                .orderId(counterpartyA.getId()).order(counterpartyA)
                .quantityBefore(0).quantityFilled(3).newStatus(OrderStatus.PARTIALLY_FILLED)
                .build();

        engine.rollback(MatchResult.builder().fills(List.of(onlyFillA)).build());

        assertThat(counterpartyA.getFilledQuantity()).isZero();
        assertThat(counterpartyB.getFilledQuantity()).isEqualTo(5); // untouched
    }

    @Test
    void rollback_onNoMatchResult_isANoOp() {
        engine.rollback(MatchResult.builder().status(com.exgpu.exgpu.domain.enums.MatchStatus.NO_MATCH).build());
        // No exception, no book change — nothing to assert beyond "did not throw".
        assertThat(engine.buyBookSize()).isZero();
        assertThat(engine.sellBookSize()).isZero();
    }

    @Test
    void rollback_onNullResult_isANoOp() {
        engine.rollback(null);
    }
}
