package com.exgpu.exgpu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.exgpu.exgpu.domain.AccessLease;
import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.TokenBalance;
import com.exgpu.exgpu.domain.enums.LeaseStatus;
import com.exgpu.exgpu.domain.enums.RevokeReason;
import com.exgpu.exgpu.dto.AccessResponse;
import com.exgpu.exgpu.repository.AccessLeaseRepository;
import com.exgpu.exgpu.repository.TokenBalanceRepository;

/**
 * The scenario in the spec: a buyer rents 3pm–5pm.
 *
 * <ul>
 *   <li>at 2pm  → told to check back at 3pm, no key</li>
 *   <li>at 3pm  → key issued</li>
 *   <li>at 5pm  → key gone</li>
 * </ul>
 */
class AccessLeaseServiceTest {

    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID ALLOCATION = UUID.randomUUID();

    // A 3pm-5pm rental, expressed in UTC so the test does not depend on the host timezone.
    private static final Instant THREE_PM = Instant.parse("2026-06-01T15:00:00Z");
    private static final Instant FIVE_PM = Instant.parse("2026-06-01T17:00:00Z");

    private AccessLeaseRepository leaseRepository;
    private TokenBalanceRepository balanceRepository;
    private AccessLeaseService service;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(AccessLeaseRepository.class);
        balanceRepository = mock(TokenBalanceRepository.class);
        service = new AccessLeaseService(leaseRepository, balanceRepository,
                new AccessCredentialMinter("test-signing-secret"));

