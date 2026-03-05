package com.exgpu.exgpu.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.exgpu.exgpu.domain.AccessLease;

/**
 * Mints short-lived, signed access credentials for a lease.
 *
 * <h2>Why nothing is stored</h2>
 * A credential here is a self-describing signed token, not a secret looked up in a table.
 * It carries the lease id, the allocation, the node, and its own expiry, all covered by an
 * HMAC. A GPU node verifies it offline with the shared secret — no callback to this service,
 * and no credential table to leak. That satisfies "never store raw private keys": the
 * database only ever sees a SHA-256 fingerprint, which cannot be replayed.
 *
 * <h2>Why polling is idempotent</h2>
 * The issue time is snapped down to a fixed bucket ({@link #TOKEN_TTL}). Every request inside
 * the same bucket therefore signs identical bytes and produces a byte-identical token. A
 * client polling every few seconds gets the same credential back each time — no new secret
 * per poll, no database write on a read path, and nothing that accumulates. The token only
 * changes when the bucket rolls over.
 *
 * <p>Expiry is set two buckets out, so a token minted at the very end of a bucket still has a
 * full TTL of life rather than expiring seconds after it is handed over. It is then clamped
 * to the lease window, which is what makes access end exactly when the rental does.
 */
@Component
public class AccessCredentialMinter {

    /**
     * Credential lifetime. Short by design: it is the upper bound on how long a revoked
     * lease's last credential stays usable on a node that verifies offline.
     */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private static final String PREFIX = "exgpu_v1";
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] signingKey;

    public AccessCredentialMinter(@Value("${exgpu.access.signing-secret}") String secret) {
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** A minted credential plus the metadata the caller needs to describe it. */
    public record Credential(String token, Instant expiresAt, String fingerprint) {}

    /**
     * Mints the credential for this lease at this instant.
     *
     * <p>Deterministic within a bucket: calling it repeatedly returns an equal
     * {@link Credential}. Callers must still check the lease actually grants access — this
     * method signs what it is told to sign and makes no authorization decision.
     */
    public Credential mint(AccessLease lease, Instant now) {
        long ttlSeconds = TOKEN_TTL.getSeconds();

        // Snap down to the bucket boundary so every poll in this bucket signs the same bytes.
        long bucketStart = Math.floorDiv(now.getEpochSecond(), ttlSeconds) * ttlSeconds;

        // Two buckets out, so a token issued late in a bucket still lives a full TTL.
        Instant expiry = Instant.ofEpochSecond(bucketStart + 2 * ttlSeconds);

        // Access must never outlive the rental, so clamp to the window end.
        if (expiry.isAfter(lease.getWindowEnd())) {
            expiry = lease.getWindowEnd();
        }

        String payload = String.join("|",
                lease.getId().toString(),
                lease.getAllocationId().toString(),
                lease.getBuyerId().toString(),
                lease.getNodeRef(),
                Long.toString(expiry.getEpochSecond()));

        String encodedPayload = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(hmac(encodedPayload.getBytes(StandardCharsets.US_ASCII)));
        String token = PREFIX + "." + encodedPayload + "." + signature;

        return new Credential(token, expiry, fingerprint(token));
    }

    /** SHA-256 of a token — safe to persist and to write to logs. */
    public String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Verifies a token's signature and expiry — the check a GPU node would run.
     *
     * <p>Uses a constant-time comparison so a caller cannot recover a valid signature by
     * timing repeated guesses.
     */
    public boolean verify(String token, Instant now) {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) return false;

        byte[] expected = hmac(parts[1].getBytes(StandardCharsets.US_ASCII));
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!MessageDigest.isEqual(expected, presented)) return false;

        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\|");
            if (fields.length != 5) return false;
            return now.getEpochSecond() < Long.parseLong(fields[4]);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Extracts the allocation id a token was issued for, or null if it is malformed. */
    public UUID allocationIdOf(String token) {
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            return UUID.fromString(payload.split("\\|")[1]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign access credential", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
