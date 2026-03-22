package com.exgpu.exgpu.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Reads the authenticated user's id out of the verified Supabase JWT.
 *
 * <p>Supabase mints {@code sub} as the user's UUID, which is exactly the shape
 * {@code orders.owner_id} and {@code token_balances.buyer_id} already expect — so identity
 * slots into the existing schema with no migration.
 *
 * <p>This is deliberately the <em>only</em> way a request's owner is determined. Nothing
 * reads an owner id out of a request body any more: a caller can no longer place orders on
 * someone else's behalf, top up someone else's balance, or read a balance that is not
 * theirs.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return the verified caller's id
     * @throws ResponseStatusException 401 if there is no authenticated JWT, 500 if the token
     *         passed validation but carries a {@code sub} that is not a UUID (a Supabase
     *         misconfiguration rather than a client error, so it is not blamed on the caller)
     */
    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token has no subject");
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Token subject is not a valid user id");
        }
    }

    /**
     * The caller's id, or null when the request is unauthenticated.
     *
     * <p>For endpoints that are public but personalise when a token is present — the market
     * listing hides your own offers from you, yet must still render for a logged-out visitor.
     */
    public static UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The caller's email, when the token carries one. Used for display only, never for authorization. */
    public static String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("email");
        }
        return null;
    }
}
