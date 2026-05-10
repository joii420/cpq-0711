package com.cpq.auth.service;

import com.cpq.common.exception.BusinessException;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Sliding-window rate limiter for {@code /auth/login} backed by Redis.
 *
 * <p>Two independent windows:
 * <ul>
 *   <li><b>Per IP:</b> 30 attempts / minute — defends against credential-stuffing</li>
 *   <li><b>Per username:</b> 10 attempts / minute — defends against targeted brute force</li>
 * </ul>
 *
 * <p>The per-account 5-failure-then-lockout policy in {@link AuthService} still applies;
 * this layer simply throttles the burst rate before the count-based lockout kicks in.
 */
@ApplicationScoped
public class LoginRateLimiter {

    private static final Logger LOG = Logger.getLogger(LoginRateLimiter.class);

    private static final int IP_LIMIT_PER_MIN = 30;
    private static final int USER_LIMIT_PER_MIN = 10;
    private static final long WINDOW_SECONDS = 60;

    private static final String IP_PREFIX = "cpq:rl:login:ip:";
    private static final String USER_PREFIX = "cpq:rl:login:user:";

    @Inject
    RedisDataSource redisDS;

    /**
     * Check both IP and username windows. Throws {@link BusinessException} 429 on exceed.
     * Increments the windows on every call (whether the credentials were valid or not).
     */
    public void check(String username, HttpServerRequest request) {
        String ip = extractIp(request);
        if (ip != null) checkAndIncrement(IP_PREFIX + ip, IP_LIMIT_PER_MIN, "IP " + ip);
        if (username != null && !username.isBlank()) {
            checkAndIncrement(USER_PREFIX + username.trim().toLowerCase(),
                    USER_LIMIT_PER_MIN, "user " + username);
        }
    }

    private void checkAndIncrement(String key, int limit, String subject) {
        try {
            ValueCommands<String, Long> values = redisDS.value(String.class, Long.class);
            long current = values.incr(key);
            if (current == 1L) {
                // first hit in window — set TTL
                KeyCommands<String> keys = redisDS.key(String.class);
                keys.expire(key, WINDOW_SECONDS);
            }
            if (current > limit) {
                LOG.warnf("Login rate limit exceeded for %s: %d > %d in %ds window",
                        subject, current, limit, WINDOW_SECONDS);
                throw new BusinessException(429,
                        "登录尝试过于频繁，请稍后重试（" + subject + "）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Fail-open: don't block authentic users if Redis hiccups
            LOG.debugf("Rate limiter error (failing open): %s", e.getMessage());
        }
    }

    private String extractIp(HttpServerRequest request) {
        if (request == null) return null;
        // Honor X-Forwarded-For when behind a proxy; first value in the comma list
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.remoteAddress() != null ? request.remoteAddress().host() : null;
    }
}
