package com.exgpu.exgpu.dto;

import java.time.Instant;
import java.util.UUID;

import com.exgpu.exgpu.domain.enums.RevokeReason;

/**
 * What the buyer sees when they ask "can I get into my compute?".
 *
 * <p>One shape covers all four states rather than four endpoints, because the client polls a
 * single URL and reacts to whatever comes back. Fields not relevant to the current state are
 * null — notably {@code accessKey}, which is populated only while access is genuinely open.
 *
 * @param state                 PENDING, ACTIVE, EXPIRED or REVOKED
 * @param allocationId          the rental this concerns
 * @param nodeRef               which GPU node the buyer is assigned to
 * @param windowStart           when access opens
 * @param windowEnd             when access closes for good
 * @param secondsUntilAvailable countdown while PENDING; null otherwise
 * @param secondsRemaining      countdown to window end while ACTIVE; null otherwise
 * @param accessKey             the signed credential — ONLY non-null while ACTIVE
 * @param keyExpiresAt          when this particular credential stops verifying; re-poll for a fresh one
 * @param connection            how to actually connect, or null when access is closed
 * @param revokeReason          why access was withdrawn early; null unless REVOKED
 * @param message               human-readable summary for direct display
 */
public record AccessResponse(
        String state,
        UUID allocationId,
        String nodeRef,
        Instant windowStart,
        Instant windowEnd,
        Long secondsUntilAvailable,
        Long secondsRemaining,
        String accessKey,
        Instant keyExpiresAt,
        ConnectionDetails connection,
        RevokeReason revokeReason,
        String message
) {
    /**
     * Mock connection details for the assigned node.
     *
     * <p>The compute itself is simulated, so these describe where a real provisioning agent
     * would place the buyer rather than a live host. The shape matches what the roadmap's
     * SSH flow will need, so wiring a real agent later does not change the contract.
     */
    public record ConnectionDetails(String host, int port, String username, String hint) {}
}
