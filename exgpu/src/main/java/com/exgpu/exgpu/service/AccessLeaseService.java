package com.exgpu.exgpu.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.exgpu.exgpu.domain.AccessLease;
import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.TokenBalance;
import com.exgpu.exgpu.domain.enums.LeaseStatus;
import com.exgpu.exgpu.domain.enums.RevokeReason;
import com.exgpu.exgpu.dto.AccessResponse;
import com.exgpu.exgpu.repository.AccessLeaseRepository;
import com.exgpu.exgpu.repository.TokenBalanceRepository;

/**
 * Owns the buyer-facing access lifecycle: when a rental opens, what credential it yields,
 * and when it closes.
 *
 * <p>The read path ({@link #describeAccess}) is deliberately side-effect-free apart from an
 * audit fingerprint, because clients poll it on a timer. See
 * {@link AccessCredentialMinter} for why repeated polls return the same credential.
 */
@Service
public class AccessLeaseService {

    private static final Logger log = LoggerFactory.getLogger(AccessLeaseService.class);

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a 'on' MMM d").withZone(ZoneId.systemDefault());

    private final AccessLeaseRepository leaseRepository;
    private final TokenBalanceRepository balanceRepository;
    private final AccessCredentialMinter minter;

    public AccessLeaseService(AccessLeaseRepository leaseRepository,
                              TokenBalanceRepository balanceRepository,
                              AccessCredentialMinter minter) {
        this.leaseRepository = leaseRepository;
        this.balanceRepository = balanceRepository;
        this.minter = minter;
    }

    /**
     * Creates the lease for a freshly matched allocation.
     *
     * <p>Idempotent: a lease already existing for the allocation is returned untouched. The
     * unique constraint on {@code allocation_id} is the real guard, so a concurrent duplicate
     * fails at the database rather than producing two leases.
     */
    @Transactional
    public AccessLease createForAllocation(Allocation allocation) {
        return leaseRepository.findByAllocationId(allocation.getId())
                .orElseGet(() -> leaseRepository.save(AccessLease.builder()
                        .allocationId(allocation.getId())
                        .buyerId(allocation.getBuyerId())
                        .windowStart(allocation.getWindow().getStart())
                        .windowEnd(allocation.getWindow().getEnd())
                        .nodeRef(nodeRefFor(allocation.getId()))
                        .status(LeaseStatus.PENDING)
                        .build()));
    }

