package com.eventmanager.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Global rate-limiting filter, keyed by client IP so it protects the whole app — both
 * {@code /api/v1/**} and {@code /ui/**} — regardless of authentication state. Registered
 * explicitly via {@link com.eventmanager.config.RateLimitConfig} with the highest possible
 * precedence so it runs before every Spring Security filter chain: a rejected request never
 * reaches authentication, session, or CSRF handling. Not itself a {@code @Component} — see
 * RateLimitConfig for why.
 * <p>
 * Skips {@code /actuator/**} unconditionally so Prometheus scraping and Kubernetes
 * liveness/readiness probes are never throttled.
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled() || request.getRequestURI().startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientId = clientIp(request);
        if (rateLimiter.isAllowed("ip:" + clientId)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit exceeded for client='{}' path='{}'", clientId, request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(properties.getWindowDuration().toSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":429,\"message\":\"Rate limit exceeded. Try again later.\"}");
    }

    /** Prefers X-Forwarded-For (set by the Traefik reverse proxy) over the raw socket address. */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
