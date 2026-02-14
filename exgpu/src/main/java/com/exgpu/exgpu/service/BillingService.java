package com.exgpu.exgpu.service;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.TokenBalance;
import com.exgpu.exgpu.domain.UsageLedger;
import com.exgpu.exgpu.domain.enums.ChargeType;
import com.exgpu.exgpu.domain.enums.RefundTier;
import com.exgpu.exgpu.domain.enums.RevokeReason;
import com.exgpu.exgpu.dto.BalanceResponse;
import com.exgpu.exgpu.dto.CreateBalanceRequest;
import com.exgpu.exgpu.dto.SubmitUsageEventRequest;
import com.exgpu.exgpu.dto.UsageEventResponse;
import com.exgpu.exgpu.dto.UsageLedgerResponse;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.TokenBalanceRepository;
import com.exgpu.exgpu.repository.UsageLedgerRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class BillingService {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final TokenBalanceRepository tokenBalanceRepository;
    private final UsageLedgerRepository usageLedgerRepository;
    private final AllocationRepository allocationRepository;
    private final ExgpuMetrics metrics;
    private final RealtimeEventPublisher events;
    private final AccessLeaseService accessLeaseService;

    public BillingService(TokenBalanceRepository tokenBalanceRepository,
                          UsageLedgerRepository usageLedgerRepository,
                          AllocationRepository allocationRepository,
                          ExgpuMetrics metrics,
                          RealtimeEventPublisher events,
                          AccessLeaseService accessLeaseService) {
        this.tokenBalanceRepository = tokenBalanceRepository;
        this.usageLedgerRepository = usageLedgerRepository;
        this.allocationRepository = allocationRepository;
        this.metrics = metrics;
        this.events = events;
        this.accessLeaseService = accessLeaseService;
    }

    /**
     * @param ownerId the authenticated caller. Taken from the verified JWT, so a top-up can
     *                only ever credit the account of whoever made the request.
     */
    @Transactional
    public BalanceResponse createOrTopUp(CreateBalanceRequest request, UUID ownerId) {
        TokenBalance balance = tokenBalanceRepository.findById(ownerId)
                .map(existing -> {
                    existing.topUp(request.amount());
                    return existing;
                })
                .orElseGet(() -> TokenBalance.builder()
                        .buyerId(ownerId)
                        .balance(request.amount())
                        .build());

        BalanceResponse response = BalanceResponse.from(tokenBalanceRepository.save(balance));
        events.publishToUser(ownerId, RealtimeEventType.BALANCE_UPDATED,
                "Balance updated to " + response.balance() + " tokens",
                response.ownerId().toString(), response);
        return response;
    }

    /**
     * A user's balance, or a zero balance if they have never topped up.
     *
     * <p>Deliberately not a 404. Every authenticated user conceptually has an account with
     * zero tokens in it, and a signed-in product page asking "what is my balance?" should
     * render "0" rather than an error the first time.
     */
    @Transactional(readOnly = true)
    public BalanceResponse findBalanceOrZero(UUID ownerId) {
        return tokenBalanceRepository.findById(ownerId)
                .map(BalanceResponse::from)
                .orElseGet(() -> BalanceResponse.zeroFor(ownerId));
    }

    /** Strict lookup used by internal billing paths, where a missing balance is a real error. */
    @Transactional(readOnly = true)
    public BalanceResponse findBalance(UUID ownerId) {
        return tokenBalanceRepository.findById(ownerId)
                .map(BalanceResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No balance found for owner: " + ownerId));
    }

    @Transactional
    public UsageEventResponse submitUsageEvent(SubmitUsageEventRequest request) {
        return usageLedgerRepository.findByIdempotencyKey(request.eventId())
                .map(existing -> {
                    BigDecimal remaining = tokenBalanceRepository.findById(existing.getBuyerId())
                            .map(TokenBalance::getBalance)
                            .orElse(BigDecimal.ZERO);
                    metrics.incrementUsageEventsDuplicate();
                    UsageEventResponse response = UsageEventResponse.of(
                            existing, remaining, remaining.compareTo(BigDecimal.ZERO) == 0, true);
                    events.publishToUser(existing.getBuyerId(), RealtimeEventType.DUPLICATE_USAGE_EVENT,
                            "Duplicate usage event skipped: " + request.eventId(),
                            request.eventId(), response);
                    return response;
                })
                .orElseGet(() -> metrics.getBillingProcessingTimer().record(() -> processNewEvent(request)));
    }

    private UsageEventResponse processNewEvent(SubmitUsageEventRequest request) {
        Allocation allocation = allocationRepository.findById(request.allocationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Allocation not found: " + request.allocationId()));

        // Who pays and at what price are taken from the matched allocation, not from the
        // event. A producer can only ever report *how many seconds* were used.
        UUID buyerId = allocation.getBuyerId();
        BigDecimal executionPrice = allocation.getExecutionPrice();
        if (buyerId == null || executionPrice == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Allocation " + allocation.getId()
                            + " has no buyer/execution price and cannot be billed");
        }

        // Cap usage at the allocation window, cumulatively: the sum of everything already
        // billed plus this event must still fit inside the window. (Duplicate events are
        // filtered out before this point, so a resend never inflates the running total.)
        long windowSeconds = Duration.between(
                allocation.getWindow().getStart(), allocation.getWindow().getEnd()).getSeconds();
        long alreadyBilledSeconds = usageLedgerRepository.sumUsageSecondsByAllocationId(allocation.getId());
        if (alreadyBilledSeconds + request.usageSeconds() > windowSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Usage of " + request.usageSeconds() + "s plus already-billed "
                            + alreadyBilledSeconds + "s exceeds allocation window of "
                            + windowSeconds + "s");
        }

        TokenBalance balance = tokenBalanceRepository.findById(buyerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No balance found for buyer: " + buyerId));

        // cost = (usageSeconds / 3600) * allocation.quantity * executionPrice
        BigDecimal cost = BigDecimal.valueOf(request.usageSeconds())
                .divide(SECONDS_PER_HOUR, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(allocation.getQuantity()))
                .multiply(executionPrice)
                .setScale(6, RoundingMode.HALF_UP);

        // Metering only. The booking charge already took payment for this whole window, so
        // deducting again here would bill the buyer twice for the same hours. The row is
        // still written: it is the telemetry record the Kafka pipeline produces, and it is
        // what makes actual-vs-booked utilisation observable.
        UsageLedger ledger = UsageLedger.builder()
                .allocationId(allocation.getId())
                .buyerId(buyerId)
                .usageSeconds(request.usageSeconds())
                .tokenCost(BigDecimal.ZERO)
                .chargeType(ChargeType.USAGE)
                .idempotencyKey(request.eventId())
                .build();

        UsageLedger saved = usageLedgerRepository.save(ledger);
        metrics.incrementUsageEventsProcessed();

        // cost is what this usage WOULD have cost, reported for transparency but not charged.
        return UsageEventResponse.of(saved, balance.getBalance(), balance.isExhausted(), false);
    }

    /**
     * Charges a buyer for a freshly matched allocation, in full, up front.
     *
     * <p>The billable unit is the <em>booked window</em>, not observed usage. Reserving
     * capacity takes it off the market whether or not the buyer runs anything on it, so the
     * provider has sold those hours either way. This also makes the refund tiers meaningful:
     * a cancellation returns part of a charge that was actually taken.
     *
     * <p>Idempotent on {@code booking:<allocationId>}, so a retried match cannot double-charge
     * — the unique constraint on the idempotency key is the final guard.
     *
     * @throws ResponseStatusException 402 if the buyer cannot cover the booking. Thrown rather
     *         than returned so the surrounding transaction rolls back and no allocation is
     *         left behind that was never paid for.
     */
    @Transactional
    public BigDecimal chargeForBooking(Allocation allocation) {
        String key = "booking:" + allocation.getId();
        if (usageLedgerRepository.findByIdempotencyKey(key).isPresent()) {
            return BigDecimal.ZERO;
        }

        UUID buyerId = allocation.getBuyerId();
        BigDecimal price = allocation.getExecutionPrice();
        if (buyerId == null || price == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Allocation " + allocation.getId() + " has no buyer/price and cannot be billed");
        }

        long windowSeconds = Duration.between(
                allocation.getWindow().getStart(), allocation.getWindow().getEnd()).getSeconds();
        BigDecimal cost = costOf(windowSeconds, allocation.getQuantity(), price);

        TokenBalance balance = tokenBalanceRepository.findById(buyerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Add tokens before renting: this booking costs " + cost));

        try {
            balance.deduct(cost);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Insufficient balance: this booking costs " + cost
                            + " but your balance is " + balance.getBalance());
        }

        tokenBalanceRepository.save(balance);
        metrics.incrementBillingDeductions();

        usageLedgerRepository.save(UsageLedger.builder()
                .allocationId(allocation.getId())
                .buyerId(buyerId)
                .usageSeconds(windowSeconds)
                .tokenCost(cost)
                .chargeType(ChargeType.BOOKING)
                .idempotencyKey(key)
                .build());

        events.publishToUser(buyerId, RealtimeEventType.USAGE_BILLED,
                "Charged " + cost + " tokens for a " + (windowSeconds / 3600) + "h booking",
                allocation.getId().toString(), null);
        events.publishToUser(buyerId, RealtimeEventType.BALANCE_UPDATED,
                "Balance updated to " + balance.getBalance() + " tokens",
                buyerId.toString(), BalanceResponse.from(balance));

        if (balance.isExhausted()) {
            metrics.incrementKillCompute();
            int revoked = accessLeaseService.revokeAllForBuyer(buyerId, RevokeReason.BALANCE_EXHAUSTED);
            if (revoked > 0) {
                metrics.incrementLeasesRevoked(revoked);
                events.publishToUser(buyerId, RealtimeEventType.ACCESS_REVOKED,
                        "Access revoked on " + revoked + " rental(s) — token balance reached zero",
                        buyerId.toString(), null);
            }
        }
        return cost;
    }

    /**
     * Non-throwing affordability pre-check for {@link #chargeForBooking} (D9).
     *
     * <p>Exists specifically so {@code OrderService.placeOrder} can decide, per allocation,
     * whether to keep or drop it <em>before</em> actually charging — it must not be implemented
     * by catching {@code chargeForBooking}'s exception, because that method is
     * {@code @Transactional(REQUIRED)}: throwing from it marks the caller's <em>shared</em>
     * transaction rollback-only, and catching the exception locally still dooms the commit with
     * {@code UnexpectedRollbackException}.
     *
     * <p>Read-only: takes no lock beyond the read itself and never mutates a balance.
     *
     * <p><b>Residual race:</b> two concurrent placements against the same buyer can both pass
     * this check before either has actually charged. {@link TokenBalance}'s optimistic
     * {@code @Version} (mapped by {@code GlobalExceptionHandler} to 409) and the
     * {@code chk_balance CHECK (balance >= 0)} constraint are the final guards for that window —
     * accepted rather than closed here, the same way {@code chargeForBooking} already accepts it.
     *
     * @return true if an idempotent retry would no-op (already charged), or if the buyer's
     *         current balance covers the booking's cost; false if the allocation cannot be
     *         billed at all (no buyer/price) or the balance is insufficient
     */
    @Transactional(readOnly = true)
    public boolean canAffordBooking(Allocation allocation) {
        String key = "booking:" + allocation.getId();
        if (usageLedgerRepository.findByIdempotencyKey(key).isPresent()) {
            return true;
        }

        UUID buyerId = allocation.getBuyerId();
        BigDecimal price = allocation.getExecutionPrice();
        if (buyerId == null || price == null) {
            return false;
        }

        long windowSeconds = Duration.between(
                allocation.getWindow().getStart(), allocation.getWindow().getEnd()).getSeconds();
        BigDecimal cost = costOf(windowSeconds, allocation.getQuantity(), price);

        return tokenBalanceRepository.findById(buyerId)
                .map(TokenBalance::getBalance)
                .map(balance -> balance.compareTo(cost) >= 0)
                .orElse(false);
    }

    /**
     * Returns the tier's share of what was originally charged for a booking.
     *
     * <p>Deliberately refunds a fraction of the <em>recorded booking charge</em> rather than
     * recomputing the cost: if pricing logic ever changes, a refund must still return money
     * against what the buyer actually paid.
     *
     * @return the amount credited, zero when the tier refunds nothing or there was no charge
     */
    @Transactional
    public BigDecimal refundBooking(Allocation allocation, RefundTier tier) {
        if (tier.rate().signum() == 0) {
            return BigDecimal.ZERO;
        }

        String refundKey = "refund:" + allocation.getId();
        if (usageLedgerRepository.findByIdempotencyKey(refundKey).isPresent()) {
            return BigDecimal.ZERO;
        }

        BigDecimal charged = usageLedgerRepository
                .findByIdempotencyKey("booking:" + allocation.getId())
                .map(UsageLedger::getTokenCost)
                .orElse(BigDecimal.ZERO);
        if (charged.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal refund = charged.multiply(tier.rate()).setScale(6, RoundingMode.HALF_UP);
        if (refund.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        UUID buyerId = allocation.getBuyerId();
        TokenBalance balance = tokenBalanceRepository.findById(buyerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No balance found for buyer: " + buyerId));
        balance.topUp(refund);
        tokenBalanceRepository.save(balance);

        usageLedgerRepository.save(UsageLedger.builder()
                .allocationId(allocation.getId())
                .buyerId(buyerId)
                // A refund covers no compute time, so it records zero seconds.
                .usageSeconds(0)
                .tokenCost(refund.negate())
                .chargeType(ChargeType.REFUND)
                .idempotencyKey(refundKey)
                .build());

        events.publishToUser(buyerId, RealtimeEventType.BALANCE_UPDATED,
                "Refunded " + refund + " tokens — balance is now " + balance.getBalance(),
                buyerId.toString(), BalanceResponse.from(balance));

        return refund;
    }

    /** cost = (seconds / 3600) × gpus × pricePerGpuHour, at the ledger's 6-dp scale. */
    private BigDecimal costOf(long seconds, int quantity, BigDecimal pricePerGpuHour) {
        return BigDecimal.valueOf(seconds)
                .divide(SECONDS_PER_HOUR, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(quantity))
                .multiply(pricePerGpuHour)
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** This user's billing history, newest first. */
    @Transactional(readOnly = true)
    public List<UsageLedgerResponse> findMyLedgerEntries(UUID buyerId) {
        return usageLedgerRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId)
                .stream()
                .map(UsageLedgerResponse::from)
                .toList();
    }

    /** Every ledger row across all users. Operator-facing only. */
    @Transactional(readOnly = true)
    public List<UsageLedgerResponse> findAllLedgerEntries() {
        return usageLedgerRepository.findAll(Sort.by("createdAt"))
                .stream()
                .map(UsageLedgerResponse::from)
                .toList();
    }
}
