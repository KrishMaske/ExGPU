package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.dto.SubmitUsageEventRequest;
import com.exgpu.exgpu.dto.UsageEventResponse;
import com.exgpu.exgpu.dto.UsageLedgerResponse;
import com.exgpu.exgpu.service.AllocationService;
import com.exgpu.exgpu.service.BillingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * REST doorway for usage telemetry.
 *
 * <p>Note where the authorization check lives: <em>here</em>, not in {@link BillingService}.
 * The service is shared with the Kafka consumer, which has no authenticated user — it is a
 * trusted internal producer. Putting the check in the service would either break that path
 * or require faking a principal for it. So the REST edge enforces "you must be a party to
 * this allocation", and the Kafka edge is trusted by deployment.
 *
 * <p>Either side of a trade may report usage. In a production deployment only the provider's
 * agent would hold a credential for this; allowing the buyer as well keeps the flow
 * demonstrable end-to-end from the UI without a separate agent.
 */
@RestController
public class UsageEventController {

    private final BillingService billingService;
    private final AllocationService allocationService;

    public UsageEventController(BillingService billingService, AllocationService allocationService) {
        this.billingService = billingService;
        this.allocationService = allocationService;
    }

    @PostMapping("/usage-events")
    @ResponseStatus(HttpStatus.CREATED)
    public UsageEventResponse submitUsageEvent(@Valid @RequestBody SubmitUsageEventRequest request) {
        UUID me = CurrentUser.id();
        if (!allocationService.isPartyTo(request.allocationId(), me)) {
            // 404 rather than 403: a caller who is not on this allocation should not learn
            // whether the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Allocation not found: " + request.allocationId());
        }
        return billingService.submitUsageEvent(request);
    }

    /** The caller's own billing history. Was previously the entire ledger for every user. */
    @GetMapping("/usage-ledger")
    public List<UsageLedgerResponse> getMyUsageLedger() {
        return billingService.findMyLedgerEntries(CurrentUser.id());
    }
}
