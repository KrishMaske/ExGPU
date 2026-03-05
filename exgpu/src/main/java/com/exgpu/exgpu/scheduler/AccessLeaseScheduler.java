package com.exgpu.exgpu.scheduler;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.exgpu.exgpu.domain.AccessLease;
import com.exgpu.exgpu.domain.enums.LeaseStatus;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.AccessLeaseRepository;

/**
 * Moves leases through their lifecycle as the clock advances.
 *
 * <h2>Idempotency</h2>
 * Every transition is a conditional bulk UPDATE whose WHERE clause includes the state being
 * moved <em>out of</em> (see {@link AccessLeaseRepository#activateDueLeases}). Consequences:
 *
 * <ul>
 *   <li>Running the tick twice for the same instant is a no-op the second time — the rows are
 *       no longer in the source state.</li>
 *   <li>A tick that runs late still does the right thing: it transitions everything that has
 *       become due since the last run, not just what became due in the last interval.</li>
 *   <li>Two instances ticking concurrently cannot double-apply. The database serialises the
 *       updates and the loser matches zero rows.</li>
 *   <li>A missed tick (process restart, GC pause) self-heals on the next one, because the
 *       queries are expressed against absolute window boundaries rather than a delta since
 *       the previous run.</li>
 * </ul>
 *
 * <p>That last property is why the tick carries no cursor or watermark: there is no state to
 * lose, so nothing needs recovering after a crash.
 *
 * <h2>Why the read path re-checks anyway</h2>
 * A tick every {@value #TICK_MS}ms means a lease can be up to that long out of date. The
 * access endpoint therefore evaluates the window itself rather than trusting the stored
 * status — the scheduler keeps the table honest for queries and metrics, but it is not what
 * gates a credential.
 */
@Component
public class AccessLeaseScheduler {

    /** 15s: fine-grained enough that a status looks live, cheap enough to be irrelevant. */
    static final long TICK_MS = 15_000;

    private static final Logger log = LoggerFactory.getLogger(AccessLeaseScheduler.class);

    private final AccessLeaseRepository leaseRepository;
    private final RealtimeEventPublisher events;
    private final ExgpuMetrics metrics;

    public AccessLeaseScheduler(AccessLeaseRepository leaseRepository,
                                RealtimeEventPublisher events,
                                ExgpuMetrics metrics) {
        this.leaseRepository = leaseRepository;
        this.events = events;
        this.metrics = metrics;
    }

    /**
     * One lifecycle tick.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: if a tick ever runs long, delay
     * spaces the next one out instead of queueing overlapping executions against the same
     * rows.
     */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = 5_000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();

        // Order matters. Expiring first would let a lease whose window opened and closed
        // within one tick be activated afterwards and left ACTIVE past its end.
        List<AccessLease> aboutToOpen = leaseRepository.findAll().stream()
                .filter(l -> l.getStatus() == LeaseStatus.PENDING)
                .filter(l -> !l.windowNotStarted(now) && !l.windowHasEnded(now))
                .toList();

        int activated = leaseRepository.activateDueLeases(now);
        int expired = leaseRepository.expireEndedLeases(now);

        if (activated > 0) {
            metrics.incrementLeasesActivated(activated);
            // Tell each buyer their access just opened, so a UI that is not polling still
            // updates. The payload carries no credential — the client fetches that itself.
            for (AccessLease lease : aboutToOpen) {
                events.publishToUser(lease.getBuyerId(), RealtimeEventType.ACCESS_GRANTED,
                        "Your compute window is open — access key available now",
                        lease.getAllocationId().toString(), null);
            }
        }
        if (expired > 0) {
            metrics.incrementLeasesExpired(expired);
        }

        if (activated > 0 || expired > 0) {
            log.info("Lease tick: activated={} expired={}", activated, expired);
        }
    }
}
