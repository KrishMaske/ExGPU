package com.exgpu.exgpu.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.exgpu.exgpu.config.AfterCommit;
import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.enums.AllocationStatus;
import com.exgpu.exgpu.domain.enums.RefundTier;
import com.exgpu.exgpu.domain.enums.RevokeReason;
import com.exgpu.exgpu.dto.CancellationQuote;
import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import com.exgpu.exgpu.repository.UsageLedgerRepository;

/**
 * Cancelling a booked rental.
 *
 * <p>Refunds are tiered by notice given before the window <em>opens</em> — see
 * {@link RefundTier}. Cancelling costs the provider more the later it happens, because hours
 * pulled off the market at short notice are unlikely to be resold.
 *
 * <p>A cancellation does three things, all in one transaction: it refunds the buyer, it
 * revokes their access, and it returns the unused capacity to the provider's listing so the
 * hours can be sold again. Doing only the first two would quietly destroy inventory.
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final AllocationRepository allocationRepository;
    private final OrderRepository orderRepository;
    private final UsageLedgerRepository usageLedgerRepository;
    private final BillingService billingService;
    private final AccessLeaseService accessLeaseService;
    private final RealtimeEventPublisher events;
    private final ExgpuMetrics metrics;
    private final MatchingEngine matchingEngine;

    public CancellationService(AllocationRepository allocationRepository,
                               OrderRepository orderRepository,
                               UsageLedgerRepository usageLedgerRepository,
                               BillingService billingService,
                               AccessLeaseService accessLeaseService,
                               RealtimeEventPublisher events,
                               ExgpuMetrics metrics,
                               MatchingEngine matchingEngine) {
        this.allocationRepository = allocationRepository;
        this.orderRepository = orderRepository;
        this.usageLedgerRepository = usageLedgerRepository;
        this.billingService = billingService;
        this.accessLeaseService = accessLeaseService;
        this.events = events;
        this.metrics = metrics;
        this.matchingEngine = matchingEngine;
    }

    /**
     * What cancelling right now would return, without changing anything.
     *
     * <p>Exists so the UI can show the buyer the consequence <em>before</em> they confirm —
     * "you'll get $12 of $24 back" is a very different decision from "cancel?".
     */
    @Transactional(readOnly = true)
    public CancellationQuote quote(UUID allocationId, UUID buyerId) {
        Allocation allocation = loadOwned(allocationId, buyerId);
        Instant now = Instant.now();
        RefundTier tier = RefundTier.forNotice(now, allocation.getWindow().getStart());

        BigDecimal charged = bookingChargeOf(allocation);
        BigDecimal refund = charged.multiply(tier.rate()).setScale(6, java.math.RoundingMode.HALF_UP);
        long noticeSeconds = Duration.between(now, allocation.getWindow().getStart()).getSeconds();

        return new CancellationQuote(
                allocationId,
                allocation.getStatus() == AllocationStatus.CANCELLED,
                canCancel(allocation, now),
                tier.name(),
                tier.rate(),
                charged,
                refund,
                Math.max(0, noticeSeconds),
                allocation.getWindow().getStart(),
                explain(tier, noticeSeconds));
    }

    /**
     * Cancels a rental and refunds according to the tier in force at this moment.
     *
     * @param buyerId the authenticated caller; must be the allocation's buyer
     */
    @Transactional
    public CancellationQuote cancel(UUID allocationId, UUID buyerId) {
        Allocation allocation = loadOwned(allocationId, buyerId);
        Instant now = Instant.now();

        if (allocation.getStatus() == AllocationStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This rental is already cancelled");
        }
        if (!canCancel(allocation, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This rental's window has already ended and cannot be cancelled");
        }

        RefundTier tier = RefundTier.forNotice(now, allocation.getWindow().getStart());
        BigDecimal refunded = billingService.refundBooking(allocation, tier);

        allocation.setStatus(AllocationStatus.CANCELLED);
        allocation.setCancelledAt(now);
        allocation.setRefundedAmount(refunded);
        allocationRepository.save(allocation);

        // Hand the hours back to the provider so they can be resold. Without this the
        // capacity would be permanently consumed by a rental that no longer exists.
        releaseCapacity(allocation);

        int revoked = accessLeaseService.revokeForAllocation(allocationId, RevokeReason.OPERATOR);
        if (revoked > 0) {
            metrics.incrementLeasesRevoked(revoked);
        }

        UUID sellerId = orderRepository.findById(allocation.getSellOrderId())
                .map(Order::getOwnerId)
                .orElse(null);

        events.publishToUser(buyerId, RealtimeEventType.ACCESS_REVOKED,
                "Rental cancelled — " + refunded + " tokens refunded",
                allocationId.toString(), null);
        if (sellerId != null) {
            events.publishToUser(sellerId, RealtimeEventType.MARKET_UPDATED,
                    "A buyer cancelled — " + allocation.getQuantity()
                            + " GPU(s) returned to your listing",
                    allocationId.toString(), null);
        }
        events.publishMarket(RealtimeEventType.MARKET_UPDATED, "Capacity returned to market", null, null);

        log.info("Cancelled allocation {} tier={} refunded={}", allocationId, tier, refunded);

        return quote(allocationId, buyerId);
    }

    /**
     * Returns the cancelled quantity to the originating SELL order.
     *
     * <p>Reducing {@code filledQuantity} makes the order matchable again, which is what puts
     * the hours back on the public market. The status is recomputed rather than assumed: an
     * order that was FILLED becomes PARTIALLY_FILLED, or OPEN if nothing else was ever sold
     * from it.
     *
     * <p>Also returns the order to {@link MatchingEngine}'s in-memory book (B2) — scheduled
     * with {@link AfterCommit} so the book is only updated once this cancellation has actually
     * committed. Previously this method only updated Postgres; the book was never rebuilt from
     * it, so a restored order became visible on the market listing but did not actually match
     * again until a restart repopulated the book from scratch. That gap is closed here.
     */
    private void releaseCapacity(Allocation allocation) {
        orderRepository.findById(allocation.getSellOrderId()).ifPresent(sellOrder -> {
            int restored = Math.max(0, sellOrder.getFilledQuantity() - allocation.getQuantity());
            sellOrder.setFilledQuantity(restored);
            sellOrder.recomputeStatus();
            orderRepository.save(sellOrder);
            AfterCommit.run(() -> matchingEngine.restore(sellOrder));
        });

        // The buy order is left alone on purpose. The buyer chose to cancel; silently
        // reopening their demand would put them back in the market for compute they just
        // decided they did not want.
    }

    /** Cancellable while the window has not yet ended and it is not already cancelled. */
    private boolean canCancel(Allocation allocation, Instant now) {
        return allocation.getStatus() != AllocationStatus.CANCELLED
                && now.isBefore(allocation.getWindow().getEnd());
    }

    private BigDecimal bookingChargeOf(Allocation allocation) {
        return usageLedgerRepository.findByIdempotencyKey("booking:" + allocation.getId())
                .map(l -> l.getTokenCost())
                .orElse(BigDecimal.ZERO);
    }

    /** Same 404 for "no such rental" and "not yours", so the endpoint cannot probe for ids. */
    private Allocation loadOwned(UUID allocationId, UUID buyerId) {
        return allocationRepository.findById(allocationId)
                .filter(a -> buyerId.equals(a.getBuyerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No rental found: " + allocationId));
    }

    private String explain(RefundTier tier, long noticeSeconds) {
        if (noticeSeconds <= 0) {
            return "This rental has already started — cancelling now refunds nothing.";
        }
        long hours = noticeSeconds / 3600;
        return switch (tier) {
            case FULL -> "Cancelling now is free — you have " + hours
                    + "h notice, above the 8h threshold for a full refund.";
            case PARTIAL -> "You have " + hours + "h notice. Between 4h and 8h, half the"
                    + " booking is refunded; below 4h, nothing is.";
            case NONE -> "Under 4h notice — the provider keeps the booking, so cancelling"
                    + " refunds nothing.";
        };
    }
}
