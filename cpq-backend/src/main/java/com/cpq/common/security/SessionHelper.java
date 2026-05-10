package com.cpq.common.security;

import com.cpq.common.exception.BusinessException;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Session management helper backed by Redis.
 *
 * Each session is stored as a Redis hash under the key "cpq:session:{sessionId}"
 * with a TTL of 8 hours. This survives JVM restarts, hot-reloads, and supports
 * multi-instance deployments.
 */
@ApplicationScoped
public class SessionHelper {

    private static final String SESSION_COOKIE_NAME = "CPQ_SESSION";
    private static final String KEY_PREFIX = "cpq:session:";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USER_ROLE = "userRole";

    @Inject
    RedisDataSource redisDS;

    @ConfigProperty(name = "cpq.security.rbac.enabled", defaultValue = "true")
    boolean rbacEnabled;

    // PRD §23 SEC-SESSION-13: 会话空闲超时 30 分钟（可通过 cpq.session.ttl-minutes 覆盖）
    @ConfigProperty(name = "cpq.session.ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    // Fallback admin UUID for test mode (first SYSTEM_ADMIN from seed data)
    private static final UUID TEST_FALLBACK_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Create a session, store it in Redis, and set the session cookie.
     */
    public String createSession(UUID userId, String role,
                                HttpServerRequest request,
                                HttpServerResponse response) {
        // Invalidate any previous session for this request
        String existing = extractSessionId(request);
        if (existing != null) {
            deleteSession(existing);
        }

        String sessionId = UUID.randomUUID().toString();
        String redisKey = KEY_PREFIX + sessionId;

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(redisKey, Map.of(
                KEY_USER_ID, userId.toString(),
                KEY_USER_ROLE, role
        ));

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.expire(redisKey, sessionTtlMinutes * 60L);

        response.headers().set("Set-Cookie",
                SESSION_COOKIE_NAME + "=" + sessionId
                        + "; Path=/; HttpOnly; SameSite=Lax");
        return sessionId;
    }

    /**
     * Read the current user ID from the session.
     *
     * @throws BusinessException 401 if not logged in or session expired
     */
    public UUID getCurrentUserId(HttpServerRequest request) {
        String val = getSessionValue(request, KEY_USER_ID);
        if (val == null) throw new BusinessException(401, "未登录");
        return UUID.fromString(val);
    }

    /**
     * Get current user ID, or fallback to first admin in test mode (RBAC disabled).
     * Use this in business endpoints that need a user but should work in tests.
     */
    public UUID getCurrentUserIdOrFallback(HttpServerRequest request) {
        String val = getSessionValue(request, KEY_USER_ID);
        if (val != null) return UUID.fromString(val);
        if (!rbacEnabled) {
            var adminUser = com.cpq.system.entity.User.find("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE' ORDER BY createdAt ASC").firstResult();
            if (adminUser != null) return ((com.cpq.system.entity.User) adminUser).id;
            return TEST_FALLBACK_USER_ID;
        }
        throw new BusinessException(401, "未登录");
    }

    /**
     * Read the current user role from the session.
     *
     * @throws BusinessException 401 if not logged in or session expired
     */
    public String getCurrentUserRole(HttpServerRequest request) {
        String val = getSessionValue(request, KEY_USER_ROLE);
        if (val == null) throw new BusinessException(401, "未登录");
        return val;
    }

    /**
     * Get current user role, or fallback to "SALES_MANAGER" in test mode (RBAC disabled).
     * Returns the role of the authenticated user when a session exists; otherwise falls back
     * to "SALES_MANAGER" so that modifiable_by checks in services still enforce per-role
     * access control even without a live session. Keys that need to be mutable in test
     * environments must set modifiable_by to SALES_MANAGER (or lower) in seed data.
     * Production is unaffected because RBAC is always enabled there.
     */
    public String getCurrentUserRoleOrFallback(HttpServerRequest request) {
        String val = getSessionValue(request, KEY_USER_ROLE);
        if (val != null) return val;
        if (!rbacEnabled) return "SALES_MANAGER";
        throw new BusinessException(401, "未登录");
    }

    /**
     * Returns true if the request carries a valid, active session.
     */
    public boolean isLoggedIn(HttpServerRequest request) {
        return getSessionValue(request, KEY_USER_ID) != null;
    }

    /**
     * Asserts that the current request is authenticated as SYSTEM_ADMIN.
     * When RBAC is disabled (test mode), this check is skipped entirely so that
     * admin-only endpoints remain accessible without a session.
     *
     * @throws BusinessException 401 if no session (RBAC enabled)
     * @throws BusinessException 403 if role is not SYSTEM_ADMIN (RBAC enabled)
     */
    public void requireSystemAdmin(HttpServerRequest request) {
        if (!rbacEnabled) {
            return; // Skip role check in test/dev mode — endpoints are unprotected
        }
        String val = getSessionValue(request, KEY_USER_ROLE);
        if (val == null) throw new BusinessException(401, "未登录");
        if (!"SYSTEM_ADMIN".equals(val)) throw new BusinessException(403, "该操作需要系统管理员权限");
    }

    /**
     * Destroy the session and clear the cookie.
     */
    public void invalidateSession(HttpServerRequest request,
                                  HttpServerResponse response) {
        String sessionId = extractSessionId(request);
        if (sessionId != null) {
            deleteSession(sessionId);
        }
        response.headers().set("Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String getSessionValue(HttpServerRequest request, String field) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) return null;

        String redisKey = KEY_PREFIX + sessionId;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        String value = hash.hget(redisKey, field);

        if (value == null) return null;

        // Refresh TTL on each access (sliding expiration)
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.expire(redisKey, sessionTtlMinutes * 60L);

        return value;
    }

    private void deleteSession(String sessionId) {
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.del(KEY_PREFIX + sessionId);
    }

    private String extractSessionId(HttpServerRequest request) {
        String cookieHeader = request.getHeader("Cookie");
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(SESSION_COOKIE_NAME + "=")) {
                return trimmed.substring((SESSION_COOKIE_NAME + "=").length()).trim();
            }
        }
        return null;
    }
}
