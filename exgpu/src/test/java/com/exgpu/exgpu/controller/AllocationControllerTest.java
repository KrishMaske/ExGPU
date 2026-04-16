package com.exgpu.exgpu.controller;

import com.exgpu.exgpu.domain.enums.AllocationStatus;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.service.AllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import com.exgpu.exgpu.config.SecurityConfig;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AllocationController.class)
// Without this, @WebMvcTest falls back to Boot's DEFAULT security (CSRF on, httpBasic),
// so these assertions would describe a filter chain the app does not actually run.
@Import(SecurityConfig.class)
class AllocationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean  AllocationService allocationService;
    @MockBean  JwtDecoder jwtDecoder;

    private static final Instant START = Instant.parse("2026-06-01T08:00:00Z");
    private static final Instant END   = Instant.parse("2026-06-01T10:00:00Z");
    private static final UUID ME = UUID.randomUUID();

    private RequestPostProcessor asMe() {
        return jwt().jwt(builder -> builder.subject(ME.toString()));
    }

    private AllocationResponse allocation(UUID buyOrderId, UUID sellOrderId, int qty) {
        return new AllocationResponse(
                UUID.randomUUID(), buyOrderId, sellOrderId, qty, START, END,
                BigDecimal.valueOf(1.50), AllocationStatus.ACTIVE,
                Instant.now(), "SCHEDULED", 7200L, BigDecimal.valueOf(30));
    }

    @Test
    void getAllocations_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/allocations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAllocations_noAllocations_returnsEmptyArray() throws Exception {
        when(allocationService.findMyRentals(ME)).thenReturn(List.of());
        when(allocationService.findMySupply(ME)).thenReturn(List.of());

        mockMvc.perform(get("/allocations").with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAllocations_returnsRentalsAndSupplyCombined() throws Exception {
        UUID buyOrderId  = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        when(allocationService.findMyRentals(ME))
                .thenReturn(List.of(allocation(buyOrderId, sellOrderId, 8)));
        when(allocationService.findMySupply(ME))
                .thenReturn(List.of(allocation(UUID.randomUUID(), UUID.randomUUID(), 3)));

        mockMvc.perform(get("/allocations").with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** The same allocation on both sides (self-trade) must not appear twice. */
    @Test
    void getAllocations_dedupesAllocationAppearingOnBothSides() throws Exception {
        AllocationResponse shared = allocation(UUID.randomUUID(), UUID.randomUUID(), 4);
        when(allocationService.findMyRentals(ME)).thenReturn(List.of(shared));
        when(allocationService.findMySupply(ME)).thenReturn(List.of(shared));

        mockMvc.perform(get("/allocations").with(asMe()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
