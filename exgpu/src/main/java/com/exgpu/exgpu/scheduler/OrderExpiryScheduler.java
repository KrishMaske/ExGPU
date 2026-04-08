package com.exgpu.exgpu.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.OrderRepository;

/**
 * Sweeps orders whose window has passed while they still had unfilled capacity to
 * {@code EXPIRED} (B4).
 *
 * <h2>Why expiry is a sweep, not a matching-path check (D11)</h2>
 * {@link com.exgpu.exgpu.domain.Order#isMatchable()} is deliberately clock-free, and so is
 * {@link MatchingEngine}. Two independent, idempotent halves do the actual work:
 * <ul>
 *   <li>{@link OrderRepository#expirePastWindows} — a conditional bulk UPDATE, the same idiom
 *       {@code AccessLeaseRepository.activateDueLeases} uses: the WHERE clause includes the
 *       state being moved out of, so a repeated or late tick self-heals rather than
 *       double-applying, and two schedulers ticking concurrently cannot double-count.</li>
 *   <li>{@link MatchingEngine#expireBefore} — removes the same orders from the in-memory book.
 *       Independent of the DB half; each is safe to run without the other having run yet.</li>
 * </ul>
 * Neither half depends on the other's output, so ordering between them does not matter for
 * correctness — only for how promptly the book and the table agree, which a 60s tick bounds
 * the same way {@code AccessLeaseScheduler}'s 15s tick bounds lease staleness. Both user-facing
 * entry points are already clock-guarded independently of this sweep:
 * {@code OrderService.placeOrder} rejects an {@code endTime} already in the past, and
 * {@code OrderService.fillDemand} re-checks the window before filling.
 */
@Component
public class OrderExpiryScheduler {

    static final long TICK_MS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;
    private final RealtimeEventPublisher events;
    private final ExgpuMetrics metrics;

    public OrderExpiryScheduler(OrderRepository orderRepository,
                                MatchingEngine matchingEngine,
                                RealtimeEventPublisher events,
                                ExgpuMetrics metrics) {
        this.orderRepository = orderRepository;
        this.matchingEngine = matchingEngine;
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = TICK_MS, initialDelay = 10_000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();

        List<UUID> removedFromBook = matchingEngine.expireBefore(now);
        int expiredInDb = orderRepository.expirePastWindows(now);

        if (expiredInDb > 0) {
            metrics.incrementOrdersExpired(expiredInDb);
        }
        for (UUID id : removedFromBook) {
            orderRepository.findById(id).ifPresent(order ->
                    events.publishToUser(order.getOwnerId(), RealtimeEventType.ORDER_EXPIRED,
                            order.getSide() + " order expired unfilled",
                            order.getId().toString(), null));
        }
        if (expiredInDb > 0 || !removedFromBook.isEmpty()) {
            events.publishMarket(RealtimeEventType.MARKET_UPDATED, "Marketplace supply changed", null, null);
            log.info("Order expiry tick: bookRemoved={} dbExpired={}", removedFromBook.size(), expiredInDb);
        }
    }
}
