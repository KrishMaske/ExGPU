package com.exgpu.exgpu.domain.enums;

/**
 * Lifecycle of a buyer's access to matched compute.
 *
 * <p>The transitions are driven by the clock, not by user action:
 * {@code PENDING → ACTIVE} when the window opens, {@code ACTIVE → EXPIRED} when it closes.
 * {@code REVOKED} is the only early exit and is reserved for access withdrawn before the
 * window would naturally end.
 */
public enum LeaseStatus {
    /** Window has not started. Access is not yet available. */
    PENDING,
    /** Inside the window. Credentials may be minted. */
    ACTIVE,
    /** Window ended normally. */
    EXPIRED,
    /** Access withdrawn early — balance exhausted, or an operator kill. */
    REVOKED
}
