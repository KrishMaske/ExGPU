package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.MatchResult;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.MatchStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    private MatchingEngine engine;

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    // [T0, T0+2h]
    private static final TimeWindow WIN_A = new TimeWindow(T0, T0.plus(2, ChronoUnit.HOURS));
    // [T0+1h, T0+3h] — overlaps WIN_A by [T0+1h, T0+2h]
    private static final TimeWindow WIN_B = new TimeWindow(T0.plus(1, ChronoUnit.HOURS), T0.plus(3, ChronoUnit.HOURS));
    // [T0+5h, T0+7h] — no overlap with WIN_A or WIN_B
    private static final TimeWindow WIN_C = new TimeWindow(T0.plus(5, ChronoUnit.HOURS), T0.plus(7, ChronoUnit.HOURS));

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(new TimeSliceLockManager(), new com.exgpu.exgpu.metrics.ExgpuMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private Order buy(double price, int qty, TimeWindow window) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(OrderSide.BUY)
                .pricePerGpuHour(BigDecimal.valueOf(price))
                .quantity(qty)
                .window(window)
                .priorityTimestamp(Instant.now())
                .build();
    }

    private Order sell(double price, int qty, TimeWindow window) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.valueOf(price))
                .quantity(qty)
                .window(window)
                .priorityTimestamp(Instant.now())
                .build();
    }

    @Test
    void fullFill_buyAndSellSameQuantityAndPrice() {
        Order sell = sell(1.00, 10, WIN_A);
        Order buy  = buy(1.00, 10, WIN_A);

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.FULL_FILL, result.getStatus());
        assertEquals(1, result.getAllocations().size());
        assertEquals(10, result.totalMatchedQuantity());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(0, engine.sellBookSize());
        assertEquals(0, engine.buyBookSize());
    }

    @Test
    void fullFill_buyPriceHigherThanSell() {
        Order sell = sell(1.00, 5, WIN_A);
        Order buy  = buy(2.00, 5, WIN_A); // buyer willing to pay more

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.FULL_FILL, result.getStatus());
        assertEquals(5, result.totalMatchedQuantity());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
    }

    @Test
    void partialFill_buyLargerThanSell() {
        Order sell = sell(1.00, 4, WIN_A);
        Order buy  = buy(1.50, 10, WIN_A);

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.PARTIAL_FILL, result.getStatus());
        assertEquals(1, result.getAllocations().size());
        assertEquals(4, result.totalMatchedQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(6, buy.remainingQuantity());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(1, engine.buyBookSize()); // unmatched portion stays in book
        assertEquals(0, engine.sellBookSize());
    }

    @Test
    void partialFill_sellLargerThanBuy() {
        Order sell = sell(1.00, 10, WIN_A);
        Order buy  = buy(1.00, 3, WIN_A);

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.FULL_FILL, result.getStatus());
        assertEquals(3, result.totalMatchedQuantity());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.PARTIALLY_FILLED, sell.getStatus());
        assertEquals(7, sell.remainingQuantity());
        assertEquals(0, engine.buyBookSize());
        assertEquals(1, engine.sellBookSize()); // sell stays with remaining qty
    }

    @Test
    void noMatch_buyPriceBelowSellPrice() {
        Order sell = sell(2.00, 10, WIN_A);
        Order buy  = buy(1.00, 10, WIN_A); // buy price < sell price

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.NO_MATCH, result.getStatus());
        assertTrue(result.getAllocations().isEmpty());
        assertEquals(OrderStatus.OPEN, buy.getStatus());
        assertEquals(1, engine.buyBookSize());
        assertEquals(1, engine.sellBookSize());
    }

    @Test
    void noMatch_timeWindowsDoNotOverlap() {
        Order sell = sell(1.00, 10, WIN_A); // [T0, T0+2h]
        Order buy  = buy(1.00, 10, WIN_C); // [T0+5h, T0+7h]

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.NO_MATCH, result.getStatus());
        assertTrue(result.getAllocations().isEmpty());
        assertEquals(1, engine.buyBookSize());
        assertEquals(1, engine.sellBookSize());
    }

    @Test
    void allocationWindow_isIntersectionOfBuyAndSell() {
        Order sell = sell(1.00, 5, WIN_A); // [T0,   T0+2h]
        Order buy  = buy(1.00, 5, WIN_B);  // [T0+1h, T0+3h]

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.FULL_FILL, result.getStatus());

        Allocation allocation = result.getAllocations().get(0);
        assertEquals(T0.plus(1, ChronoUnit.HOURS), allocation.getWindow().getStart());
        assertEquals(T0.plus(2, ChronoUnit.HOURS), allocation.getWindow().getEnd());
    }

    @Test
    void multipleSellersFillOneBuyer_prioritizedByCheapestFirst() {
        Order sellCheap = sell(0.50, 3, WIN_A);
        Order sellPricey = sell(1.00, 3, WIN_A);
        Order buy = buy(1.50, 6, WIN_A);

        engine.submitOrder(sellCheap);
        engine.submitOrder(sellPricey);
        MatchResult result = engine.submitOrder(buy);

        assertEquals(MatchStatus.FULL_FILL, result.getStatus());
        assertEquals(2, result.getAllocations().size());
        assertEquals(6, result.totalMatchedQuantity());

        // Cheap sell should be consumed first
        Allocation first = result.getAllocations().get(0);
        assertEquals(sellCheap.getId(), first.getSellOrderId());
    }

    @Test
    void allocation_carriesBuyerIdAndMakerExecutionPrice() {
        // Resting (maker) sell at $0.80; incoming (taker) buy willing to pay $1.50.
        Order sell = sell(0.80, 5, WIN_A);
        Order buy  = buy(1.50, 5, WIN_A);

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        Allocation allocation = result.getAllocations().get(0);
        // Buyer is the owner of the buy order, captured on the allocation for billing.
        assertEquals(buy.getOwnerId(), allocation.getBuyerId());
        // Trade clears at the resting/maker price (the sell), not the taker's bid.
        assertEquals(0, BigDecimal.valueOf(0.80).compareTo(allocation.getExecutionPrice()));
    }

    @Test
    void updatedOrders_containsBothSides() {
        Order sell = sell(1.00, 5, WIN_A);
        Order buy  = buy(1.00, 5, WIN_A);

        engine.submitOrder(sell);
        MatchResult result = engine.submitOrder(buy);

        assertTrue(result.getUpdatedOrders().contains(sell));
        assertTrue(result.getUpdatedOrders().contains(buy));
    }

    // ── self-trade prevention ─────────────────────────────────────────────────

    /** Helper: an order owned by a specific user, so both sides can share an owner. */
    private Order orderFor(UUID owner, OrderSide side, double price, int qty, TimeWindow window) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(owner)
                .side(side)
                .pricePerGpuHour(BigDecimal.valueOf(price))
                .quantity(qty)
                .window(window)
                .priorityTimestamp(Instant.now())
                .build();
    }

    /**
     * You cannot rent your own GPUs. A self-match would move money from an account to itself
     * and manufacture a billing record for compute nobody actually brokered.
     */
    @Test
    void ownOrders_doNotMatchEachOther_evenWhenPriceAndWindowAgree() {
        UUID me = UUID.randomUUID();
        TimeWindow window = WIN_A;

        engine.submitOrder(orderFor(me, OrderSide.SELL, 2.0, 10, window));
        MatchResult result = engine.submitOrder(orderFor(me, OrderSide.BUY, 5.0, 10, window));

        assertThat(result.getStatus()).isEqualTo(MatchStatus.NO_MATCH);
        assertThat(result.getAllocations()).isEmpty();
    }

    /** The self order stays on the book — it is skipped as a counterparty, not cancelled. */
    @Test
    void ownOrderIsSkipped_butStillMatchesSomeoneElse() {
        UUID me = UUID.randomUUID();
        TimeWindow window = WIN_A;

        engine.submitOrder(orderFor(me, OrderSide.SELL, 2.0, 10, window));

        // My own buy finds nothing...
        assertThat(engine.submitOrder(orderFor(me, OrderSide.BUY, 5.0, 10, window)).getStatus())
                .isEqualTo(MatchStatus.NO_MATCH);

        // ...but a different buyer matches the same resting sell.
        MatchResult other = engine.submitOrder(
                orderFor(UUID.randomUUID(), OrderSide.BUY, 5.0, 10, window));
        assertThat(other.getStatus()).isEqualTo(MatchStatus.FULL_FILL);
        assertThat(other.getAllocations()).hasSize(1);
    }

    /** With both a self order and a stranger resting, the stranger is chosen. */
    @Test
    void prefersStrangerOverOwnOrder_evenWhenOwnOrderIsCheaper() {
        UUID me = UUID.randomUUID();
        TimeWindow window = WIN_A;

        // My own sell is cheaper, so price-time priority would pick it first if allowed.
        engine.submitOrder(orderFor(me, OrderSide.SELL, 1.0, 5, window));
        engine.submitOrder(orderFor(UUID.randomUUID(), OrderSide.SELL, 3.0, 5, window));

        MatchResult result = engine.submitOrder(orderFor(me, OrderSide.BUY, 5.0, 5, window));

        assertThat(result.getStatus()).isEqualTo(MatchStatus.FULL_FILL);
        assertThat(result.getAllocations()).hasSize(1);
        // Filled at the stranger’s $3, not my own $1.
        assertThat(result.getAllocations().get(0).getExecutionPrice())
                .isEqualByComparingTo("3.0");
    }
}
