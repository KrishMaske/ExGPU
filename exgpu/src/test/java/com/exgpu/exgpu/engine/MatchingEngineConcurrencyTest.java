package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.MatchResult;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Concurrency tests for {@link MatchingEngine} — the precondition
 * {@link TimeSliceLockManager}'s own (former) Javadoc set: "do not wire it into the matching
 * path until the lock-acquisition ordering is designed and the simpler synchronized matcher is
 * fully covered by concurrency tests." All 13 {@code MatchingEngineTest} cases are
 * single-threaded; these are not.
 *
 * <p>Run against <b>both</b> engine modes via {@code @ValueSource(booleans = {false, true})} —
 * {@code striped=false} retro-covers the Step 6 single-lock matcher (trivially correct, since
 * it fully serializes), and {@code striped=true} is D1's actual claim under test.
 */
class MatchingEngineConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    private MatchingEngine newEngine(boolean striped) {
        return new MatchingEngine(new TimeSliceLockManager(), new ExgpuMetrics(new SimpleMeterRegistry()), striped);
    }

    private Order order(OrderSide side, double price, int qty, TimeWindow window) {
        return Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(side)
                .pricePerGpuHour(BigDecimal.valueOf(price))
                .quantity(qty)
                .window(window)
                .priorityTimestamp(Instant.now())
                .build();
    }

    // ── C1 — no double-allocation under simultaneous submissions ─────────────

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noDoubleAllocation_underOneHundredSimultaneousBuys(boolean striped) throws Exception {
        MatchingEngine engine = newEngine(striped);
        TimeWindow window = new TimeWindow(T0, T0.plus(1, ChronoUnit.HOURS));

        Order sell = order(OrderSide.SELL, 1.00, 100, window);
        engine.submitOrder(sell);

        int threadCount = 100;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAllocated = new AtomicInteger(0);
        AtomicInteger negativeRemainingObserved = new AtomicInteger(0);

        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    Order buy = order(OrderSide.BUY, 1.00, 1, window);
                    MatchResult result = engine.submitOrder(buy);
                    totalAllocated.addAndGet(result.totalMatchedQuantity());
                    if (sell.remainingQuantity() < 0) negativeRemainingObserved.incrementAndGet();
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get(); // propagate any assertion/execution error
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(totalAllocated.get()).isEqualTo(100);
        assertThat(sell.getFilledQuantity()).isEqualTo(100);
        assertThat(sell.getStatus()).isEqualTo(com.exgpu.exgpu.domain.enums.OrderStatus.FILLED);
        assertThat(negativeRemainingObserved.get()).isZero();
        assertThat(engine.sellBookSize()).isZero();
    }

    // ── C2 — the cross-bucket hazard (proves D1) ─────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossBucketHazard_neverOversellsAWideRestingMaker(boolean striped) throws Exception {
        MatchingEngine engine = newEngine(striped);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                // A resting SELL spanning 09:00–15:00 (six hours — far wider than either taker).
                TimeWindow sellWindow = new TimeWindow(T0, T0.plus(6, ChronoUnit.HOURS));
                Order sell = order(OrderSide.SELL, 1.00, 10, sellWindow);
                engine.submitOrder(sell);

                // Two BUYs over disjoint 30-minute sub-windows, both overlapping the SELL, but
                // NOT overlapping each other — so their bucket spans share none of their own
                // buckets, only the resting SELL's.
                TimeWindow earlyWindow = new TimeWindow(T0, T0.plus(30, ChronoUnit.MINUTES));
                TimeWindow lateWindow = new TimeWindow(
                        T0.plus(5, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES),
                        T0.plus(6, ChronoUnit.HOURS));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<Integer> early = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return engine.submitOrder(order(OrderSide.BUY, 1.00, 10, earlyWindow))
                            .totalMatchedQuantity();
                };
                Callable<Integer> late = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return engine.submitOrder(order(OrderSide.BUY, 1.00, 10, lateWindow))
                            .totalMatchedQuantity();
                };

                Future<Integer> f1 = pool.submit(early);
                Future<Integer> f2 = pool.submit(late);
                int totalMatched = f1.get(10, TimeUnit.SECONDS) + f2.get(10, TimeUnit.SECONDS);

                assertThat(totalMatched)
                        .as("iteration %d: sell only had 10 GPUs to sell", i)
                        .isLessThanOrEqualTo(10);
                assertThat(sell.getFilledQuantity()).isLessThanOrEqualTo(sell.getQuantity());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ── C3 — deadlock-free under overlapping bucket sets (soak) ──────────────

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlockFree_underReverseOverlappingBucketSpans_tenThousandIterations(boolean striped) {
        MatchingEngine engine = newEngine(striped);
        int iterations = 10_000;

        // [b0, b40] vs [b40, b80] — the two spans overlap only at the shared boundary bucket,
        // and the two threads approach it from opposite ends.
        TimeWindow windowA = new TimeWindow(T0, T0.plusSeconds(900 * 40));
        TimeWindow windowB = new TimeWindow(T0.plusSeconds(900 * 40), T0.plusSeconds(900 * 80));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Void> taskA = () -> {
                    for (int i = 0; i < iterations; i++) {
                        engine.submitOrder(order(OrderSide.BUY, 1.00, 1, windowA));
                    }
                    return null;
                };
                Callable<Void> taskB = () -> {
                    for (int i = 0; i < iterations; i++) {
                        engine.submitOrder(order(OrderSide.SELL, 1.00, 1, windowB));
                    }
                    return null;
                };
                Future<Void> fa = pool.submit(taskA);
                Future<Void> fb = pool.submit(taskB);
                fa.get();
                fb.get();
            } finally {
                pool.shutdownNow();
            }
        });
    }

    // ── C4 — parallelism is real, not theatre ─────────────────────────────────

    @org.junit.jupiter.api.Test
    void disjointBucketRanges_neverWidenLockSetToTheWholeArray() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExgpuMetrics metrics = new ExgpuMetrics(registry);
        MatchingEngine engine = new MatchingEngine(new TimeSliceLockManager(), metrics, true);

        // Two short (4-bucket) windows, deliberately far apart so their stripe sets cannot
        // collide even under masking with the default 1024-stripe array.
        TimeWindow windowA = new TimeWindow(T0, T0.plusSeconds(900 * 4));
        TimeWindow windowB = new TimeWindow(T0.plusSeconds(900 * 2000), T0.plusSeconds(900 * 2004));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> taskA = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 200; i++) {
                    engine.submitOrder(order(OrderSide.BUY, 1.00, 1, windowA));
                }
                return null;
            };
            Callable<Void> taskB = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 200; i++) {
                    engine.submitOrder(order(OrderSide.SELL, 1.00, 1, windowB));
                }
                return null;
            };
            Future<Void> fa = pool.submit(taskA);
            Future<Void> fb = pool.submit(taskB);
            fa.get(15, TimeUnit.SECONDS);
            fb.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        double maxStripesHeld = registry.get("exgpu_matching_lock_stripes").summary().max();
        // Each window spans exactly 5 buckets (4 * 900s duration, but the end bucket is
        // INCLUSIVE per D2, so bucketOf(start)..bucketOf(start + 4*900s) is 5 buckets, not 4).
        // Either way it is nowhere near the full 1024-stripe array — if it were, striping had
        // degenerated back into an effectively global lock.
        assertThat(maxStripesHeld).isLessThanOrEqualTo(5.0);
    }
}
