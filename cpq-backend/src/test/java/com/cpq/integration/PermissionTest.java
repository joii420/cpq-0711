package com.cpq.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

/**
 * Permission and authentication enforcement tests.
 *
 * The system uses Option B (session-cookie auth without per-route @RoleAllowed annotations).
 * Core endpoints that require authentication use SessionHelper.getCurrentUserId() which
 * throws BusinessException(401) when no valid session is present.
 *
 * Full role enforcement will be added gradually; this test verifies the baseline behavior.
 */
@QuarkusTest
class PermissionTest {

    // -------------------------------------------------------------------------
    // Public endpoints — accessible without auth
    // -------------------------------------------------------------------------

    @Test
    void healthEndpoint_publicAccess() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/health")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.status", equalTo("UP"));
    }

    @Test
    void loginEndpoint_publicAccess_rejectsInvalidCredentials() {
        // Login endpoint is public (no auth needed to attempt login)
        // and returns 401 for invalid credentials — not a 404 or 403
        String body = """
                {
                  "username": "nonexistent",
                  "password": "wrongpassword"
                }
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/auth/login")
                .then()
                    .statusCode(401);
    }

    @Test
    void loginEndpoint_publicAccess_validCredentials_return200() {
        // Verifying that /login itself doesn't require an existing session
        String body = """
                {
                  "username": "admin",
                  "password": "Admin@2026"
                }
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/auth/login")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200));
    }

    // -------------------------------------------------------------------------
    // Protected endpoints — require valid session
    // -------------------------------------------------------------------------

    @Test
    void meEndpoint_withoutSession_returns401() {
        // GET /me is protected: requires a valid CPQ_SESSION cookie
        RestAssured.given()
                .when()
                    .get("/api/cpq/auth/me")
                .then()
                    .statusCode(401);
    }

    @Test
    void meEndpoint_withInvalidSessionCookie_returns401() {
        // A made-up / expired session token should also yield 401
        RestAssured.given()
                .cookie("CPQ_SESSION", "invalid-session-token-xyz")
                .when()
                    .get("/api/cpq/auth/me")
                .then()
                    .statusCode(401);
    }
}
