package com.atelier.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite por IP em janela fixa para os endpoints sensíveis (PRD, seção 20.2).
 * ponytail: contadores em memória, por instância. Com 2+ instâncias, mover para Redis (Bucket4j).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(int limit, Duration window) {}

    private static final Map<String, Rule> RULES = Map.of(
            "/api/auth/login", new Rule(10, Duration.ofMinutes(1)),
            "/api/auth/register", new Rule(5, Duration.ofHours(1)),
            "/api/auth/refresh", new Rule(30, Duration.ofMinutes(1)),
            "/api/auth/forgot-password", new Rule(5, Duration.ofHours(1)),
            "/api/auth/reset-password", new Rule(10, Duration.ofHours(1)),
            "/api/auth/verify-email", new Rule(20, Duration.ofHours(1)),
            "/api/auth/resend-verification", new Rule(3, Duration.ofHours(1)),
            "/api/newsletter", new Rule(10, Duration.ofHours(1)));

    private static final class Window {
        long startedAt;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    RateLimitFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !RULES.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Rule rule = RULES.get(path);
        long now = clock.millis();
        long retryAfterMs = hit(path + "|" + request.getRemoteAddr(), rule, now);
        if (retryAfterMs > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterMs / 1000)));
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Muitas tentativas. Tente novamente mais tarde\","
                    + "\"status\":429,\"code\":\"RATE_LIMITED\",\"requestId\":\"" + MDC.get("requestId") + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** @return 0 se permitido, senão milissegundos até a janela reabrir. */
    private long hit(String key, Rule rule, long now) {
        if (windows.size() > 50_000) purge(now);
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (now - w.startedAt >= rule.window().toMillis()) {
                w.startedAt = now;
                w.count = 0;
            }
            return ++w.count > rule.limit() ? w.startedAt + rule.window().toMillis() - now : 0;
        }
    }

    private void purge(long now) {
        long longest = RULES.values().stream().mapToLong(r -> r.window().toMillis()).max().orElse(0);
        windows.values().removeIf(w -> now - w.startedAt >= longest);
    }

    /** Para testes. */
    public void reset() {
        windows.clear();
    }
}