        // Funded by default; the empty-balance case overrides this.
        when(balanceRepository.findById(BUYER)).thenReturn(Optional.of(
                TokenBalance.builder().buyerId(BUYER).balance(BigDecimal.valueOf(500)).build()));
    }

    private AccessLease lease(LeaseStatus status) {
        return AccessLease.builder()
                .id(UUID.randomUUID())
                .allocationId(ALLOCATION)
                .buyerId(BUYER)
                .status(status)
                .windowStart(THREE_PM)
                .windowEnd(FIVE_PM)
                .nodeRef("gpu-node-abc123")
                .build();
    }

    private void givenLease(AccessLease l) {
        when(leaseRepository.findByAllocationId(ALLOCATION)).thenReturn(Optional.of(l));
    }

    // ── the three moments in the spec ─────────────────────────────────────────
    //
    // describeAccess reads Instant.now() internally, so these drive the clock by choosing
    // window boundaries relative to real now rather than by freezing time.

    @Test
    void beforeWindow_reportsPendingWithCountdownAndNoKey() {
        Instant start = Instant.now().plus(Duration.ofHours(1));   // "3pm", an hour away
        Instant end = start.plus(Duration.ofHours(2));             // "5pm"
        givenLease(AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                .status(LeaseStatus.PENDING).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build());

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("PENDING");
        assertThat(res.accessKey()).isNull();
        assertThat(res.connection()).isNull();
        assertThat(res.secondsUntilAvailable()).isBetween(3500L, 3600L);
        assertThat(res.message()).contains("Check back");
        assertThat(res.windowStart()).isEqualTo(start);
    }

    @Test
    void insideWindow_issuesKeyAndConnectionDetails() {
        Instant start = Instant.now().minus(Duration.ofMinutes(30));
        Instant end = Instant.now().plus(Duration.ofMinutes(90));
        givenLease(AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                .status(LeaseStatus.ACTIVE).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build());

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("ACTIVE");
        assertThat(res.accessKey()).isNotNull().startsWith("exgpu_v1.");
        assertThat(res.connection()).isNotNull();
        assertThat(res.connection().host()).contains("gpu-node-abc123");
        assertThat(res.secondsRemaining()).isBetween(5300L, 5400L);
        // The credential may never outlive the rental.
        assertThat(res.keyExpiresAt()).isBeforeOrEqualTo(end);
    }

    @Test
    void afterWindow_reportsExpiredAndWithholdsKey() {
        Instant start = Instant.now().minus(Duration.ofHours(3));
        Instant end = Instant.now().minus(Duration.ofHours(1));
        givenLease(AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                // Still says ACTIVE — the scheduler has not ticked yet. The read path must
                // not be fooled by that.
                .status(LeaseStatus.ACTIVE).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build());

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("EXPIRED");
        assertThat(res.accessKey()).isNull();
        assertThat(res.connection()).isNull();
        assertThat(res.message()).contains("no longer issued");
    }

    /**
     * The window opened since the last scheduler tick. Access must be granted immediately
     * rather than waiting up to a tick, otherwise the buyer sees "check back at 3pm" at 3pm.
     */
    @Test
    void windowJustOpenedButRowStillPending_grantsAccessAnyway() {
        Instant start = Instant.now().minus(Duration.ofSeconds(5));
        Instant end = Instant.now().plus(Duration.ofHours(2));
        AccessLease stale = AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                .status(LeaseStatus.PENDING).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build();
        givenLease(stale);

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("ACTIVE");
        assertThat(res.accessKey()).isNotNull();
        // And the row is corrected in passing.
        assertThat(stale.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(stale.getActivatedAt()).isNotNull();
    }

    // ── polling is idempotent ─────────────────────────────────────────────────

    @Test
    void repeatedPolls_returnTheSameKey() {
        Instant start = Instant.now().minus(Duration.ofMinutes(10));
        Instant end = Instant.now().plus(Duration.ofHours(4));
        givenLease(AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                .status(LeaseStatus.ACTIVE).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build());

        AccessResponse first = service.describeAccess(ALLOCATION, BUYER);
        AccessResponse second = service.describeAccess(ALLOCATION, BUYER);
        AccessResponse third = service.describeAccess(ALLOCATION, BUYER);

        assertThat(second.accessKey()).isEqualTo(first.accessKey());
        assertThat(third.accessKey()).isEqualTo(first.accessKey());
        assertThat(second.keyExpiresAt()).isEqualTo(first.keyExpiresAt());
    }

    // ── revocation ────────────────────────────────────────────────────────────

    @Test
    void emptyBalanceDuringWindow_suspendsAccess() {
        when(balanceRepository.findById(BUYER)).thenReturn(Optional.of(
                TokenBalance.builder().buyerId(BUYER).balance(BigDecimal.ZERO).build()));

        Instant start = Instant.now().minus(Duration.ofMinutes(10));
        Instant end = Instant.now().plus(Duration.ofHours(2));
        givenLease(AccessLease.builder()
                .id(UUID.randomUUID()).allocationId(ALLOCATION).buyerId(BUYER)
                .status(LeaseStatus.ACTIVE).windowStart(start).windowEnd(end)
                .nodeRef("gpu-node-abc123").build());

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("REVOKED");
        assertThat(res.accessKey()).isNull();
        assertThat(res.revokeReason()).isEqualTo(RevokeReason.BALANCE_EXHAUSTED);
    }

    @Test
    void revokedLease_staysRevokedEvenInsideWindow() {
        AccessLease revoked = lease(LeaseStatus.REVOKED);
        revoked.setWindowStart(Instant.now().minus(Duration.ofMinutes(5)));
        revoked.setWindowEnd(Instant.now().plus(Duration.ofHours(2)));
        revoked.setRevokeReason(RevokeReason.BALANCE_EXHAUSTED);
        givenLease(revoked);

        AccessResponse res = service.describeAccess(ALLOCATION, BUYER);

        assertThat(res.state()).isEqualTo("REVOKED");
        assertThat(res.accessKey()).isNull();
    }

    // ── authorization ─────────────────────────────────────────────────────────

    @Test
    void anotherUsersRental_is404NotForbidden() {
        givenLease(lease(LeaseStatus.ACTIVE));

        assertThatThrownBy(() -> service.describeAccess(ALLOCATION, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void unknownAllocation_is404() {
        when(leaseRepository.findByAllocationId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.describeAccess(UUID.randomUUID(), BUYER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── lease creation is idempotent ──────────────────────────────────────────

    @Test
    void createForAllocation_whenLeaseExists_doesNotCreateSecond() {
        AccessLease existing = lease(LeaseStatus.PENDING);
        givenLease(existing);

        Allocation allocation = Allocation.builder()
                .id(ALLOCATION).buyerId(BUYER)
                .window(new TimeWindow(THREE_PM, FIVE_PM))
                .buyOrderId(UUID.randomUUID()).sellOrderId(UUID.randomUUID())
                .quantity(2).build();

        AccessLease result = service.createForAllocation(allocation);

        assertThat(result).isSameAs(existing);
        verify(leaseRepository, never()).save(any());
    }

    @Test
    void createForAllocation_whenAbsent_createsPendingLease() {
        when(leaseRepository.findByAllocationId(ALLOCATION)).thenReturn(Optional.empty());
        when(leaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Allocation allocation = Allocation.builder()
                .id(ALLOCATION).buyerId(BUYER)
                .window(new TimeWindow(THREE_PM, FIVE_PM))
                .buyOrderId(UUID.randomUUID()).sellOrderId(UUID.randomUUID())
                .quantity(2).build();

        AccessLease created = service.createForAllocation(allocation);

        assertThat(created.getStatus()).isEqualTo(LeaseStatus.PENDING);
        assertThat(created.getBuyerId()).isEqualTo(BUYER);
        assertThat(created.getWindowStart()).isEqualTo(THREE_PM);
        assertThat(created.getWindowEnd()).isEqualTo(FIVE_PM);
        assertThat(created.getNodeRef()).startsWith("gpu-node-");
    }
}
