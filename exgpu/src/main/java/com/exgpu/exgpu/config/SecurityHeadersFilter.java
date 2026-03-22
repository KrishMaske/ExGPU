package com.exgpu.exgpu.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds baseline security headers to every HTTP response. The app has no Spring Security on the
 * classpath, so these are set explicitly:
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — stop MIME sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — block clickjacking / framing of API responses.</li>
 *   <li>{@code Referrer-Policy: no-referrer} — don't leak URLs to third parties.</li>
 *   <li>{@code Cache-Control: no-store} — keep balance/billing responses out of caches.</li>
 * </ul>
 */
@Component
@Order(0)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        filterChain.doFilter(request, response);
    }
}
