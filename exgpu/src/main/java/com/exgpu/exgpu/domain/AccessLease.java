package com.exgpu.exgpu.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.exgpu.exgpu.domain.enums.LeaseStatus;
import com.exgpu.exgpu.domain.enums.RevokeReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A buyer's time-bounded right to use the compute they matched on.
 *
 * <p>This is the operational counterpart to an {@link Allocation}. The allocation is the
 * commercial record — who bought what, at what price. The lease answers the only question
 * the buyer actually cares about at runtime: <em>can I get in right now?</em>
 *
 * <p>Critically, no credential is stored on this entity. Access tokens are minted on demand,
 * signed, and short-lived; all that is retained is a fingerprint of the last one issued, for
 * audit correlation. A database dump therefore contains nothing that grants access.
 */
@Entity
@Table(name = "access_leases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AccessLease {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "allocation_id", nullable = false, unique = true, updatable = false)
    private UUID allocationId;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private UUID buyerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private LeaseStatus status = LeaseStatus.PENDING;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "node_ref", nullable = false, length = 100)
    private String nodeRef;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 50)
    private RevokeReason revokeReason;

    @Column(name = "last_credential_fingerprint", length = 64)
    private String lastCredentialFingerprint;

    @Column(name = "last_issued_at")
    private Instant lastIssuedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /**
     * Whether credentials may be minted right now.
     *
     * <p>Checks the clock as well as the status. The scheduler moves rows between states on a
     * fixed tick, so between ticks a row can be stale — {@code ACTIVE} with a window that has
     * just closed. Re-checking the window here means access closes exactly on time rather
     * than up to one tick late, which matters because that gap would otherwise be a window of
     * unauthorized access.
     */
    public boolean grantsAccessAt(Instant now) {
        return status == LeaseStatus.ACTIVE
                && !now.isBefore(windowStart)
                && now.isBefore(windowEnd);
    }

    /** True once the window has closed, regardless of what the stored status says. */
    public boolean windowHasEnded(Instant now) {
        return !now.isBefore(windowEnd);
    }

    /** True while the window is still in the future. */
    public boolean windowNotStarted(Instant now) {
        return now.isBefore(windowStart);
    }

    /** Terminal states never transition again. */
    public boolean isTerminal() {
        return status == LeaseStatus.EXPIRED || status == LeaseStatus.REVOKED;
    }
}
