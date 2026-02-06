package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.TimeWindow;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two-tier striped locking over 15-minute time buckets, used by {@link MatchingEngine} when
 * {@code exgpu.matching.striped=true}.
 *
 * <h2>Why two tiers (D1)</h2>
 * A per-bucket lock alone is unsound. Let a resting SELL span buckets {@code [36..59]} (e.g.
 * 09:00–15:00) and let two incoming BUYs land in disjoint sub-ranges of that span — one over
 * {@code [36..38]}, the other over {@code [58..60]}. Each taker only locks its own buckets,
 * both see the SELL as a matchable candidate, both compute {@code matchedQty} against the same
 * pre-mutation {@code remainingQuantity()}, and both apply it — the SELL is over-sold with no
 * bucket lock ever violated. The bucket is not the shared resource; the resting maker is.
 *
 * <p>The fix is two independent lock tiers:
 * <ul>
 *   <li><b>Tier 1 (time stripes)</b> — serializes book <em>structure</em> (the bucket index)
 *       and gives a submission its locality. Acquired for the whole span of the incoming
 *       order's window, before any matching work.</li>
 *   <li><b>Tier 2 (order stripes)</b> — serializes mutation of one specific maker order,
 *       keyed by order id. Acquired immediately before mutating a candidate and released
 *       immediately after, one at a time, always at the leaf.</li>
 * </ul>
 * Correctness comes from re-checking {@code candidate.isMatchable()} and recomputing
 * {@code remainingQuantity()} <em>under the candidate's tier-2 lock</em>, immediately before
 * mutating it — see {@link MatchingEngine}. That re-check is what makes the counterexample
 * above impossible: whichever thread gets there first mutates the candidate and releases; the
 * second thread's re-check then sees the reduced (or exhausted) remaining quantity.
 *
 * <h2>Lock acquisition and release discipline (D4) — READ BEFORE CALLING</h2>
 * <ol>
 *   <li>Tier 1: compute {@link #stripesFor(TimeWindow)}, which is sorted ascending and
 *       deduplicated. Acquire all of them, in that order, before any matching work. Release in
 *       reverse order, in a {@code finally} — {@link #acquire(TimeWindow)} returns an
 *       {@link AutoCloseable} handle so this cannot be forgotten.</li>
 *   <li>Tier 2: acquired only while already holding tier 1. At most one tier-2 lock is held at
 *       a time — acquire it immediately before mutating one candidate, release it immediately
 *       after that candidate's fill completes, before moving to the next candidate.</li>
 *   <li><b>Never</b> acquire a tier-1 lock while holding a tier-2 lock.</li>
 *   <li>The two tiers live in separate fixed arrays, so no single {@link Lock} instance is
 *       ever both a tier-1 and a tier-2 lock.</li>
 * </ol>
 * Deadlock-freedom follows from these rules: tier-1 acquisitions are always sorted ascending
 * by array index, so no cycle can form among them (the standard "acquire locks in a total
 * order" argument). Tier 2 is always a single leaf lock, never held while waiting on anything
 * else, so it cannot participate in a cycle either. The invariant is not visible from any one
 * call site, which is why it is written here rather than left implicit.
 *
 * <h2>Fixed stripe arrays, not a lock-per-bucket map (D3)</h2>
 * Both tiers are fixed-size {@link ReentrantLock} arrays, sized once at construction. This
 * makes lock-object reclamation a non-problem rather than a solved one — there is nothing to
 * reclaim, unlike a {@code Map<Long, Lock>} with reference counting (a classic source of
 * acquire/release/refcount races). The accepted cost is false contention: two unrelated
 * buckets sharing a stripe serialize needlessly. That is correctness-neutral and is the
 * standard trade in every striped-lock design.
 */
public final class TimeSliceLockManager {

    /** 15-minute buckets, epoch-aligned. */
    public static final long BUCKET_SECONDS = 900;

    private static final int DEFAULT_TIME_STRIPES = 1024;
    private static final int DEFAULT_ORDER_STRIPES = 512;

    private final ReentrantLock[] timeStripes;
    private final ReentrantLock[] orderStripes;

    /** Defaults, used directly by tests. Production sizes come from properties — see below. */
    public TimeSliceLockManager() {
        this(DEFAULT_TIME_STRIPES, DEFAULT_ORDER_STRIPES);
    }

    /**
     * Explicit stripe counts. Used by tests to force collisions the defaults make rare, and by
     * {@link com.exgpu.exgpu.config.MatchingConfig}, which builds the singleton bean from
     * {@code exgpu.matching.lock-stripes} / {@code exgpu.matching.order-lock-stripes}.
     *
     * <p>This class stays free of Spring annotations on purpose: it is a concurrency primitive
     * with plain-JUnit tests, so the framework wiring lives in a {@code @Configuration} rather
     * than here. {@link MatchingEngine} injects it unconditionally, so the bean must exist even
     * when {@code exgpu.matching.striped=false} — in that mode nothing calls the instance (only
     * the static {@link #bucketRange} helpers, which maintain the bucket index), but the context
     * still fails to start without it.
     */
    public TimeSliceLockManager(int timeStripes, int orderStripes) {
        if (Integer.bitCount(timeStripes) != 1 || Integer.bitCount(orderStripes) != 1) {
            throw new IllegalArgumentException(
                    "stripe counts must be powers of two for masking to be safe: "
                            + timeStripes + ", " + orderStripes);
        }
        this.timeStripes = newLocks(timeStripes);
        this.orderStripes = newLocks(orderStripes);
    }

    private static ReentrantLock[] newLocks(int n) {
        ReentrantLock[] locks = new ReentrantLock[n];
        for (int i = 0; i < n; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    /**
     * Epoch-aligned bucket a given instant falls in. {@code floorDiv}, not {@code /}, so an
     * instant before the epoch buckets correctly instead of rounding toward zero.
     */
    static long bucketOf(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), BUCKET_SECONDS);
    }

    /**
     * Inclusive bucket range spanned by a window, {@code [bucketOf(start), bucketOf(end)]}.
     *
     * <p>The end bucket is included even when the window ends exactly on a bucket boundary.
     * This deliberately over-locks by at most one bucket, and it is required for soundness:
     * {@link TimeWindow#overlaps} is <b>closed</b> on both ends — two windows that merely touch
     * at an instant already count as overlapping today. A half-open span here would under-lock
     * exactly those pairs and let a match proceed with one of its buckets unheld. Rule: the
     * bucket span must never be narrower than the overlap predicate it is protecting.
     */
    static long[] bucketRange(TimeWindow w) {
        long from = bucketOf(w.getStart());
        long to = bucketOf(w.getEnd());
        int count = (int) (to - from + 1);
        long[] range = new long[count];
        for (int i = 0; i < count; i++) {
            range[i] = from + i;
        }
        return range;
    }

    /**
     * The tier-1 stripe indices a window's bucket span maps to — sorted ascending and
     * deduplicated. This is the A2 invariant that makes tier-1 deadlock-freedom hold; exposed
     * (package-private) specifically so it is directly, deterministically assertable in tests
     * rather than only inferable from a race.
     *
     * <p>Indexed by <b>masking, not hashing</b>: {@code (int) (bucket & (STRIPES - 1))}. Bucket
     * keys within one span are contiguous, so masking maps a span of at most
     * {@code STRIPES} buckets onto <em>distinct</em> stripes — a single order's own span never
     * self-collides. Hashing the bucket key first would scatter a contiguous span
     * pseudo-randomly and could collide a span against itself, inflating the held-lock count
     * for no benefit (unlike order ids, which are not contiguous and so are hashed below).
     */
    int[] stripesFor(TimeWindow w) {
        long[] buckets = bucketRange(w);
        int mask = timeStripes.length - 1;
        int[] stripes = new int[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            stripes[i] = (int) (buckets[i] & mask);
        }
        Arrays.sort(stripes);
        int uniqueCount = 0;
        for (int i = 0; i < stripes.length; i++) {
            if (i == 0 || stripes[i] != stripes[i - 1]) {
                stripes[uniqueCount++] = stripes[i];
            }
        }
        return Arrays.copyOf(stripes, uniqueCount);
    }

    /**
     * Acquires every tier-1 stripe a window's bucket span touches, sorted ascending, and
     * returns a handle that releases them (in reverse order) on {@link Handle#close()}.
     *
     * <pre>{@code
     * try (TimeSliceLockManager.Handle h = locks.acquire(incoming.getWindow())) {
     *     ...
     * }
     * }</pre>
     */
    public Handle acquire(TimeWindow w) {
        int[] stripes = stripesFor(w);
        for (int stripe : stripes) {
            timeStripes[stripe].lock();
        }
        return new HandleImpl(stripes);
    }

    /** Tier-2 leaf lock for one order id. Hashed — UUIDs are not contiguous like bucket keys. */
    Lock orderLock(UUID orderId) {
        int mask = orderStripes.length - 1;
        int index = orderId.hashCode() & mask;
        return orderStripes[index];
    }

    /** Number of tier-1 stripes, exposed for metrics/tests only. */
    int timeStripeCount() {
        return timeStripes.length;
    }

    /** Direct access to one tier-1 stripe by raw index — package-private, test seam only. */
    ReentrantLock timeStripe(int index) {
        return timeStripes[index];
    }

    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private final class HandleImpl implements Handle {
        private final int[] stripes;

        HandleImpl(int[] stripes) {
            this.stripes = stripes;
        }

        @Override
        public void close() {
            for (int i = stripes.length - 1; i >= 0; i--) {
                timeStripes[stripes[i]].unlock();
            }
        }
    }
}
