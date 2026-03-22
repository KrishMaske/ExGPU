package com.exgpu.exgpu.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Deny-by-default security for the exchange API.
 *
 * <p>Identity is owned by Supabase Auth, not by this app: there is no users table and no
 * password handling here. The frontend signs in with supabase-js and sends the resulting
 * access token as {@code Authorization: Bearer <jwt>}. This config validates that token
 * against the Supabase project's JWKS endpoint (ES256, asymmetric — so the app never needs
 * to hold a shared signing secret) and exposes the verified {@code sub} claim as the
 * authenticated principal.
 *
 * <p>Why this matters beyond "adding login": before this, {@code ownerId} arrived in the
 * request body, so any caller could place orders as anyone and read anyone's balance by
 * guessing a UUID. {@link CurrentUser} now derives the owner from the verified token, which
 * makes the identity half of the billing trust model as strong as the telemetry half.
 *
 * <p>Public by design:
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/prometheus} — the dashboard health tile
 *       and the Prometheus scraper both read these unauthenticated.</li>
 *   <li>{@code /market/**} — browsable GPU supply. A visitor must be able to see what is
 *       for rent before creating an account; these expose listing data only, never balances
 *       or owner identities.</li>
 *   <li>Swagger UI and the OpenAPI document, for local API exploration.</li>
 *   <li>{@code /ws/**} — the STOMP handshake. See the note below.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String FRONTEND_ORIGIN = "http://localhost:3001";

    private final String jwkSetUri;
    private final String issuer;

    public SecurityConfig(
            @Value("${exgpu.supabase.jwk-set-uri}") String jwkSetUri,
            @Value("${exgpu.supabase.issuer}") String issuer) {
        this.jwkSetUri = jwkSetUri;
        this.issuer = issuer;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // The API is stateless and token-authenticated: there is no session or
                // login form to protect, and no cookie for a cross-site request to ride on,
                // so CSRF protection has nothing to defend here.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // CORS preflights carry no credentials and must never be challenged.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()

                        // Anonymous browsing of supply — the shop window.
                        .requestMatchers(HttpMethod.GET, "/market/**").permitAll()

                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**").permitAll()

                        // SECURITY: the STOMP handshake is permitted here because Spring
                        // Security cannot read an Authorization header off a browser
                        // WebSocket open (the browser WebSocket API cannot set one). The
                        // socket is authenticated at the STOMP CONNECT frame instead — see
                        // WebSocketAuthChannelInterceptor — and /topic/events carries only
                        // non-sensitive activity notifications.
                        .requestMatchers("/ws/**").permitAll()

                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Decoder pinned to the Supabase project's JWKS.
     *
     * <p>The explicit algorithm list is essential, not decoration. {@code withJwkSetUri}
     * defaults to <b>RS256 only</b>, while Supabase signs access tokens with <b>ES256</b>.
     * Without this, every genuine token is refused with "Another algorithm expected, or no
     * matching key(s) found" — a 401 that looks exactly like a bad token rather than a
     * server misconfiguration.
     *
     * <p>RS256 is listed alongside ES256 because older Supabase projects (and projects still
     * on the legacy shared JWT secret) issue RS256, and a project can be migrated between the
     * two. Accepting both means a key rotation does not silently lock every user out. Only
     * these two are accepted — the set is a whitelist, so an attacker cannot downgrade to a
     * weaker algorithm, and {@code none} is never permitted.
     *
     * <p>Validating the issuer as well as the signature stops a correctly-signed token from a
     * <em>different</em> Supabase project being replayed against this API.
     */
    @Bean
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256);
                    algorithms.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256);
                })
                .build();
        decoder.setJwtValidator(
                org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /**
     * Single source of CORS truth now that Spring Security is on the classpath. This
     * replaces the old WebMvcConfigurer mapping, which the security filter chain would sit
     * in front of anyway.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(FRONTEND_ORIGIN));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
