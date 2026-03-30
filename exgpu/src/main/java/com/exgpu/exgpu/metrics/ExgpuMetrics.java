package com.exgpu.exgpu.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Application meters, exposed on {@code /actuator/prometheus}.
 *
 * <p><b>Reserved Prometheus suffixes — never end a meter name in one of these:</b>
 * {@code _total}, {@code _created}, {@code _count}, {@code _sum}, {@code _bucket},
 * {@code _info}. The Prometheus client's {@code sanitizeMetricName} strips them before
 * rendering, and Micrometer's own counter renderer separately appends {@code _total}. Stack
 * the two — e.g. name a counter {@code foo_created_total} — and what actually gets scraped is
 * {@code foo_total} (Micrometer strips the trailing {@code _total} it recognises, the
 * Prometheus client then strips the reserved {@code _created} suffix, and the counter renderer
 * appends {@code _total} back on), which does not match the name any dashboard or alert was
 * written against. {@code exgpu_matches_total} and {@code exgpu_allocations_total} were
 * previously named with a {@code _created} in the middle for exactly this reason, and the
 * "Matches Created" / "Allocations Created" Grafana panels read "No data" as a result — fixed
 * by naming the meter what it round-trips to, everywhere, not just here.
 */
@Component
public class ExgpuMetrics {

    private final Counter ordersSubmitted;
    private final Counter matchesCreated;
    private final Counter allocationsCreated;
    private final Counter usageEventsProcessed;
    private final Counter usageEventsDuplicate;
    private final Counter usageEventsDlq;
    private final Counter billingDeductions;
    private final Counter killCompute;
    private final Counter leasesActivated;
    private final Counter leasesExpired;
    private final Counter leasesRevoked;
    private final Counter accessCredentialsIssued;
    private final Counter ordersCancelled;
    private final Counter ordersExpired;
    private final Counter bookingChargeFailures;
    private final Timer billingProcessingTimer;
    private final Timer matchingLatencyTimer;
    private final Timer matchingLockWaitTimer;
    private final DistributionSummary matchingLockStripes;

    public ExgpuMetrics(MeterRegistry registry) {
        ordersSubmitted = Counter.builder("exgpu_orders_submitted_total")
                .description("Total orders submitted").register(registry);

        // Named so the meter round-trips to itself through Micrometer's trailing-_total strip
        // and the Prometheus client's reserved-_created strip — see class Javadoc.
        matchesCreated = Counter.builder("exgpu_matches_total")
                .description("Total order matches created").register(registry);
        allocationsCreated = Counter.builder("exgpu_allocations_total")
                .description("Total allocation rows created").register(registry);

        usageEventsProcessed = Counter.builder("exgpu_usage_events_processed_total")
                .description("Total new usage events billed").register(registry);
        usageEventsDuplicate = Counter.builder("exgpu_usage_events_duplicate_total")
                .description("Total duplicate usage events skipped").register(registry);
        usageEventsDlq = Counter.builder("exgpu_usage_events_dlq_total")
                .description("Total usage events sent to DLQ").register(registry);
        billingDeductions = Counter.builder("exgpu_billing_deductions_total")
                .description("Total successful token balance deductions").register(registry);
        killCompute = Counter.builder("exgpu_kill_compute_total")
                .description("Total compute kill events (balance reached zero)").register(registry);
        leasesActivated = Counter.builder("exgpu_access_leases_activated_total")
                .description("Access leases opened as their window arrived").register(registry);
        leasesExpired = Counter.builder("exgpu_access_leases_expired_total")
                .description("Access leases closed as their window ended").register(registry);
        leasesRevoked = Counter.builder("exgpu_access_leases_revoked_total")
                .description("Access leases withdrawn early (KillCompute / operator)").register(registry);
        accessCredentialsIssued = Counter.builder("exgpu_access_credentials_issued_total")
                .description("Access credentials minted for active leases").register(registry);
        ordersCancelled = Counter.builder("exgpu_orders_cancelled_total")
                .description("Total orders cancelled by their owner").register(registry);
        ordersExpired = Counter.builder("exgpu_orders_expired_total")
                .description("Total orders swept to EXPIRED").register(registry);
        bookingChargeFailures = Counter.builder("exgpu_booking_charge_failures_total")
                .description("Booking charge attempts dropped because the payer could not afford it")
                .register(registry);

        // publishPercentileHistogram is what actually emits the _bucket series that
        // histogram_quantile() reads. Without it, no bucket series exists at all — the
        // "Billing Processing Latency" panel's two histogram_quantile() targets were empty for
        // exactly this reason. Bounding min/max keeps the bucket ladder relevant to the
        // millisecond-to-low-seconds range this operation actually runs in, instead of
        // Micrometer's much wider default range.
        billingProcessingTimer = Timer.builder("exgpu_billing_processing_seconds")
                .description("Time to process a new usage event through billing")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);

        matchingLatencyTimer = Timer.builder("exgpu_matching_latency_seconds")
                .description("Time spent inside MatchingEngine.submitOrder")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(0).plusNanos(100_000)) // 100 microseconds
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(registry);

        // Also given a histogram (see the class-level warning above) — otherwise the "Tier-1
        // Lock Wait p99" panel would read "No data" for exactly the reason this class exists
        // to fix.
        matchingLockWaitTimer = Timer.builder("exgpu_matching_lock_wait_seconds")
                .description("Time spent blocked acquiring tier-1 time-stripe locks")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofNanos(1_000))
                .maximumExpectedValue(Duration.ofMillis(100))
                .register(registry);

        matchingLockStripes = DistributionSummary.builder("exgpu_matching_lock_stripes")
                .description("Number of tier-1 stripes a single acquisition held")
                .register(registry);
    }

    public void incrementOrdersSubmitted()              { ordersSubmitted.increment(); }
    public void incrementMatchesCreated()               { matchesCreated.increment(); }
    public void incrementAllocationsCreated(int count)  { allocationsCreated.increment(count); }
    public void incrementUsageEventsProcessed()         { usageEventsProcessed.increment(); }
    public void incrementUsageEventsDuplicate()         { usageEventsDuplicate.increment(); }
    public void incrementUsageEventsDlq()               { usageEventsDlq.increment(); }
    public void incrementBillingDeductions()            { billingDeductions.increment(); }
    public void incrementKillCompute()                  { killCompute.increment(); }
    public void incrementLeasesActivated(int count)     { leasesActivated.increment(count); }
    public void incrementLeasesExpired(int count)       { leasesExpired.increment(count); }
    public void incrementLeasesRevoked(int count)       { leasesRevoked.increment(count); }
    public void incrementAccessCredentialsIssued()      { accessCredentialsIssued.increment(); }
    public void incrementOrdersCancelled()              { ordersCancelled.increment(); }
    public void incrementOrdersExpired(int count)       { ordersExpired.increment(count); }
    public void incrementBookingChargeFailures()        { bookingChargeFailures.increment(); }
    public Timer getBillingProcessingTimer()            { return billingProcessingTimer; }
    public Timer getMatchingLatencyTimer()              { return matchingLatencyTimer; }
    public Timer getMatchingLockWaitTimer()             { return matchingLockWaitTimer; }
    public void recordLockStripes(int stripes)          { matchingLockStripes.record(stripes); }
}
