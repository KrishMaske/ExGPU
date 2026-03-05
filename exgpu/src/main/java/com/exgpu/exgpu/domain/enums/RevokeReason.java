package com.exgpu.exgpu.domain.enums;

/** Why a lease was revoked before its window ended. Surfaced to the buyer so the UI can explain. */
public enum RevokeReason {
    /** Token balance hit zero — the KillCompute path. */
    BALANCE_EXHAUSTED,
    /** Withdrawn manually by an operator. */
    OPERATOR
}
