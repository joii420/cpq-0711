package com.cpq.auth;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Session lifecycle integration tests.
 *
 * Test cases:
 *   SEC-SESSION-13   : Session expires (Redis key deleted) -> subsequent /me returns 401
 *   SEC-CONCURRENT-14: Two independent sessions for the same user -> both valid simultaneously
 *
 * Design notes:
 * - SessionHelper stores sessions in Redis under "cpq:session:{sessionId}" with TTL.
 * - "Expiry" is simulated by directly deleting the Redis key (equivalent to TTL reaching 0).
 * - The PRD requires 30-minute session timeout; the current implementation uses
 *   Duration.ofHours(8) (8 hours). This discrepancy is noted in the test comment.
 *   SEC-SESSION-13 tests the expiry mechanism regardless of configured duration.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SessionLifecycleTest - session expiry and concurrent sessions")
class SessionLifecycleTest {

    private static final Logger LOG = Logger.getLogger(SessionLifecycleTest.class);

    private static final String LOGIN_URL = "/api/cpq/auth/login";
    private static final String ME_URL    = "/api/cpq/auth/me";
    private static final String LOGOUT_URL = "/api/cpq/auth/logout";

    /** Redis key prefix used by SessionHelper (must match SessionHelper.KEY_PREFIX). */
    private static final String SESSION_KEY_PREFIX = "cpq:session:";

    @Inject
    EntityManager em;

    @Inject
    RedisDataSource redisDS;

    @BeforeEach
    @Transactional
    void resetAdminUser() {
        // Ensure admin has a known password and is unlocked before each test
        String hash = BCrypt.hashpw("Admin@2026", BCrypt.gensalt(12));
        em.createQuery(
                "UPDATE User u SET u.passwordHash = :hash, u.failedLoginAttempts = 0, " +
                "u.lockedUntil = null, u.isFirstLogin = true, u.status = 'ACTIVE' " +
                "WHERE u.username = 'admin'")
                .setParameter("hash", hash)
                .executeUpdate();
    }

    // =========================================================================
    // Helper: login and extract CPQ_SESSION cookie value
    // =========================================================================

    private String loginAsAdmin() {
        String body = """
                {
                  "username": "admin",
                  "password": "Admin@2026"
                }
                """;
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post(LOGIN_URL)
                .then()
                    .statusCode(200)
                    .extract()
                    .cookie("CPQ_SESSION");
    }

    // =========================================================================
    // SEC-SESSION-13: Session expiry -> /me returns 401
    // =========================================================================

    /**
     * SEC-SESSION-13
     * PRD reference: security chapter - session timeout (PRD specifies 30 minutes;
     *   current implementation uses 8 hours — discrepancy flagged for PM review).
     *
     * NOTE: The actual configured TTL is Duration.ofHours(8) in SessionHelper, but
     *   PRD §security requires 30-minute idle timeout. This mismatch should be
     *   clarified with PM and the SessionHelper SESSION_TTL constant adjusted accordingly.
     *
     * This test validates the expiry mechanism itself by directly deleting the Redis
     * session key (simulating TTL reaching zero), then verifying /me returns 401.
     *
     * Steps:
     *   1. POST /auth/login -> 200, extract CPQ_SESSION cookie
     *   2. GET /auth/me with cookie -> 200 (session active)
     *   3. Delete Redis key "cpq:session:{cookie}" (simulate expiry)
     *   4. GET /auth/me with same cookie -> 401 (session gone)
     */
    @Test
    @Order(1)
    @DisplayName("SEC-SESSION-13: expired session (Redis key deleted) returns 401 on /me")
    void sec_session_13_expiredSessionReturns401() {
        // Step 1: Login and obtain session cookie
        String cookie = loginAsAdmin();
        assertNotNull((Object) cookie, "Login must return a CPQ_SESSION cookie");

        // Step 2: Confirm /me is accessible with valid session
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get(ME_URL)
                .then()
                    .statusCode(200)
                    .body("data.username", equalTo("admin"));

        // Step 3: Simulate session expiry by deleting the Redis key
        // (equivalent to TTL reaching 0 — same effect as 30-minute / 8-hour expiry)
        KeyCommands<String> keys = redisDS.key(String.class);
        String redisKey = SESSION_KEY_PREFIX + cookie;
        keys.del(redisKey);
        LOG.infof("SEC-SESSION-13: deleted Redis key '%s'", redisKey);

        // Step 4: /me must now return 401 (session no longer exists in Redis)
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get(ME_URL)
                .then()
                    .statusCode(401);
    }

