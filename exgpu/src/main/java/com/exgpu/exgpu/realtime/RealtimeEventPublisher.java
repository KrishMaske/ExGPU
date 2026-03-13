package com.exgpu.exgpu.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Pushes {@link RealtimeEvent}s to WebSocket clients.
 *
 * <p>There are two delivery modes, and picking the right one is a privacy decision:
 *
 * <ul>
 *   <li>{@link #publishToUser} — the default for anything tied to a person. Routed to
 *       {@code /user/queue/events}, which the broker delivers only to sessions whose
 *       authenticated principal matches. Balances, billing, fills and allocations all go
 *       here.</li>
 *   <li>{@link #publishMarket} — the public {@code /topic/market} feed. Anyone, signed in or
 *       not, receives these, so payloads must never carry owner ids, balances or amounts
 *       owed. It exists so the "browse supply" page can update live without an account.</li>
 * </ul>
 *
 * <p>Publishing is side-effect-only and never throws back into the caller: a broker hiccup
 * must not roll back an order or a billing transaction.
 */
@Service
public class RealtimeEventPublisher {

    /** Public, identity-free market activity. Safe for anonymous subscribers. */
    public static final String MARKET_TOPIC = "/topic/market";

    /** Per-user destination suffix; the broker prefixes it with /user/{principal}. */
    public static final String USER_QUEUE = "/queue/events";

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Delivers an event to exactly one user's sessions.
     *
     * @param userId the owner this event belongs to; null is ignored rather than broadcast,
     *               so a missing owner can never accidentally fan out to everyone
     */
    public void publishToUser(UUID userId, RealtimeEventType type, String message,
                              String entityId, Object payload) {
        if (userId == null) {
            log.warn("Dropping {} event with no target user (entityId={})", type, entityId);
            return;
        }
        RealtimeEvent event = build(type, message, entityId, payload);
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, event);
        } catch (Exception e) {
            log.warn("Failed to publish user event type={} entityId={}: {}", type, entityId, e.getMessage());
        }
    }

    /**
     * Delivers the same event to several users, de-duplicated — used where one action has two
     * interested parties, such as an allocation that concerns both the buyer and the seller.
     */
    public void publishToUsers(RealtimeEventType type, String message, String entityId,
                               Object payload, UUID... userIds) {
        for (UUID id : new LinkedHashSet<>(java.util.Arrays.asList(userIds))) {
            publishToUser(id, type, message, entityId, payload);
        }
    }

    /**
     * Broadcasts an anonymous market signal to every subscriber, including logged-out
     * visitors. Callers are responsible for passing a payload that identifies no one.
     */
    public void publishMarket(RealtimeEventType type, String message, String entityId, Object payload) {
        RealtimeEvent event = build(type, message, entityId, payload);
        try {
            messagingTemplate.convertAndSend(MARKET_TOPIC, event);
        } catch (Exception e) {
            log.warn("Failed to publish market event type={} entityId={}: {}", type, entityId, e.getMessage());
        }
    }

    private RealtimeEvent build(RealtimeEventType type, String message, String entityId, Object payload) {
        return new RealtimeEvent(
                UUID.randomUUID().toString(), type, message, entityId, payload, Instant.now());
    }
}
