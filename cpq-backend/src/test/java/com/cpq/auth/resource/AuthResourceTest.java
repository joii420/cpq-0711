package com.cpq.auth.resource;

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

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthResourceTest {

    private static final Logger LOG = Logger.getLogger(AuthResourceTest.class);

    @Inject
    EntityManager em;

    /**
     * Before each test, ensure the admin has a known password and reset any lockout state.
     * We do this before each test to be safe (some tests intentionally cause failed attempts).
     */
    @BeforeEach
    @Transactional
    void setupAdminUser() {
        String hash = BCrypt.hashpw("Admin@2026", BCrypt.gensalt(12));
        em.createQuery(
                "UPDATE User u SET u.passwordHash = :hash, u.failedLoginAttempts = 0, u.lockedUntil = null, u.isFirstLogin = true, u.status = 'ACTIVE' WHERE u.username = 'admin'")
                .setParameter("hash", hash)
                .executeUpdate();
    }

    // -------------------------------------------------------------------------
    // POST /login
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void loginWithValidCredentials() {
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
                .body("code", equalTo(200))
                .body("data.username", equalTo("admin"))
                .body("data.role", equalTo("SYSTEM_ADMIN"))
                .body("data.forceChangePassword", equalTo(true));
    }

    @Test
    @Order(2)
    void loginWithWrongPasswordFails() {
        String body = """
                {
                  "username": "admin",
                  "password": "wrong"
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
    @Order(3)
    void loginWithNonexistentUserFails() {
        String body = """
                {
                  "username": "nobody",
                  "password": "test"
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

    // -------------------------------------------------------------------------
    // GET /me
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    void getMeWithoutSessionReturns401() {
        RestAssured.given()
            .when()
                .get("/api/cpq/auth/me")
            .then()
                .statusCode(401);
    }

    @Test
    @Order(5)
    void getMeAfterLoginReturnsUserInfo() {
        String body = """
                {
                  "username": "admin",
                  "password": "Admin@2026"
                }
                """;

        // Login and capture session cookie
        String cookie = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/auth/login")
            .then()
                .statusCode(200)
                .extract()
                .cookie("CPQ_SESSION");

        // Use the session cookie to call /me
        RestAssured.given()
            .cookie("CPQ_SESSION", cookie)
            .when()
                .get("/api/cpq/auth/me")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.username", equalTo("admin"))
                .body("data.role", equalTo("SYSTEM_ADMIN"));
    }

    // -------------------------------------------------------------------------
    // POST /logout
    // -------------------------------------------------------------------------

    @Test
    @Order(6)
    void logoutClearsSession() {
        String body = """
                {
                  "username": "admin",
                  "password": "Admin@2026"
                }
                """;

        // Login
        String cookie = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/auth/login")
            .then()
                .statusCode(200)
                .extract()
                .cookie("CPQ_SESSION");

        // Logout
        RestAssured.given()
            .cookie("CPQ_SESSION", cookie)
            .when()
                .post("/api/cpq/auth/logout")
            .then()
                .statusCode(200);

        // /me should now fail with 401
        RestAssured.given()
            .cookie("CPQ_SESSION", cookie)
            .when()
                .get("/api/cpq/auth/me")
            .then()
                .statusCode(401);
    }
}
