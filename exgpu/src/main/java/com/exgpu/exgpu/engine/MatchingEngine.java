package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.Fill;
import com.exgpu.exgpu.domain.MatchResult;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.MatchStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The in-memory order book and matching algorithm.
 *
 * <h2>Single-instance assumption</h2>
 * The book lives only in this JVM's heap. Running more than one instance of this application
 * against the same database would give each instance its own, independently-matching book,
 * which could double-match the same resting capacity. Nothing in this class detects or guards
 * against that — a multi-instance design needs either a {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} claim scheme or a partitioned book, both out of scope here. See
 * {@link OrderBookRehydrator}, which shares this assumption.
 *
 * <h2>Two locking modes</h2>
 * Controlled by {@code exgpu.matching.striped}, constructor-injected as {@code striped}:
 * <ul>
 *   <li><b>{@code false} (default)</b> — a single engine-wide {@link ReentrantLock} serializes
 *       every book-mutating call, functionally identical to the {@code synchronized} this
 *       replaced. Safe under virtual threads: unlike a monitor, a {@code ReentrantLock} parks
 *       the virtual thread and frees its carrier instead of pinning it (JEP 491 territory,
 *       relevant because this runs on Java 21, not 24).</li>
 *   <li><b>{@code true}</b> — the two-tier striped discipline in {@link TimeSliceLockManager}:
 *       tier-1 time-bucket stripes for the incoming order's own window, tier-2 a per-order-id
 *       leaf lock acquired immediately before mutating one candidate and released immediately
 *       after. See {@link TimeSliceLockManager}'s class Javadoc for why both tiers are
 *       necessary and the counterexample that rules out tier-1 alone (D1).</li>
 * </ul>
 * Both modes maintain the same bucket-partitioned index ({@code buyBuckets}/{@code
 * sellBuckets} alongside the flat {@code buyBook}/{@code sellBook}), so candidate gathering is
 * always "orders sharing a bucket with the incoming window" rather than a linear scan of the
 * whole counter-book — this is a strict superset of what a flat scan would find (before the
 * existing price/self-trade/overlap filters), so it changes no matching outcome.
 *
 * <h2>Expiry stays out of matching (D11)</h2>
 * {@link Order#isMatchable()} is deliberately clock-free, and nothing in this class calls
 * {@link Instant#now()} while matching. Expiry is exclusively {@link #expireBefore(Instant)}'s
 * job, driven by a scheduler with an explicit, testable {@code now}. Threading a clock into the
 * matching path would make every window in {@code MatchingEngineTest} — pinned to 2026-06-01,
 * already in the past — instantly unmatchable.
 */
@Service
public class MatchingEngine {

    private final Map<UUID, Order> buyBook = new ConcurrentHashMap<>();
    private final Map<UUID, Order> sellBook = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentHashMap<UUID, Order>> buyBuckets = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentHashMap<UUID, Order>> sellBuckets = new ConcurrentHashMap<>();

    /** Step 6's fallback path: one engine-wide lock, held for the whole of any book operation. */
    private final ReentrantLock globalLock = new ReentrantLock();

    private final TimeSliceLockManager locks;
    private final ExgpuMetrics metrics;
    private final boolean striped;

    /**
     * The constructor {@code MatchingEngineTest} and other plain-JUnit tests use directly.
     * Always non-striped (matches production's {@code exgpu.matching.striped} default of
     * {@code false}) — the striped path is exercised separately, via the 3-arg constructor, by
     * {@code MatchingEngineConcurrencyTest}.
     */
    public MatchingEngine(TimeSliceLockManager locks, ExgpuMetrics metrics) {
        this(locks, metrics, false);
    }

    @Autowired
    public MatchingEngine(TimeSliceLockManager locks,
                          ExgpuMetrics metrics,
                          @Value("${exgpu.matching.striped:false}") boolean striped) {
        this.locks = locks;
        this.metrics = metrics;
        this.striped = striped;
    }

    /**
     * Matches {@code incoming} against the resting book and, if anything remains, inserts it.
     * No DB I/O, no Kafka, nothing blocking beyond the book's own locks — see CLAUDE.md's
     * "avoid blocking operations inside the matching path" constraint.
     */
    public MatchResult submitOrder(Order incoming) {
        if (!incoming.isMatchable()) {
            return MatchResult.builder().status(MatchStatus.NO_MATCH).incoming(incoming).build();
        }

        Timer.Sample sample = Timer.start();
        try {
            return striped ? submitStriped(incoming) : submitSingleLocked(incoming);
        } finally {
            sample.stop(metrics.getMatchingLatencyTimer());
        }
    }

    private MatchResult submitSingleLocked(Order incoming) {
        globalLock.lock();
        try {
            return doMatch(incoming, false);
        } finally {
            globalLock.unlock();
        }
    }

    private MatchResult submitStriped(Order incoming) {
        metrics.recordLockStripes(locks.stripesFor(incoming.getWindow()).length);

        Timer.Sample waitSample = Timer.start();
        TimeSliceLockManager.Handle handle = locks.acquire(incoming.getWindow());
        waitSample.stop(metrics.getMatchingLockWaitTimer());
        try {
            return doMatch(incoming, true);
        } finally {
            handle.close();
        }
    }

    /**
     * Runs the match, then inserts {@code incoming} if anything is left of it. Assumes the
     * appropriate tier-1 lock for {@code incoming.getWindow()} (or the global lock) is already
     * held by the caller for its entire duration.
     */
    private MatchResult doMatch(Order incoming, boolean useTier2) {
        List<Allocation> allocations = new ArrayList<>();
        List<Order> updatedOrders = new ArrayList<>();
        List<Fill> fills = new ArrayList<>();

        boolean isBuy = incoming.getSide() == OrderSide.BUY;
        Map<Long, ConcurrentHashMap<UUID, Order>> counterBuckets = isBuy ? sellBuckets : buyBuckets;
        Map<UUID, Order> counterBook = isBuy ? sellBook : buyBook;

        runMatch(incoming, isBuy, counterBook, counterBuckets, useTier2, allocations, updatedOrders, fills);

        MatchStatus status;
        if (allocations.isEmpty()) {
            status = MatchStatus.NO_MATCH;
        } else if (!incoming.isMatchable()) {
            status = MatchStatus.FULL_FILL;
        } else {
            status = MatchStatus.PARTIAL_FILL;
        }

        if (!allocations.isEmpty()) {
            updatedOrders.add(incoming);
        }

        if (incoming.isMatchable()) {
            insert(incoming, isBuy);
        }

        return MatchResult.builder()
                .allocations(allocations)
                .updatedOrders(updatedOrders)
                .fills(fills)
                .incoming(incoming)
                .status(status)
                .build();
    }

    private void runMatch(Order incoming, boolean isBuy, Map<UUID, Order> counterBook,
                          Map<Long, ConcurrentHashMap<UUID, Order>> counterBuckets, boolean useTier2,
                          List<Allocation> allocations, List<Order> updatedOrders, List<Fill> fills) {

        // Best price first: cheapest sell for a buy, highest buy for a sell.
        Comparator<Order> priceSort = isBuy
                ? Comparator.comparing(Order::getPricePerGpuHour)
                : Comparator.comparing(Order::getPricePerGpuHour, Comparator.reverseOrder());

        List<Order> candidates = gatherCandidates(incoming.getWindow(), counterBuckets).stream()
                .filter(Order::isMatchable)
                // Self-trade prevention: you cannot rent your own GPUs. Beyond being
                // nonsensical for the user, a self-match would move real money between an
                // account and itself, minus nothing — inflating traded volume and creating
                // billing rows for compute that was never actually brokered.
                .filter(c -> !c.getOwnerId().equals(incoming.getOwnerId()))
                .filter(c -> priceCompatible(incoming, c, isBuy))
                .filter(c -> c.getWindow().overlaps(incoming.getWindow()))
                .sorted(priceSort.thenComparing(Order::getPriorityTimestamp))
                .toList();

        for (Order candidate : candidates) {
            if (!incoming.isMatchable()) break;

            // Tier 2: acquired only while already holding tier 1 (or the global lock), one at
            // a time, released before moving to the next candidate — see TimeSliceLockManager's
            // class Javadoc (D4) for why this ordering is what makes the design deadlock-free.
            Lock candidateLock = useTier2 ? locks.orderLock(candidate.getId()) : null;
            if (candidateLock != null) candidateLock.lock();
            try {
                // The re-check that makes D1's counterexample impossible: candidate may have
                // been mutated (or removed) by another thread between the scan above and here.
                if (!candidate.isMatchable()) continue;

                int matchedQty = Math.min(incoming.remainingQuantity(), candidate.remainingQuantity());
                if (matchedQty <= 0) continue;

                int candidateBefore = candidate.getFilledQuantity();
                TimeWindow matchedWindow = incoming.getWindow().intersection(candidate.getWindow());

                incoming.setFilledQuantity(incoming.getFilledQuantity() + matchedQty);
                candidate.setFilledQuantity(candidate.getFilledQuantity() + matchedQty);

                incoming.recomputeStatus();
                candidate.recomputeStatus();

                if (!candidate.isMatchable()) {
                    counterBook.remove(candidate.getId());
                    unregisterBuckets(candidate, counterBuckets);
                }

                Order buyOrder  = isBuy ? incoming : candidate;
                Order sellOrder = isBuy ? candidate : incoming;

                // The trade clears at the resting (maker) order's price — that's always the
                // candidate, since it was already in the book when 'incoming' arrived.
                // Capturing buyer and price here makes the allocation a self-contained
                // billing record, so billing never has to trust the usage event for them.
                Allocation allocation = Allocation.builder()
                        .buyOrderId(buyOrder.getId())
                        .sellOrderId(sellOrder.getId())
                        .buyerId(buyOrder.getOwnerId())
                        .executionPrice(candidate.getPricePerGpuHour())
                        .quantity(matchedQty)
                        .window(matchedWindow)
                        .build();
                allocations.add(allocation);

                updatedOrders.add(candidate);
                fills.add(Fill.builder()
                        .orderId(candidate.getId())
                        .order(candidate)
                        .quantityBefore(candidateBefore)
                        .quantityFilled(matchedQty)
                        .newStatus(candidate.getStatus())
                        .allocation(allocation)
                        .build());
            } finally {
                if (candidateLock != null) candidateLock.unlock();
            }
        }
    }

    /**
     * Union, deduped by id, of every counter-side bucket the incoming window's span touches —
     * a strict superset of "every order in the counter book" once the filters in
     * {@link #runMatch} run, so this changes no match outcome relative to a linear scan.
     *
     * <p>Weakly consistent by design: a candidate's own bucket span can extend outside
     * {@code window}, so another thread can remove it from a bucket this method has not yet
     * reached. That is fine — correctness comes from the {@code isMatchable()} re-check under
     * the candidate's tier-2 lock in {@link #runMatch}, not from this scan being a snapshot.
     */
    private List<Order> gatherCandidates(TimeWindow window,
                                         Map<Long, ConcurrentHashMap<UUID, Order>> counterBuckets) {
        Map<UUID, Order> deduped = new HashMap<>();
        for (long bucket : TimeSliceLockManager.bucketRange(window)) {
            ConcurrentHashMap<UUID, Order> inBucket = counterBuckets.get(bucket);
            if (inBucket != null) {
                deduped.putAll(inBucket);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    // buy.price >= sell.price
    private boolean priceCompatible(Order incoming, Order candidate, boolean isBuy) {
        if (isBuy) {
            return candidate.getPricePerGpuHour().compareTo(incoming.getPricePerGpuHour()) <= 0;
        } else {
            return candidate.getPricePerGpuHour().compareTo(incoming.getPricePerGpuHour()) >= 0;
        }
    }

    private void insert(Order order, boolean isBuy) {
        Map<UUID, Order> book = isBuy ? buyBook : sellBook;
        Map<Long, ConcurrentHashMap<UUID, Order>> buckets = isBuy ? buyBuckets : sellBuckets;
        book.put(order.getId(), order);
        registerBuckets(order, buckets);
    }

    private void registerBuckets(Order order, Map<Long, ConcurrentHashMap<UUID, Order>> buckets) {
        for (long bucket : TimeSliceLockManager.bucketRange(order.getWindow())) {
            buckets.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>()).put(order.getId(), order);
        }
    }

    /**
     * Removes {@code order} from every bucket it was registered in, deleting the inner map
     * when it empties — otherwise the bucket index leaks unboundedly, exactly like an
     * unreclaimed per-bucket lock would. {@code computeIfPresent} makes the
     * "remove, then delete if empty" sequence atomic per bucket key.
     */
    private void unregisterBuckets(Order order, Map<Long, ConcurrentHashMap<UUID, Order>> buckets) {
        for (long bucket : TimeSliceLockManager.bucketRange(order.getWindow())) {
            buckets.computeIfPresent(bucket, (k, inBucket) -> {
                inBucket.remove(order.getId());
                return inBucket.isEmpty() ? null : inBucket;
            });
        }
    }

    /**
     * Inserts {@code order} into the book WITHOUT matching. Used for startup rehydration
     * (B3) and for returning capacity after cancellation/rollback (B1/B2) — none of those
     * callers want a fresh match to happen outside a billing transaction. A no-op if the order
     * is not matchable. Idempotent: inserting an id already present just overwrites it with
     * itself.
     */
    public void restore(Order order) {
        if (!order.isMatchable()) return;
        boolean isBuy = order.getSide() == OrderSide.BUY;
        withLock(order.getWindow(), () -> {
            insert(order, isBuy);
            return null;
        });
    }

    /** Bulk {@link #restore}, in the given order — used by {@link OrderBookRehydrator}. */
    public void load(Collection<Order> orders) {
        for (Order order : orders) {
            restore(order);
        }
    }

    /**
     * Removes an order from the book entirely, regardless of its matchable status. Used for
     * cancellation (B5) and by {@link #expireBefore(Instant)}.
     *
     * @return true if the order was present and removed
     */
    public boolean remove(UUID orderId) {
        Order order = buyBook.get(orderId);
        boolean isBuy = true;
        if (order == null) {
            order = sellBook.get(orderId);
            isBuy = false;
        }
        if (order == null) return false;

        Order target = order;
        boolean finalIsBuy = isBuy;
        return withLock(target.getWindow(), () -> {
            Map<UUID, Order> book = finalIsBuy ? buyBook : sellBook;
            Map<Long, ConcurrentHashMap<UUID, Order>> buckets = finalIsBuy ? buyBuckets : sellBuckets;
            Order removed = book.remove(orderId);
            if (removed == null) return false;
            unregisterBuckets(removed, buckets);
            return true;
        });
    }

    /**
     * Removes every book entry whose window has already ended as of {@code now}. Pure
     * in-memory, cheap, and deterministic given an explicit {@code now} — see D11. Called only
     * by {@link com.exgpu.exgpu.scheduler.OrderExpiryScheduler}; matching itself never calls
     * this or consults a clock.
     *
     * @return the ids removed
     */
    public List<UUID> expireBefore(Instant now) {
        List<UUID> removed = new ArrayList<>();
        for (Order order : buyBook.values()) {
            if (order.isExpired(now) && remove(order.getId())) {
                removed.add(order.getId());
            }
        }
        for (Order order : sellBook.values()) {
            if (order.isExpired(now) && remove(order.getId())) {
                removed.add(order.getId());
            }
        }
        return removed;
    }

    /**
     * Compensating rollback for a match whose surrounding transaction did not commit (D8).
     *
     * <p>{@code CLAUDE.md} asks for "transaction logs for rollback on partial failure" —
     * {@link MatchResult} (specifically its {@link Fill} list) is that log, and this is what
     * makes the promise literal rather than aspirational.
     *
     * <p>For each fill: decrements the counterparty's {@code filledQuantity} by the recorded
     * amount (never restores to a snapshot — decrementing is what composes correctly with a
     * concurrent, unrelated increment landing on the same order from another thread) and
     * reinserts it into the book if it is matchable again. Then removes the incoming order
     * from the book entirely, if it is still there.
     *
     * <p>{@code result} may describe a full match (D8's whole-placement rollback) or a single
     * dropped fill (D9/D10's per-allocation compensation) — in the latter case callers pass a
     * result built with just the one {@link Fill} and no {@code incoming}, and only that one
     * counterparty is touched.
     *
     * <p>Not crash-safe on its own: if the process dies between a DB rollback and this call
     * running, the book keeps a phantom fill until the next restart, when
     * {@link OrderBookRehydrator} rebuilds the book from Postgres (the authoritative source)
     * and the phantom is gone.
     */
    public void rollback(MatchResult result) {
        if (result == null) return;

        for (Fill fill : result.getFills()) {
            Order order = fill.getOrder();
            if (order == null) continue;
            compensateFill(order, fill.getQuantityFilled());
        }

        Order incoming = result.getIncoming();
        if (incoming != null) {
            remove(incoming.getId());
        }
    }

    private void compensateFill(Order order, int quantityFilled) {
        boolean isBuy = order.getSide() == OrderSide.BUY;
        withLock(order.getWindow(), () -> {
            Runnable mutate = () -> {
                order.setFilledQuantity(Math.max(0, order.getFilledQuantity() - quantityFilled));
                order.recomputeStatus();
                if (order.isMatchable()) {
                    insert(order, isBuy);
                }
            };
            if (striped) {
                Lock orderLock = locks.orderLock(order.getId());
                orderLock.lock();
                try {
                    mutate.run();
                } finally {
                    orderLock.unlock();
                }
            } else {
                mutate.run();
            }
            return null;
        });
    }

    /** Tier-1 (or the global lock, when non-striped) for the whole of {@code body}. */
    private <T> T withLock(TimeWindow window, Supplier<T> body) {
        if (striped) {
            try (TimeSliceLockManager.Handle h = locks.acquire(window)) {
                return body.get();
            }
        } else {
            globalLock.lock();
            try {
                return body.get();
            } finally {
                globalLock.unlock();
            }
        }
    }

    public int buyBookSize() {
        return buyBook.size();
    }

    public int sellBookSize() {
        return sellBook.size();
    }
}
