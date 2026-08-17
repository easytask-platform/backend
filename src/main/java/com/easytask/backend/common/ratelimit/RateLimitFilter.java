package com.easytask.backend.common.ratelimit;

import com.easytask.backend.common.logging.SecurityAuditLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limit for the public credential endpoints (P6):
 * login, forgot-password, reset-password. Keyed by client IP + path.
 * In-memory on purpose — single-instance dev/college deployment. Disabled
 * under the test profile via {@code easytask.ratelimit.enabled=false}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after the request-logging filter
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/verify-reset-code",
            "/api/v1/auth/reset-password");

    private static final long WINDOW_MILLIS = 60_000;

    private final boolean enabled;
    private final int limitPerMinute;
    private final SecurityAuditLog audit;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${easytask.ratelimit.enabled:true}") boolean enabled,
            @Value("${easytask.ratelimit.limit-per-minute:10}") int limitPerMinute,
            SecurityAuditLog audit) {
        this.enabled = enabled;
        this.limitPerMinute = limitPerMinute;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !enabled || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String key = clientIp(request) + "|" + request.getRequestURI();
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            allowed = window.size() < limitPerMinute;
            if (allowed) {
                window.addLast(now);
            }
        }
        if (!allowed) {
            audit.rateLimited(request.getRequestURI(), clientIp(request));
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":429,"code":"TOO_MANY_REQUESTS",\
                    "message":"Too many attempts. Try again in a minute.","fields":{}}""");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
