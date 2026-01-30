package com.exgpu.exgpu.domain.enums;

public enum AllocationStatus {
    /** Buyer cancelled before the window ran. Capacity is returned to the provider's listing. */
    CANCELLED,
    ACTIVE,
    COMPLETED,
    KILLED
}
