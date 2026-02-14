package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.config.CurrentUser;
import com.exgpu.exgpu.dto.BalanceResponse;
import com.exgpu.exgpu.dto.CreateBalanceRequest;
import com.exgpu.exgpu.service.BillingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prepaid token balance for the signed-in user.
 *
 * <p>The old {@code GET /balances/{ownerId}} route is gone. It let anyone read anyone's
 * balance by supplying a UUID, and there is no legitimate caller for it now that the owner
 * is always the authenticated user.
 */
@RestController
@RequestMapping("/balances")
public class BalanceController {

    private final BillingService billingService;

    public BalanceController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** Adds tokens to the caller's own balance, creating it on first use. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceResponse createOrTopUp(@Valid @RequestBody CreateBalanceRequest request) {
        return billingService.createOrTopUp(request, CurrentUser.id());
    }

    /** The caller's balance. Same data as {@code GET /me/balance}, kept for API symmetry. */
    @GetMapping("/me")
    public BalanceResponse getMyBalance() {
        return billingService.findBalanceOrZero(CurrentUser.id());
    }
}
