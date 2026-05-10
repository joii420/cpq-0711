package com.cpq.system.operationlog;

import com.cpq.system.entity.OperationLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * OperationLogResource REST API tests.
 *
 * OPL-LIST-11: GET /operation-logs?operationType=APPROVE&targetType=QUOTATION returns
 * only records matching both filter criteria, not other combinations.
 *
 * NOTE: OperationLogResource has @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"}).
 * Because cpq.security.rbac.enabled=false in test profile, RoleFilter returns
 * immediately without checking session. The resource itself does not call
 * SessionHelper, so no login cookie is required.
 *
 * The OperationLog entity requires a valid operator_id FK into the "user" table.
 * We resolve the seeded admin user's ID at test time via JPQL query to avoid
 * hard-coding a UUID that may differ across environments.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationLogResourceTest {

    @Inject
    EntityManager em;

    /** Seeded admin user ID used as operator_id FK. */
    private static UUID adminId;

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    @Transactional
    void setupTestData() {
        // Remove any test-owned entries first
        em.createNativeQuery(
            "DELETE FROM operation_log WHERE summary LIKE 'opl-test-%'"
        ).executeUpdate();

        // Resolve admin user ID once (cached in static field after first run)
        if (adminId == null) {
            Object result = em
                    .createQuery("SELECT u.id FROM User u WHERE u.username = 'admin'")
                    .getSingleResult();
            adminId = (UUID) result;
        }
    }

    @AfterEach
    @Transactional
    void cleanup() {
        em.createNativeQuery(
            "DELETE FROM operation_log WHERE summary LIKE 'opl-test-%'"
        ).executeUpdate();
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    @Transactional
    void persistLog(String operationType, String targetType, String summary) {
        OperationLog log = new OperationLog();
        log.operatorId = adminId;
        log.operationType = operationType;
        log.targetType = targetType;
        log.targetId = UUID.randomUUID();
        log.summary = summary;
        em.persist(log);
    }

    // -------------------------------------------------------------------------
    // OPL-LIST-11: filter by operationType + targetType
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void oplList11_filterByOperationTypeAndTargetType_returnsOnlyMatchingRecords() {
        // Insert target records: APPROVE on QUOTATION (should match)
        persistLog("APPROVE", "QUOTATION", "opl-test-approve-quotation-1");
        persistLog("APPROVE", "QUOTATION", "opl-test-approve-quotation-2");

        // Insert noise records: same targetType, different operationType
        persistLog("REJECT", "QUOTATION", "opl-test-reject-quotation");

        // Insert noise records: same operationType, different targetType
        persistLog("APPROVE", "COMPONENT", "opl-test-approve-component");

        // Insert noise records: completely different
        persistLog("CREATE", "PRODUCT", "opl-test-create-product");

        // Call API with both filters
        RestAssured.given()
                .queryParam("operationType", "APPROVE")
                .queryParam("targetType", "QUOTATION")
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    // At least the 2 we inserted
                    .body("data.content.size()", greaterThanOrEqualTo(2))
                    // All returned items must match both filter criteria
                    .body("data.content.findAll { it.operationType != 'APPROVE' }.size()", equalTo(0))
                    .body("data.content.findAll { it.targetType != 'QUOTATION' }.size()", equalTo(0));
    }

    @Test
    @Order(2)
    void oplList11_filterByOperationTypeOnly_returnsAllMatchingOperationType() {
        persistLog("APPROVE", "QUOTATION", "opl-test-approve-q");
        persistLog("APPROVE", "COMPONENT", "opl-test-approve-c");
        persistLog("REJECT",  "QUOTATION", "opl-test-reject-q");

        RestAssured.given()
                .queryParam("operationType", "APPROVE")
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("data.content.findAll { it.operationType != 'APPROVE' }.size()", equalTo(0));
    }

    @Test
    @Order(3)
    void oplList11_filterByTargetTypeOnly_returnsAllMatchingTargetType() {
        persistLog("APPROVE", "QUOTATION", "opl-test-a-q");
        persistLog("REJECT",  "QUOTATION", "opl-test-r-q");
        persistLog("APPROVE", "COMPONENT", "opl-test-a-c");

        RestAssured.given()
                .queryParam("targetType", "QUOTATION")
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("data.content.findAll { it.targetType != 'QUOTATION' }.size()", equalTo(0));
    }

    @Test
    @Order(4)
    void oplList11_noFilters_returnsAllRecords() {
        persistLog("APPROVE", "QUOTATION", "opl-test-nofilter-1");
        persistLog("CREATE",  "PRODUCT",   "opl-test-nofilter-2");

        RestAssured.given()
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.content.size()", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(5)
    void oplList11_filterReturnsNoResults_returnsEmptyContentWithZeroTotal() {
        // No records with this combination exist
        RestAssured.given()
                .queryParam("operationType", "NONEXISTENT_OP_XYZ")
                .queryParam("targetType", "NONEXISTENT_TYPE_XYZ")
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.content.size()", equalTo(0))
                    .body("data.totalElements", equalTo(0));
    }

    @Test
    @Order(6)
    void oplList11_pagination_pageSizeRespected() {
        // Insert 5 APPROVE/QUOTATION entries
        for (int i = 0; i < 5; i++) {
            persistLog("APPROVE", "QUOTATION", "opl-test-page-" + i);
        }

        // Request page 0 with size 2
        RestAssured.given()
                .queryParam("operationType", "APPROVE")
                .queryParam("targetType", "QUOTATION")
                .queryParam("page", 0)
                .queryParam("size", 2)
                .when()
                    .get("/api/cpq/operation-logs")
                .then()
                    .statusCode(200)
                    .body("data.content.size()", lessThanOrEqualTo(2))
                    .body("data.size", equalTo(2));
    }
}
