package com.cpq.system.notification;

import com.cpq.notification.entity.Notification;
import com.cpq.system.entity.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * Notification REST API tests.
 *
 * NOTI-LIST-08: GET /notifications returns only the current user's notifications.
 * NOTI-MARK-ALL-09: POST /notifications/mark-all-read marks all as read, unread-count becomes 0.
 * NOTI-UNREAD-COUNT-10: GET /notifications/unread-count returns the correct unread count.
 *
 * NOTE: NotificationResource calls sessionHelper.getCurrentUserId() which throws 401
 * when no session exists even with RBAC disabled. Tests must login first to obtain
 * a CPQ_SESSION cookie. Admin credentials from seed data are used (Admin@2026).
 *
 * Isolation: each test class run uses two distinct test user UUIDs. Cleanup removes
 * all notifications and test users created in @BeforeEach so tests are idempotent.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationResourceTest {

    @Inject
    EntityManager em;

    /** UUID of user A — the "current" user who will call the API. */
    private static UUID userAId;

    /** UUID of user B — whose notifications must NOT appear in user A's list. */
    private static UUID userBId;

    /** CPQ_SESSION cookie obtained after login as user A. */
    private static String sessionCookieA;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Login as the given username/password and return the CPQ_SESSION cookie value.
     */
    private static String login(String username, String password) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .when()
                    .post("/api/cpq/auth/login")
                .then()
                    .statusCode(200)
                    .extract()
                    .cookie("CPQ_SESSION");
    }

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    @Transactional
    void setupTestData() {
        // Remove any leftover test state
        em.createNativeQuery("DELETE FROM notification WHERE recipient_id IN (SELECT id FROM \"user\" WHERE username IN ('noti_test_userA', 'noti_test_userB'))").executeUpdate();
        em.createNativeQuery("DELETE FROM \"user\" WHERE username IN ('noti_test_userA', 'noti_test_userB')").executeUpdate();

        String hashA = BCrypt.hashpw("Test@2026!", BCrypt.gensalt(12));
        String hashB = BCrypt.hashpw("Test@2026!", BCrypt.gensalt(12));

        // Create user A — do not set id; let @GeneratedValue assign it
        User ua = new User();
        ua.username = "noti_test_userA";
        ua.fullName = "Noti Test User A";
        ua.email = "noti_test_userA@cpq-test.internal";
        ua.passwordHash = hashA;
        ua.role = "SALES_REP";
        ua.status = "ACTIVE";
        ua.isFirstLogin = false;
        ua.failedLoginAttempts = 0;
        em.persist(ua);
        em.flush(); // flush so that ua.id is populated by the DB sequence
        userAId = ua.id;

        // Create user B
        User ub = new User();
        ub.username = "noti_test_userB";
        ub.fullName = "Noti Test User B";
        ub.email = "noti_test_userB@cpq-test.internal";
        ub.passwordHash = hashB;
        ub.role = "SALES_REP";
        ub.status = "ACTIVE";
        ub.isFirstLogin = false;
        ub.failedLoginAttempts = 0;
        em.persist(ub);
        em.flush();
        userBId = ub.id;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM notification WHERE recipient_id IN (SELECT id FROM \"user\" WHERE username IN ('noti_test_userA', 'noti_test_userB'))").executeUpdate();
        em.createNativeQuery("DELETE FROM \"user\" WHERE username IN ('noti_test_userA', 'noti_test_userB')").executeUpdate();
    }

    /**
     * Login as user A before each test group. Done lazily inside each test
     * to ensure users are already persisted and committed when login is attempted.
     */
    private String getOrCreateSessionCookieA() {
        return login("noti_test_userA", "Test@2026!");
    }

    // -------------------------------------------------------------------------
    // Notification persistence helper (called within @Transactional context)
    // -------------------------------------------------------------------------

    @Transactional
    void persistNotification(UUID recipientId, String title, boolean isRead) {
        Notification n = new Notification();
        n.recipientId = recipientId;
        n.type = "SYSTEM";
        n.title = title;
        n.content = "test content";
        n.isRead = isRead;
        em.persist(n);
    }

    // -------------------------------------------------------------------------
    // NOTI-LIST-08: GET /notifications returns only the calling user's records
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void notiList08_listReturnsOnlyCurrentUserNotifications() {
        // Persist 2 notifications for user A and 1 for user B
        persistNotification(userAId, "User A notification 1", false);
        persistNotification(userAId, "User A notification 2", false);
        persistNotification(userBId, "User B notification", false);

        // Login as user A
        String cookie = getOrCreateSessionCookieA();

        // GET /notifications — should return only user A's notifications
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get("/api/cpq/notifications")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    // All returned items must have recipientId == userAId
                    .body("data.findAll { it.recipientId != '" + userAId + "' }.size()", equalTo(0))
                    // User A has exactly 2
                    .body("data.size()", equalTo(2));
    }

    @Test
    @Order(2)
    void notiList08_listWithoutSessionReturns401() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/notifications")
                .then()
                    .statusCode(401);
    }

    // -------------------------------------------------------------------------
    // NOTI-UNREAD-COUNT-10: GET /notifications/unread-count returns N
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void notiUnreadCount10_insertNUnread_countReturnsN() {
        int N = 5;
        for (int i = 0; i < N; i++) {
            persistNotification(userAId, "Unread notification " + i, false);
        }
        // 1 read notification — must not count
        persistNotification(userAId, "Read notification", true);

        String cookie = getOrCreateSessionCookieA();

        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get("/api/cpq/notifications/unread-count")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.count", equalTo(N));
    }

    @Test
    @Order(4)
    void notiUnreadCount10_noNotifications_countIsZero() {
        String cookie = getOrCreateSessionCookieA();

        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get("/api/cpq/notifications/unread-count")
                .then()
                    .statusCode(200)
                    .body("data.count", equalTo(0));
    }

    // -------------------------------------------------------------------------
    // NOTI-MARK-ALL-09: POST /notifications/mark-all-read → all isRead=true, count=0
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    void notiMarkAll09_markAllReadSetsAllIsReadTrue() {
        // Insert 3 unread notifications for user A
        persistNotification(userAId, "Unread A1", false);
        persistNotification(userAId, "Unread A2", false);
        persistNotification(userAId, "Unread A3", false);

        String cookie = getOrCreateSessionCookieA();

        // Confirm unread count is 3
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get("/api/cpq/notifications/unread-count")
                .then()
                    .statusCode(200)
                    .body("data.count", equalTo(3));

        // POST mark-all-read
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .contentType(ContentType.JSON)
                .when()
                    .post("/api/cpq/notifications/mark-all-read")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200));

        // Unread count must now be 0
        RestAssured.given()
                .cookie("CPQ_SESSION", cookie)
                .when()
                    .get("/api/cpq/notifications/unread-count")
                .then()
                    .statusCode(200)
                    .body("data.count", equalTo(0));
    }

    @Test
    @Order(6)
    void notiMarkAll09_markAllReadDoesNotAffectOtherUsersNotifications() {
        // User A: 2 unread; User B: 1 unread
        persistNotification(userAId, "A unread", false);
        persistNotification(userAId, "A unread 2", false);
        persistNotification(userBId, "B unread", false);

        String cookieA = getOrCreateSessionCookieA();
        String cookieB = login("noti_test_userB", "Test@2026!");

        // Mark all read for user A
        RestAssured.given()
                .cookie("CPQ_SESSION", cookieA)
                .contentType(ContentType.JSON)
                .when()
                    .post("/api/cpq/notifications/mark-all-read")
                .then()
                    .statusCode(200);

        // User B's unread count must remain 1
        RestAssured.given()
                .cookie("CPQ_SESSION", cookieB)
                .when()
                    .get("/api/cpq/notifications/unread-count")
                .then()
                    .statusCode(200)
                    .body("data.count", equalTo(1));
    }
}
