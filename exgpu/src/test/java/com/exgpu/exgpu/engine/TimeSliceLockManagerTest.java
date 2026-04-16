package com.exgpu.exgpu.engine;

import com.exgpu.exgpu.domain.TimeWindow;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, no-Spring tests for {@link TimeSliceLockManager} — the lock manager itself,
 * not the engine wired on top of it. See the Test plan's L1–L4.
 */
class TimeSliceLockManagerTest {

    private static final Instant EPOCH = Instant.EPOCH;

    // ── L1 — bucket derivation ────────────────────────────────────────────────

    @Test
    void bucketOf_isEpochAlignedTo900Seconds() {
        assertThat(TimeSliceLockManager.bucketOf(EPOCH)).isEqualTo(0);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.plusSeconds(899))).isEqualTo(0);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.plusSeconds(900))).isEqualTo(1);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.plusSeconds(1799))).isEqualTo(1);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.plusSeconds(1800))).isEqualTo(2);
    }

    @Test
    void bucketOf_isSafeForInstantsBeforeTheEpoch() {
        // floorDiv, not integer division: an instant one second before an aligned boundary
        // must round DOWN to the earlier bucket, not toward zero.
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.minusSeconds(1))).isEqualTo(-1);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.minusSeconds(900))).isEqualTo(-1);
        assertThat(TimeSliceLockManager.bucketOf(EPOCH.minusSeconds(901))).isEqualTo(-2);
    }

    @Test
    void bucketRange_isInclusiveOfTheEndBucket_evenOnAnExactBoundary() {
        // A window ending exactly on a bucket boundary [0s, 900s) would, half-open, belong
        // only to bucket 0. This span must still include bucket 1 (D2) because
        // TimeWindow.overlaps is closed on both ends.
        TimeWindow window = new TimeWindow(EPOCH, EPOCH.plusSeconds(900));
        long[] range = TimeSliceLockManager.bucketRange(window);

        assertThat(range).containsExactly(0L, 1L);
    }

    @Test
    void bucketRange_singleBucketWindow_isOneElement() {
        TimeWindow window = new TimeWindow(EPOCH.plusSeconds(100), EPOCH.plusSeconds(200));
        assertThat(TimeSliceLockManager.bucketRange(window)).containsExactly(0L);
    }

    @Test
    void windowsThatOnlyTouchAtAnInstant_produceOverlappingBucketSpans() {
        // Mirrors TimeWindow.overlaps being closed on both ends: A ends exactly where B
        // starts, so overlaps() returns true, and the bucket spans must agree — sharing the
        // boundary bucket — or a half-open span would silently drop a match the engine makes.
        TimeWindow a = new TimeWindow(EPOCH, EPOCH.plusSeconds(900));
        TimeWindow b = new TimeWindow(EPOCH.plusSeconds(900), EPOCH.plusSeconds(1800));

        assertThat(a.overlaps(b)).isTrue();

        long[] rangeA = TimeSliceLockManager.bucketRange(a);
        long[] rangeB = TimeSliceLockManager.bucketRange(b);
        assertThat(rangeA).contains(1L);
        assertThat(rangeB).contains(1L);
    }

    // ── L2 — the A2 invariant: sorted, deduplicated stripe sets ──────────────

    @Test
    void stripesFor_isSortedAscendingAndDeduplicated_acrossVariousWindowSizes() {
        TimeSliceLockManager manager = new TimeSliceLockManager();

        assertSortedAndDeduped(manager, bucketsWindow(1));
        assertSortedAndDeduped(manager, bucketsWindow(4));
        assertSortedAndDeduped(manager, bucketsWindow(96));
        assertSortedAndDeduped(manager, bucketsWindow(5000));
    }

    private void assertSortedAndDeduped(TimeSliceLockManager manager, TimeWindow window) {
        int[] stripes = manager.stripesFor(window);
        for (int i = 1; i < stripes.length; i++) {
            assertThat(stripes[i]).isGreaterThan(stripes[i - 1]);
        }
    }

    /** A window spanning exactly {@code bucketCount} 900s buckets, starting at epoch. */
    private TimeWindow bucketsWindow(int bucketCount) {
        return new TimeWindow(EPOCH, EPOCH.plusSeconds(900L * bucketCount));
    }

    // ── L3 — deterministic deadlock demonstration ────────────────────────────

    /**
     * Proves two things with no sleeps and no probabilistic timing:
     * <ol>
     *   <li>the deadlock <em>condition</em> is real for an unsorted acquisition order — two
     *       threads each holding one of two locks and trying to acquire the other's cannot
     *       both succeed;</li>
     *   <li>the real {@link TimeSliceLockManager#acquire} does not exhibit it, because it
     *       always acquires in sorted order regardless of which "thread" is asking.</li>
     * </ol>
     */
    @Test
    void acquire_sortsStripes_soConcurrentOverlappingAcquisitionsCannotDeadlock() throws Exception {
        TimeSliceLockManager manager = new TimeSliceLockManager(2, 2);
        ReentrantLock stripeA = manager.timeStripe(0);
        ReentrantLock stripeB = manager.timeStripe(1);

        // Two barriers, not one: the first proves both threads hold their own stripe before
        // either attempts to steal the other's; the second holds each thread's OWN stripe
        // locked until both attempts have actually happened, so neither thread can race ahead,
        // finish its whole attempt-then-release cycle, and free up the stripe the other one is
        // about to (or already did) ask for. Without the second barrier this is flaky: nothing
        // stops one thread's 200ms tryLock from starting and finishing before the other's has
        // even begun.
        CyclicBarrier bothHolding = new CyclicBarrier(2);
        CyclicBarrier bothAttempted = new CyclicBarrier(2);
        AtomicBoolean t1GotB = new AtomicBoolean(true);
        AtomicBoolean t2GotA = new AtomicBoolean(true);

        Thread t1 = new Thread(() -> {
            stripeA.lock();
            try {
                await(bothHolding);
                try {
                    t1GotB.set(stripeB.tryLock(200, TimeUnit.MILLISECONDS));
                    if (t1GotB.get()) stripeB.unlock();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    await(bothAttempted);
                }
            } finally {
                stripeA.unlock();
            }
        });
        Thread t2 = new Thread(() -> {
            stripeB.lock();
            try {
                await(bothHolding);
                try {
                    t2GotA.set(stripeA.tryLock(200, TimeUnit.MILLISECONDS));
                    if (t2GotA.get()) stripeA.unlock();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    await(bothAttempted);
                }
            } finally {
                stripeB.unlock();
            }
        });

        t1.start();
        t2.start();
        t1.join(5_000);
        t2.join(5_000);

        // Both sides held their own lock and failed to steal the other's within the timeout —
        // the deadlock CONDITION exists for unsorted acquisition.
        assertThat(t1GotB.get()).isFalse();
        assertThat(t2GotA.get()).isFalse();

        // Now prove the real acquire() path does not have this problem: a window spanning
        // both buckets, acquired concurrently by two threads, always locks in the same
        // (sorted) order, so both complete.
        TimeWindow window = new TimeWindow(EPOCH, EPOCH.plusSeconds(1_200)); // spans 2 buckets
        assertThat(manager.stripesFor(window)).containsExactly(0, 1);

        CountDownLatch done = new CountDownLatch(2);
        Runnable acquireAndRelease = () -> {
            try (TimeSliceLockManager.Handle h = manager.acquire(window)) {
                // critical section intentionally empty
            } finally {
                done.countDown();
            }
        };
        Thread r1 = new Thread(acquireAndRelease);
        Thread r2 = new Thread(acquireAndRelease);
        r1.start();
        r2.start();

        boolean completed = done.await(5, TimeUnit.SECONDS);
        assertThat(completed).as("both acquire() calls completed without deadlocking").isTrue();
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── L4 — no growth ────────────────────────────────────────────────────────

    @Test
    void stripeArray_doesNotGrow_evenAcrossTenThousandDistinctBuckets() {
        TimeSliceLockManager manager = new TimeSliceLockManager();
        int before = manager.timeStripeCount();

        TimeWindow hugeWindow = new TimeWindow(EPOCH, EPOCH.plusSeconds(900L * 10_000));
        try (TimeSliceLockManager.Handle h = manager.acquire(hugeWindow)) {
            assertThat(manager.timeStripeCount()).isEqualTo(before);
        }
        assertThat(manager.timeStripeCount()).isEqualTo(before);
    }

    @RepeatedTest(3)
    void stripesFor_maskedIndexing_neverExceedsArrayBounds() {
        TimeSliceLockManager manager = new TimeSliceLockManager(64, 32);
        TimeWindow window = new TimeWindow(EPOCH, EPOCH.plusSeconds(900L * 500));
        int[] stripes = manager.stripesFor(window);
        for (int s : stripes) {
            assertThat(s).isBetween(0, 63);
        }
    }
}
