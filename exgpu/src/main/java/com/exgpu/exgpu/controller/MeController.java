package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.dto.AccessResponse;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.dto.BalanceResponse;
import com.exgpu.exgpu.dto.CancellationQuote;
import com.exgpu.exgpu.dto.UsageLedgerResponse;
import com.exgpu.exgpu.service.AccessLeaseService;
import com.exgpu.exgpu.service.AllocationService;
import com.exgpu.exgpu.service.BillingService;
import com.exgpu.exgpu.service.CancellationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything belonging to the signed-in user, in one place.
 *
 * <p>None of these endpoints takes an id: the subject is always the caller, resolved from
 * the verified token. That is what makes them safe to expose — there is no parameter for an
 * attacker to substitute.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final AllocationService allocationService;
    private final BillingService billingService;
    private final AccessLeaseService accessLeaseService;
    private final CancellationService cancellationService;

    public MeController(AllocationService allocationService,
                        BillingService billingService,
                        AccessLeaseService accessLeaseService,
                        CancellationService cancellationService) {
        this.allocationService = allocationService;
        this.billingService = billingService;
        this.accessLeaseService = accessLeaseService;
        this.cancellationService = cancellationService;
    }

    /** Identity as the API sees it. Lets the frontend confirm the token is actually accepted. */
    @GetMapping
    public Map<String, Object> whoAmI() {
        UUID id = CurrentUser.id();
        String email = CurrentUser.email();
        return Map.of(
                "userId", id.toString(),
                "email", email == null ? "" : email);
    }

    /** GPU capacity the user is renting (they are the buyer). */
    @GetMapping("/rentals")
    public List<AllocationResponse> myRentals() {
        return allocationService.findMyRentals(CurrentUser.id());
    }

    /** GPU capacity the user is providing (the allocation came from their SELL order). */
    @GetMapping("/supply")
    public List<AllocationResponse> mySupply() {
        return allocationService.findMySupply(CurrentUser.id());
    }

    /** Prepaid token balance. Returns zero rather than 404 before the first top-up. */
    @GetMapping("/balance")
    public BalanceResponse myBalance() {
        return billingService.findBalanceOrZero(CurrentUser.id());
    }

    /** Billing history: what was charged, for which allocation, and when. */
    @GetMapping("/usage")
    public List<UsageLedgerResponse> myUsage() {
        return billingService.findMyLedgerEntries(CurrentUser.id());
    }

    /**
     * Whether the caller can get into a rental right now, and the credential if so.
     *
     * <p>Designed to be polled. The response is derived from the clock and the lease, and a
     * credential minted inside one time bucket is byte-identical across calls — so hitting
     * this on a timer produces no drift, no accumulating state, and no new secret per poll.
     * See {@link com.exgpu.exgpu.service.AccessCredentialMinter}.
     *
     * <p>Returns 404 for an allocation that does not exist <em>and</em> for one belonging to
     * someone else, so it cannot be used to probe for valid ids.
     */
    @GetMapping("/rentals/{allocationId}/access")
    public AccessResponse rentalAccess(@PathVariable UUID allocationId) {
        return accessLeaseService.describeAccess(allocationId, CurrentUser.id());
    }

    /**
     * What cancelling this rental right now would refund. Read-only — nothing is cancelled.
     *
     * <p>Separate from the cancel call so the UI can show the consequence before the buyer
     * commits: seeing how much comes back is a very different decision from a bare "cancel?".
     */
    @GetMapping("/rentals/{allocationId}/cancellation-quote")
    public CancellationQuote cancellationQuote(@PathVariable UUID allocationId) {
        return cancellationService.quote(allocationId, CurrentUser.id());
    }

    /**
     * Cancels a rental, refunding by notice given: full at 8h+, half at 4-8h, none under 4h.
     * Returns the resulting state, so the client sees exactly what was refunded.
     */
    @PostMapping("/rentals/{allocationId}/cancel")
    public CancellationQuote cancelRental(@PathVariable UUID allocationId) {
        return cancellationService.cancel(allocationId, CurrentUser.id());
    }
}
