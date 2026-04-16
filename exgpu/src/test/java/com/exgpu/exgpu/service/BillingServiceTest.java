package com.exgpu.exgpu.service;

import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.TokenBalance;
import com.exgpu.exgpu.domain.UsageLedger;
import com.exgpu.exgpu.domain.enums.ChargeType;
import com.exgpu.exgpu.dto.BalanceResponse;
import com.exgpu.exgpu.dto.CreateBalanceRequest;
import com.exgpu.exgpu.dto.SubmitUsageEventRequest;
import com.exgpu.exgpu.dto.UsageEventResponse;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.TokenBalanceRepository;
import com.exgpu.exgpu.repository.UsageLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceTest {

    private static final Instant T0  = Instant.parse("2026-06-01T09:00:00Z");
    private static final Instant T2H = Instant.parse("2026-06-01T11:00:00Z"); // window = 7200s

    private TokenBalanceRepository tokenBalanceRepository;
    private UsageLedgerRepository usageLedgerRepository;
    private AllocationRepository allocationRepository;
    private AccessLeaseService accessLeaseService;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        tokenBalanceRepository = mock(TokenBalanceRepository.class);
        usageLedgerRepository  = mock(UsageLedgerRepository.class);
        allocationRepository   = mock(AllocationRepository.class);
        accessLeaseService = mock(AccessLeaseService.class);
        billingService = new BillingService(tokenBalanceRepository, usageLedgerRepository, allocationRepository,
                new ExgpuMetrics(new SimpleMeterRegistry()), mock(RealtimeEventPublisher.class),
                accessLeaseService);
    }

    /**
     * Allocation now carries the payer and the agreed execution price — billing reads them
     * from here rather than from the event, so the tests set them on the allocation.
     */
    private Allocation allocation(UUID id, int quantity, UUID buyerId, BigDecimal executionPrice) {
        return Allocation.builder()
                .id(id)
                .buyOrderId(UUID.randomUUID())
                .sellOrderId(UUID.randomUUID())
                .buyerId(buyerId)
                .quantity(quantity)
                .executionPrice(executionPrice)
                .window(new TimeWindow(T0, T2H))
                .build();
    }

    // ── createOrTopUp ────────────────────────────────────────────────────────

    @Test
    void createOrTopUp_noExistingBalance_createsNew() {
        UUID ownerId = UUID.randomUUID();
        when(tokenBalanceRepository.findById(ownerId)).thenReturn(Optional.empty());

        TokenBalance saved = TokenBalance.builder()
                .buyerId(ownerId).balance(BigDecimal.valueOf(100)).build();
        when(tokenBalanceRepository.save(any())).thenReturn(saved);

        BalanceResponse response = billingService.createOrTopUp(
                new CreateBalanceRequest(BigDecimal.valueOf(100)), ownerId);

        assertThat(response.ownerId()).isEqualTo(ownerId);
        assertThat(response.balance()).isEqualByComparingTo("100");
    }

    @Test
    void createOrTopUp_existingBalance_topsUp() {
        UUID ownerId = UUID.randomUUID();
        TokenBalance existing = TokenBalance.builder()
                .buyerId(ownerId).balance(BigDecimal.valueOf(50)).build();
        when(tokenBalanceRepository.findById(ownerId)).thenReturn(Optional.of(existing));

        TokenBalance saved = TokenBalance.builder()
                .buyerId(ownerId).balance(BigDecimal.valueOf(150)).build();
        when(tokenBalanceRepository.save(any())).thenReturn(saved);

        BalanceResponse response = billingService.createOrTopUp(
                new CreateBalanceRequest(BigDecimal.valueOf(100)), ownerId);

        assertThat(response.balance()).isEqualByComparingTo("150");
    }

    /**
     * The account credited is the caller passed in by the controller, which comes from the
     * verified token. The request body has no owner field at all any more, so there is no
     * longer a way to top up someone else's balance.
     */
    @Test
    void createOrTopUp_creditsTheSuppliedOwner_notAnythingFromTheRequest() {
        UUID caller = UUID.randomUUID();
        when(tokenBalanceRepository.findById(caller)).thenReturn(Optional.empty());
        when(tokenBalanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BalanceResponse response = billingService.createOrTopUp(
                new CreateBalanceRequest(BigDecimal.TEN), caller);

        assertThat(response.ownerId()).isEqualTo(caller);
        assertThat(response.balance()).isEqualByComparingTo("10");
    }

    // ── findBalanceOrZero ────────────────────────────────────────────────────

    /** A user who has never topped up reads as zero, not as a 404. */
    @Test
    void findBalanceOrZero_noRow_returnsZeroBalance() {
        UUID newUser = UUID.randomUUID();
        when(tokenBalanceRepository.findById(newUser)).thenReturn(Optional.empty());

        BalanceResponse response = billingService.findBalanceOrZero(newUser);

        assertThat(response.ownerId()).isEqualTo(newUser);
        assertThat(response.balance()).isEqualByComparingTo("0");
        assertThat(response.version()).isEqualTo(-1L);
    }

    // ── submitUsageEvent — cost formula (price comes from the allocation) ─────

    @Test
    void submitUsageEvent_oneHour_qty1_costEqualsPrice() {
        // (3600 / 3600) * 1 * $2 = $2
        assertCost("evt-q1", 1, 3600, BigDecimal.valueOf(2), "2.000000");
    }

    @Test
    void submitUsageEvent_oneHour_qty5_costIsQuantityTimesPrice() {
        // (3600 / 3600) * 5 * $2 = $10
        assertCost("evt-q5-1h", 5, 3600, BigDecimal.valueOf(2), "10.000000");
    }

    @Test
    void submitUsageEvent_twoHours_qty5_costDoubles() {
        // (7200 / 3600) * 5 * $2 = $20
        assertCost("evt-q5-2h", 5, 7200, BigDecimal.valueOf(2), "20.000000");
    }

    private void assertCost(String eventId, int quantity, long usageSeconds,
                             BigDecimal executionPrice, String expectedCost) {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, quantity, buyerId, executionPrice)));

        TokenBalance balance = TokenBalance.builder()
                .buyerId(buyerId).balance(BigDecimal.valueOf(100)).build();
        when(tokenBalanceRepository.findById(buyerId)).thenReturn(Optional.of(balance));
        when(tokenBalanceRepository.save(any())).thenReturn(balance);

        UsageLedger saved = UsageLedger.builder()
                .id(UUID.randomUUID())
                .allocationId(allocationId).buyerId(buyerId)
                .usageSeconds(usageSeconds).tokenCost(new BigDecimal(expectedCost))
                .idempotencyKey(eventId).build();
        when(usageLedgerRepository.save(any())).thenReturn(saved);

        UsageEventResponse response = billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, usageSeconds));

        assertThat(response.cost()).isEqualByComparingTo(expectedCost);
        assertThat(response.duplicate()).isFalse();
    }

    // ── submitUsageEvent — billing identity/price come from the allocation ────

    @Test
    void submitUsageEvent_billsAllocationBuyerAtAllocationPrice_ignoringAnyEventClaims() {
        UUID allocationId = UUID.randomUUID();
        UUID allocationBuyer = UUID.randomUUID();
        String eventId = "evt-derive";

        // allocation says: buyer = allocationBuyer, price = $3
        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 2, allocationBuyer, BigDecimal.valueOf(3))));

        TokenBalance balance = TokenBalance.builder()
                .buyerId(allocationBuyer).balance(BigDecimal.valueOf(100)).build();
        when(tokenBalanceRepository.findById(allocationBuyer)).thenReturn(Optional.of(balance));
        when(tokenBalanceRepository.save(any())).thenReturn(balance);

        UsageLedger saved = UsageLedger.builder()
                .id(UUID.randomUUID())
                .allocationId(allocationId).buyerId(allocationBuyer)
                .usageSeconds(3600).tokenCost(new BigDecimal("6.000000"))
                .idempotencyKey(eventId).build();
        when(usageLedgerRepository.save(any())).thenReturn(saved);

        // (3600/3600) * 2 * $3 = $6, charged to the allocation's buyer
        UsageEventResponse response = billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 3600));

        assertThat(response.buyerId()).isEqualTo(allocationBuyer);
        assertThat(response.cost()).isEqualByComparingTo("6.000000");
    }

    @Test
    void submitUsageEvent_allocationMissingBillingData_throws422() {
        UUID allocationId = UUID.randomUUID();
        String eventId = "evt-incomplete";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        // legacy-style allocation with no buyer / execution price
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 1, null, null)));

        assertThatThrownBy(() -> billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 3600)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be billed");
    }

    // ── submitUsageEvent — duration validation ────────────────────────────────

    @Test
    void submitUsageEvent_usageBelowWindowBoundary_isAccepted() {
        // window = 7200s; request uses 3600s — accepted
        assertCost("evt-below", 1, 3600, BigDecimal.valueOf(2), "2.000000");
    }

    @Test
    void submitUsageEvent_usageExactlyAtWindowBoundary_isAccepted() {
        // window = 7200s; request uses exactly 7200s — accepted
        assertCost("evt-exact", 1, 7200, BigDecimal.valueOf(2), "4.000000");
    }

    @Test
    void submitUsageEvent_usageExceedsAllocationWindow_throws400() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-over";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        // window is 7200s; request submits 8400s
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 1, buyerId, BigDecimal.ONE)));

        assertThatThrownBy(() -> billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 8400)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds allocation window");
    }

    @Test
    void submitUsageEvent_cumulativeUsageExceedsWindow_throws400() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-cumulative";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 1, buyerId, BigDecimal.ONE)));
        // 5400s already billed; this 3600s event would push the total to 9000s > 7200s
        when(usageLedgerRepository.sumUsageSecondsByAllocationId(allocationId)).thenReturn(5400L);

        assertThatThrownBy(() -> billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 3600)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds allocation window");
    }

    // ── submitUsageEvent — idempotency ────────────────────────────────────────

    @Test
    void submitUsageEvent_duplicateEventId_returnsExistingWithoutRededuction() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-dup";

        UsageLedger existing = UsageLedger.builder()
                .allocationId(allocationId).buyerId(buyerId)
                .usageSeconds(1800).tokenCost(BigDecimal.valueOf(5))
                .idempotencyKey(eventId).build();
        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.of(existing));

        TokenBalance balance = TokenBalance.builder()
                .buyerId(buyerId).balance(BigDecimal.valueOf(95)).build();
        when(tokenBalanceRepository.findById(buyerId)).thenReturn(Optional.of(balance));

        UsageEventResponse response = billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 1800));

        assertThat(response.duplicate()).isTrue();
        assertThat(response.remainingBalance()).isEqualByComparingTo("95");
        verify(tokenBalanceRepository, never()).save(any());
        verify(usageLedgerRepository, never()).save(any());
    }

    // ── submitUsageEvent — error paths ────────────────────────────────────────

    /**
     * Usage events no longer move money. Billing happens once at booking for the whole
     * window, so a metered event that would once have cost $5 now deducts nothing — charging
     * here as well would bill the buyer twice for the same hours.
     */
    @Test
    void submitUsageEvent_doesNotDeductFromBalance() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-metering";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 5, buyerId, BigDecimal.ONE)));

        TokenBalance balance = TokenBalance.builder()
                .buyerId(buyerId).balance(BigDecimal.valueOf(0.5)).build();
        when(tokenBalanceRepository.findById(buyerId)).thenReturn(Optional.of(balance));
        when(usageLedgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingService.submitUsageEvent(new SubmitUsageEventRequest(eventId, allocationId, 3600));

        // Balance untouched even though the metered usage would have cost $5.
        assertThat(balance.getBalance()).isEqualByComparingTo("0.5");
        verify(tokenBalanceRepository, never()).save(any());
    }

    /** The metering row is written with a zero cost and tagged USAGE, not BOOKING. */
    @Test
    void submitUsageEvent_recordsZeroCostUsageRow() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-zero-cost";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 3, buyerId, BigDecimal.valueOf(2))));
        when(tokenBalanceRepository.findById(buyerId)).thenReturn(Optional.of(
                TokenBalance.builder().buyerId(buyerId).balance(BigDecimal.valueOf(100)).build()));

        ArgumentCaptor<UsageLedger> captor = ArgumentCaptor.forClass(UsageLedger.class);
        when(usageLedgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingService.submitUsageEvent(new SubmitUsageEventRequest(eventId, allocationId, 1800));

        verify(usageLedgerRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenCost()).isEqualByComparingTo("0");
        assertThat(captor.getValue().getChargeType()).isEqualTo(ChargeType.USAGE);
        assertThat(captor.getValue().getUsageSeconds()).isEqualTo(1800);
    }

    @Test
    void submitUsageEvent_unknownAllocation_throws404() {
        UUID allocationId = UUID.randomUUID();
        String eventId    = "evt-noalloc";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 3600)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Allocation not found");
    }

    @Test
    void submitUsageEvent_unknownBuyer_throws404() {
        UUID allocationId = UUID.randomUUID();
        UUID buyerId      = UUID.randomUUID();
        String eventId    = "evt-nobuyer";

        when(usageLedgerRepository.findByIdempotencyKey(eventId)).thenReturn(Optional.empty());
        when(allocationRepository.findById(allocationId))
                .thenReturn(Optional.of(allocation(allocationId, 1, buyerId, BigDecimal.ONE)));
        when(tokenBalanceRepository.findById(buyerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billingService.submitUsageEvent(
                new SubmitUsageEventRequest(eventId, allocationId, 3600)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No balance found for buyer");
    }
}
