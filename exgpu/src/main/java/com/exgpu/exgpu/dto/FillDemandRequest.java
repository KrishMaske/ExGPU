package com.exgpu.exgpu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A provider's offer to fill an open buy request.
 *
 * <p>Only the quantity is accepted. Price and window come from the request being filled,
 * read server-side — a client cannot propose different terms and have them treated as a
 * fill, and a stale page cannot commit the provider to a price that has since changed.
 */
public record FillDemandRequest(
        @Min(value = 1, message = "Offer at least 1 GPU")
        @Max(value = 1_000_000, message = "quantity is too large")
        int gpus
) {}
