package com.exgpu.exgpu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP-over-WebSocket configuration.
 *
 * <ul>
 *   <li>Clients connect at {@code ws://localhost:8080/ws} (raw WebSocket, no SockJS).</li>
 *   <li>The in-memory simple broker relays messages to destinations under {@code /topic}.</li>
 *   <li>Client-bound messages (if any are ever added) would be routed under {@code /app}.</li>
 * </ul>
 *
 * The frontend only subscribes to {@code /topic/events}; it never sends STOMP messages, so
 * the {@code /app} prefix is configured for completeness but currently unused.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String FRONTEND_ORIGIN = "http://localhost:3001";

    private final WebSocketAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(WebSocketAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /queue backs per-user delivery: convertAndSendToUser writes to
        // /user/{principal}/queue/events and the broker fans it out only to that session.
        // /topic remains for genuinely public, identity-free market signals.
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Registers the CONNECT-frame authenticator on the inbound channel. Without this the
     * session has no Principal, user destinations cannot route, and the socket would be
     * anonymous.
     */
    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(FRONTEND_ORIGIN);
    }

    /**
     * Bound the resources a single WebSocket session can consume, so a malicious or buggy
     * client cannot exhaust memory with huge frames or stall the server by never reading.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024);       // 16 KB max inbound STOMP frame
        registration.setSendBufferSizeLimit(512 * 1024);   // 512 KB outbound buffer per session
        registration.setSendTimeLimit(15 * 1000);          // 15s to flush before disconnect
    }
}
