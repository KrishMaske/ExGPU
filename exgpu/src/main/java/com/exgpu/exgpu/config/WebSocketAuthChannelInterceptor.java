package com.exgpu.exgpu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates STOMP sessions at the CONNECT frame.
 *
 * <p>Why not the HTTP filter chain? The browser WebSocket API cannot set an
 * {@code Authorization} header on the opening handshake, so Spring Security has nothing to
 * read there — which is why {@code /ws/**} is permitted in {@link SecurityConfig}. The real
 * check happens here instead: the client puts the Supabase access token in the STOMP CONNECT
 * frame's headers, this interceptor verifies it with the same {@link JwtDecoder} the REST
 * side uses, and attaches the verified user id as the session {@link Principal}.
 *
 * <p>That principal is what makes per-user delivery possible: {@code convertAndSendToUser}
 * routes by principal name, so a client can only ever receive its own events. Without it,
 * every subscriber would receive every other user's balance and billing activity.
 *
 * <p>A CONNECT with a missing or invalid token is rejected outright, which closes the
 * socket rather than leaving an unauthenticated session attached to the broker.
 */
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthChannelInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public WebSocketAuthChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = extractToken(accessor);
        if (token == null) {
            throw new IllegalArgumentException("Missing Authorization header on STOMP CONNECT");
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            UUID userId = UUID.fromString(jwt.getSubject());
            accessor.setUser(new StompPrincipal(userId.toString()));
            log.debug("STOMP session authenticated for user {}", userId);
        } catch (JwtException e) {
            // Do not echo the decoder message back to the client — it can describe why a
            // token failed validation.
            log.debug("Rejected STOMP CONNECT: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid access token");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid access token");
        }

        return message;
    }

    private String extractToken(StompHeaderAccessor accessor) {
        List<String> headers = accessor.getNativeHeader("Authorization");
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String raw = headers.get(0);
        if (raw == null || !raw.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = raw.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Minimal principal whose name is the Supabase user id — the key user destinations route on. */
    record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
