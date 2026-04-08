package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Rebuilds {@link MatchingEngine}'s in-memory book from Postgres at startup (B3).
 *
 * <p>Verified live: after 13 minutes of uptime with {@code exgpu_orders_submitted_total = 0},
 * the database held 11 {@code OPEN} + 4 {@code PARTIALLY_FILLED} orders — every restart
 * silently emptied the book while {@code /market/supply} kept advertising that supply. This
 * closes that gap.
 *
 * <p>{@code @EventListener(ApplicationReadyEvent.class)}, not {@code @PostConstruct}: the
 * DataSource and JPA must be fully initialised before this can query. Guarded by
 * {@code exgpu.matching.rehydrate-on-startup} (default true; disabled in the test properties,
 * belt-and-braces alongside {@code ExgpuApplicationTests}'s {@code @MockBean MatchingEngine}).
 *
 * <p>Reuses {@link OrderRepository#findByStatusIn} with {@code priorityTimestamp} ascending,
 * which preserves price-time priority for orders loaded from disk. Rows whose window has
 * already ended are skipped here — expiry sweeping them to {@code EXPIRED} is
 * {@code OrderExpiryScheduler}'s job (B4), not this listener's; skipping just avoids briefly
 * reintroducing stale capacity into a fresh book.
 *
 * <p>Loads via {@link MatchingEngine#load}, which inserts WITHOUT matching — rehydration must
 * never create an allocation outside a billing transaction.
 *
 * <p>Shares {@link MatchingEngine}'s single-instance assumption: this loads the <em>whole</em>
 * live book into every JVM that starts, so running more than one instance against the same
 * database would give each its own independently-matching copy of the book.
 */
@Component
@ConditionalOnProperty(name = "exgpu.matching.rehydrate-on-startup", matchIfMissing = true)
public class OrderBookRehydrator {

    private static final Logger log = LoggerFactory.getLogger(OrderBookRehydrator.class);

    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;

    public OrderBookRehydrator(OrderRepository orderRepository, MatchingEngine matchingEngine) {
        this.orderRepository = orderRepository;
        this.matchingEngine = matchingEngine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rehydrate() {
        Instant now = Instant.now();
        List<Order> live = orderRepository.findByStatusIn(
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED),
                Sort.by(Sort.Direction.ASC, "priorityTimestamp"));

        List<Order> loadable = live.stream().filter(o -> !o.isExpired(now)).toList();
        matchingEngine.load(loadable);

        log.info("Order book rehydrated: {} order(s) loaded ({} skipped as already past their window)",
                loadable.size(), live.size() - loadable.size());
    }
}
