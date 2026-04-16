package com.exgpu.exgpu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.exgpu.exgpu.domain.AccessLease;

/**
 * The minter is what makes polling idempotent, so these tests are mostly about "does calling
 * it again give me the same thing" rather than about cryptography.
 */
class AccessCredentialMinterTest {

    private final AccessCredentialMinter minter = new AccessCredentialMinter("test-signing-secret");

    private AccessLease lease(Instant start, Instant end) {
        return AccessLease.builder()
                .id(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .allocationId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .buyerId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                .nodeRef("gpu-node-abc123")
                .windowStart(start)
                .windowEnd(end)
                .build();
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    void mint_calledRepeatedlyWithinSameBucket_returnsIdenticalToken() {
        Instant start = Instant.parse("2026-06-01T10:00:00Z");
        Instant end = Instant.parse("2026-06-01T18:00:00Z");
        AccessLease l = lease(start, end);

        // Three polls a few seconds apart, all inside one 15-minute bucket.
        var a = minter.mint(l, Instant.parse("2026-06-01T12:00:01Z"));
        var b = minter.mint(l, Instant.parse("2026-06-01T12:00:09Z"));
        var c = minter.mint(l, Instant.parse("2026-06-01T12:14:59Z"));

        assertThat(a.token()).isEqualTo(b.token()).isEqualTo(c.token());
        assertThat(a.expiresAt()).isEqualTo(b.expiresAt()).isEqualTo(c.expiresAt());
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void mint_afterBucketRollsOver_returnsDifferentToken() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));

        var before = minter.mint(l, Instant.parse("2026-06-01T12:14:59Z"));
        var after = minter.mint(l, Instant.parse("2026-06-01T12:15:01Z"));

        assertThat(after.token()).isNotEqualTo(before.token());
    }

    /** A token handed over at the very end of a bucket must still be usable for a while. */
    @Test
    void mint_lateInBucket_stillHasAtLeastOneFullTtlOfLife() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T23:00:00Z"));
        Instant lateInBucket = Instant.parse("2026-06-01T12:14:59Z");

        var credential = minter.mint(l, lateInBucket);

        assertThat(Duration.between(lateInBucket, credential.expiresAt()))
                .isGreaterThanOrEqualTo(AccessCredentialMinter.TOKEN_TTL);
    }

    // ── window clamping: access must never outlive the rental ─────────────────

    @Test
    void mint_nearWindowEnd_clampsExpiryToWindowEnd() {
        Instant end = Instant.parse("2026-06-01T12:20:00Z");
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), end);

        // Bucket would otherwise push expiry to 12:30, past the rental's end.
        var credential = minter.mint(l, Instant.parse("2026-06-01T12:16:00Z"));

        assertThat(credential.expiresAt()).isEqualTo(end);
    }

    // ── verification ──────────────────────────────────────────────────────────

    @Test
    void verify_freshToken_isValid() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        Instant now = Instant.parse("2026-06-01T12:00:00Z");

        var credential = minter.mint(l, now);

        assertThat(minter.verify(credential.token(), now)).isTrue();
    }

    @Test
    void verify_afterExpiry_isRejected() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        var credential = minter.mint(l, Instant.parse("2026-06-01T12:00:00Z"));

        assertThat(minter.verify(credential.token(), credential.expiresAt().plusSeconds(1))).isFalse();
    }

    @Test
    void verify_tamperedPayload_isRejected() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        String token = minter.mint(l, now).token();

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XY." + parts[2];

        assertThat(minter.verify(tampered, now)).isFalse();
    }

    @Test
    void verify_tokenSignedWithAnotherKey_isRejected() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        Instant now = Instant.parse("2026-06-01T12:00:00Z");

        String foreign = new AccessCredentialMinter("a-different-secret").mint(l, now).token();

        assertThat(minter.verify(foreign, now)).isFalse();
    }

    @Test
    void verify_garbage_isRejectedWithoutThrowing() {
        Instant now = Instant.now();
        assertThat(minter.verify("not-a-token", now)).isFalse();
        assertThat(minter.verify("exgpu_v1.!!!.???", now)).isFalse();
        assertThat(minter.verify("", now)).isFalse();
    }

    // ── the fingerprint is what gets persisted, so it must not be reversible ──

    @Test
    void fingerprint_isSha256HexAndNotTheToken() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        var credential = minter.mint(l, Instant.parse("2026-06-01T12:00:00Z"));

        assertThat(credential.fingerprint()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(credential.fingerprint()).isNotEqualTo(credential.token());
        assertThat(credential.token()).doesNotContain(credential.fingerprint());
    }

    @Test
    void allocationIdOf_roundTripsFromToken() {
        AccessLease l = lease(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T18:00:00Z"));
        var credential = minter.mint(l, Instant.parse("2026-06-01T12:00:00Z"));

        assertThat(minter.allocationIdOf(credential.token())).isEqualTo(l.getAllocationId());
        assertThat(minter.allocationIdOf("garbage")).isNull();
    }
}
