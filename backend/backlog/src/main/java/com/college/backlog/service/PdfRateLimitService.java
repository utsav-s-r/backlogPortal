package com.college.backlog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Fixed-window rate limiter for the two PDF endpoints, backed by Redis (Upstash in production).
 *
 * <p>Why only PDF: iText rendering is the one CPU-bound, repeatable operation in the app, and the
 * deployed instance has 0.1 vCPU. Everything else is I/O-bound and cheap. **The login endpoints are
 * deliberately NOT limited and must never return 429** — throttling there is a DECLINED feature
 * (see {@code docs/adr/student-authentication.md}); {@code LoginThrottleService} and its table were
 * removed on purpose, with the brute-force risk stated and accepted.
 *
 * <p>Keyed on the authenticated principal, never on a client IP or {@code X-Forwarded-For}: behind
 * a proxy those are attacker-controlled, and "authorise on a value the caller can set" is precisely
 * the bug class already fixed once in {@code SecurityConfig}. Both PDF endpoints require auth, so a
 * principal always exists.
 *
 * <p>Costs two Redis commands per allowed request (INCR, plus EXPIRE only when the window opens),
 * which keeps a low-traffic deployment far inside Upstash's free daily command budget.
 */
@Service
public class PdfRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(PdfRateLimitService.class);

    private static final String KEY_PREFIX = "rl:pdf:";

    private final StringRedisTemplate redis;

    @Value("${app.ratelimit.enabled:false}")
    private boolean enabled;

    @Value("${app.ratelimit.pdf.max-per-window:10}")
    private int maxPerWindow;

    @Value("${app.ratelimit.pdf.window-minutes:60}")
    private long windowMinutes;

    public PdfRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Count one PDF request against {@code principal}'s window.
     *
     * @return true if the request may proceed, false if the limit is already spent.
     */
    public boolean tryConsume(String principal) {
        if (!enabled || principal == null || principal.isBlank()) {
            return true;
        }
        String key = KEY_PREFIX + principal;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                // No value back means the command did not really execute; fail OPEN rather than
                // lock a legitimate user out of their own form.
                log.warn("PDF rate limit: INCR returned null for key={}, allowing request", key);
                return true;
            }
            if (count == 1L) {
                // First hit opens the window. Set the TTL only here, so a burst cannot keep
                // pushing the expiry out and turn a fixed window into a rolling one.
                redis.expire(key, Duration.ofMinutes(windowMinutes));
            }
            return count <= maxPerWindow;
        } catch (RuntimeException e) {
            // FAIL OPEN, but never silently: a rate limiter is a guard, not a dependency, and
            // taking the app down because Upstash is unreachable would be a worse outcome than
            // briefly unlimited PDF generation. WARN so it is visible in the logs.
            log.warn("PDF rate limit unavailable ({}); allowing request", e.toString());
            return true;
        }
    }

    /** Seconds a caller should wait before retrying — the whole window, for a fixed-window limiter. */
    public long retryAfterSeconds() {
        return Duration.ofMinutes(windowMinutes).toSeconds();
    }
}