    // =========================================================================
    // SEC-CONCURRENT-14: Two independent sessions for same user -> both valid
    // =========================================================================

    /**
     * SEC-CONCURRENT-14
     * PRD reference: security chapter - multi-device login policy.
     *
     * The system ALLOWS multiple concurrent sessions for the same user (no single-sign-on
     * enforcement). This test verifies that:
     *   - Client A can login and call /me successfully
     *   - Client B logs in with the same credentials and calls /me successfully
     *   - Client A's original cookie remains valid after Client B's login
     *
     * Implementation note: SessionHelper.createSession() calls invalidateSession() only
     * for the session cookie already present in the INCOMING REQUEST. Since Client A and
     * Client B use separate HTTP connections (no shared cookie store), Client A's session
     * is NOT invalidated when Client B logs in — confirming multi-device support.
     *
     * If the system were to enforce single-session-per-user (force-logout old sessions),
     * the assertion for Client A should instead expect 401 and the test should be updated
     * to document that single-sign-on behaviour.
     */
    @Test
    @Order(2)
    @DisplayName("SEC-CONCURRENT-14: two concurrent sessions for same user - both remain valid")
    void sec_concurrent_14_twoIndependentSessionsBothValid() {
        // Client A: login independently (no existing cookie in the request)
        String cookieA = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "username": "admin",
                          "password": "Admin@2026"
                        }
                        """)
                .when()
                    .post(LOGIN_URL)
                .then()
                    .statusCode(200)
                    .extract()
                    .cookie("CPQ_SESSION");

        assertNotNull((Object) cookieA, "Client A must receive a CPQ_SESSION cookie");

        // Client A: confirm session is active
        RestAssured.given()
                .cookie("CPQ_SESSION", cookieA)
                .when()
                    .get(ME_URL)
                .then()
                    .statusCode(200)
                    .body("data.username", equalTo("admin"));

        // Client B: login with same credentials (separate request, no cookie sent)
        String cookieB = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "username": "admin",
                          "password": "Admin@2026"
                        }
                        """)
                .when()
                    .post(LOGIN_URL)
                .then()
                    .statusCode(200)
                    .extract()
                    .cookie("CPQ_SESSION");

        assertNotNull((Object) cookieB, "Client B must receive a CPQ_SESSION cookie");
        assertNotEquals((Object) cookieA, (Object) cookieB,
                "SEC-CONCURRENT-14: each login must produce a distinct session cookie");

        // Client B: confirm its session is active
        RestAssured.given()
                .cookie("CPQ_SESSION", cookieB)
                .when()
                    .get(ME_URL)
                .then()
                    .statusCode(200)
                    .body("data.username", equalTo("admin"));

        // Client A: must STILL be valid (multi-device sessions allowed)
        // If the system enforces single-sign-on, change this to statusCode(401)
        RestAssured.given()
                .cookie("CPQ_SESSION", cookieA)
                .when()
                    .get(ME_URL)
                .then()
                    .statusCode(200)
                    .body("data.username", equalTo("admin"));

        // Cleanup: logout both sessions
        RestAssured.given().cookie("CPQ_SESSION", cookieA).post(LOGOUT_URL);
        RestAssured.given().cookie("CPQ_SESSION", cookieB).post(LOGOUT_URL);
    }
}