    /**
     * Answers "can I get in right now?" for one allocation.
     *
     * <p>State is decided from the clock, not solely from the stored status. The scheduler
     * updates rows on a tick; between ticks a row can lag reality. Reading the window here
     * means a buyer never sees a credential one second past their window end, and never sees
     * "not yet" one second after it should have opened.
     *
     * @param buyerId the authenticated caller — must be the lease's buyer
     */
    @Transactional
    public AccessResponse describeAccess(UUID allocationId, UUID buyerId) {
        AccessLease lease = leaseRepository.findByAllocationId(allocationId)
                // Same 404 for "no such allocation" and "not yours": the endpoint must not
                // confirm that an id exists to someone who has no claim on it.
                .filter(l -> buyerId.equals(l.getBuyerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No rental found: " + allocationId));

        Instant now = Instant.now();

        if (lease.getStatus() == LeaseStatus.REVOKED) {
            return revoked(lease);
        }
        if (lease.windowHasEnded(now)) {
            return expired(lease);
        }
        if (lease.windowNotStarted(now)) {
            return pending(lease, now);
        }

        // Inside the window. Access still depends on the buyer being able to pay: an empty
        // balance means compute would be killed the moment it started billing, so handing
        // out a credential would be misleading.
        BigDecimal balance = balanceRepository.findById(buyerId)
                .map(TokenBalance::getBalance)
                .orElse(BigDecimal.ZERO);
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return new AccessResponse(
                    "REVOKED", lease.getAllocationId(), lease.getNodeRef(),
                    lease.getWindowStart(), lease.getWindowEnd(),
                    null, null, null, null, null,
                    RevokeReason.BALANCE_EXHAUSTED,
                    "Access is suspended because your token balance is empty. Add tokens to resume.");
        }

        // The row may still say PENDING if the window opened since the last scheduler tick.
        // Reflect reality immediately rather than making the buyer wait for the tick.
        if (lease.getStatus() == LeaseStatus.PENDING) {
            lease.setStatus(LeaseStatus.ACTIVE);
            if (lease.getActivatedAt() == null) lease.setActivatedAt(now);
        }

        AccessCredentialMinter.Credential credential = minter.mint(lease, now);

        // Audit trail only. Writing the fingerprint (not the token) keeps the read path
        // idempotent in the way that matters: polling never mints a *different* credential,
        // and this value is stable for the whole bucket.
        lease.setLastCredentialFingerprint(credential.fingerprint());
        lease.setLastIssuedAt(now);

        long secondsRemaining = Duration.between(now, lease.getWindowEnd()).getSeconds();

        return new AccessResponse(
                "ACTIVE",
                lease.getAllocationId(),
                lease.getNodeRef(),
                lease.getWindowStart(),
                lease.getWindowEnd(),
                null,
                secondsRemaining,
                credential.token(),
                credential.expiresAt(),
                new AccessResponse.ConnectionDetails(
                        lease.getNodeRef() + ".nodes.exgpu.local",
                        22,
                        "buyer-" + shortHex(lease.getBuyerId()),
                        "Simulated node — the credential is real and verifiable, the host is not."),
                null,
                "Access is open until " + CLOCK.format(lease.getWindowEnd()) + ".");
    }

    /** Access state for several allocations at once, so a rentals list needs one round trip. */
    @Transactional(readOnly = true)
    public List<AccessLease> findLeasesForBuyer(UUID buyerId) {
        return leaseRepository.findByBuyerIdOrderByWindowStartDesc(buyerId);
    }

    @Transactional(readOnly = true)
    public Optional<AccessLease> findByAllocation(UUID allocationId) {
        return leaseRepository.findByAllocationId(allocationId);
    }

    /**
     * Withdraws all live access for a buyer. Called on the KillCompute path.
     *
     * @return how many leases this call actually revoked; zero if access was already gone
     */
    @Transactional
    public int revokeAllForBuyer(UUID buyerId, RevokeReason reason) {
        int revoked = leaseRepository.revokeAllForBuyer(buyerId, reason, Instant.now());
        if (revoked > 0) {
            log.info("Revoked {} lease(s) for buyer {} reason={}", revoked, buyerId, reason);
        }
        return revoked;
    }

    /** Revokes access for one allocation. Idempotent; returns 0 if it was already terminal. */
    @Transactional
    public int revokeForAllocation(UUID allocationId, RevokeReason reason) {
        int revoked = leaseRepository.revokeForAllocation(allocationId, reason, Instant.now());
        if (revoked > 0) {
            log.info("Revoked lease for allocation {} reason={}", allocationId, reason);
        }
        return revoked;
    }

    // ── response builders ─────────────────────────────────────────────────────

    private AccessResponse pending(AccessLease lease, Instant now) {
        long until = Duration.between(now, lease.getWindowStart()).getSeconds();
        return new AccessResponse(
                "PENDING", lease.getAllocationId(), lease.getNodeRef(),
                lease.getWindowStart(), lease.getWindowEnd(),
                until, null, null, null, null, null,
                "Your window opens at " + CLOCK.format(lease.getWindowStart())
                        + ". Check back then for your access key.");
    }

    private AccessResponse expired(AccessLease lease) {
        return new AccessResponse(
                "EXPIRED", lease.getAllocationId(), lease.getNodeRef(),
                lease.getWindowStart(), lease.getWindowEnd(),
                null, null, null, null, null, null,
                "This rental ended at " + CLOCK.format(lease.getWindowEnd())
                        + ". Access keys are no longer issued.");
    }

    private AccessResponse revoked(AccessLease lease) {
        String why = lease.getRevokeReason() == RevokeReason.BALANCE_EXHAUSTED
                ? "your token balance reached zero"
                : "an operator withdrew access";
        return new AccessResponse(
                "REVOKED", lease.getAllocationId(), lease.getNodeRef(),
                lease.getWindowStart(), lease.getWindowEnd(),
                null, null, null, null, null, lease.getRevokeReason(),
                "Access was revoked because " + why + ".");
    }

    /** Stable, non-identifying node label derived from the allocation id. */
    private static String nodeRefFor(UUID allocationId) {
        return "gpu-node-" + shortHex(allocationId);
    }

    private static String shortHex(UUID id) {
        return id.toString().replace("-", "").substring(0, 6);
    }
}
