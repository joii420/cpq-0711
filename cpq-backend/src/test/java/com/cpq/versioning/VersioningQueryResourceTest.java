package com.cpq.versioning;

import com.cpq.versioning.query.VersioningQueryResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for VersioningQueryResource (UI-5 / UI-6).
 *
 * <p>Test data setup:
 * - CUST_ID + PART_NO seeded in @BeforeEach
 * - mat_process rows inserted to simulate multi-version history
 * - mat_fee rows for fee-type tests
 * - plating_fee rows for plating tests
 *
 * <p>Test cases:
 *   T1:  list history multiple versions → paginated result
 *   T2:  list history no data → empty page (total=0)
 *   T3:  list history page/size params → correct pagination
 *   T4:  getRowDetail found → all columns returned
 *   T5:  getRowDetail not found → 404
 *   T6:  compareVersions → fieldDiffs calculated correctly (same + different fields)
 *   T7:  compareVersions record not found → 404
 *   T8:  unsupported tableName → 400
 *   T9:  @RoleAllowed annotation present on resource class
 *   T10: list history includes both is_current=true and is_current=false rows
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("VersioningQueryResource — UI-5/6 Versioning History")
class VersioningQueryResourceTest {

    private static final String BASE = "/api/cpq/versioning";

    private static final UUID CUST_ID  = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID  = UUID.fromString("55000000-0000-0000-0000-000000000002");
    private static final String PART_V1 = "VQR-V1-PART";
    private static final String PART_V2 = "VQR-V2-PART";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    // Shared state for cross-test UUID references
    private static UUID rowV1Id;
    private static UUID rowV2Id;
    private static UUID rowFeeId;
    private static UUID rowPlatingId;

    @BeforeEach
    void setup() throws Exception {
        try {
            if (utx.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                utx.rollback();
            }
        } catch (Exception ignored) {}

        utx.begin();
        em.joinTransaction();

        // User
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'vqr-tester', 'VQR Tester', 'vqr@test.com', 'hash', 'SALES_MANAGER', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", USER_ID).executeUpdate();

        // Customer
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'VQR Test Customer', 'VQR-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUST_ID).executeUpdate();

        // Parts
        for (String pn : List.of(PART_V1, PART_V2)) {
            em.createNativeQuery(
                    "INSERT INTO mat_part(part_no, part_name, status_code, created_at, updated_at) " +
                    "VALUES (:pn, :pn, 'Y', NOW(), NOW()) ON CONFLICT (part_no) DO NOTHING")
                    .setParameter("pn", pn).executeUpdate();
        }

        // Clean existing test data
        em.createNativeQuery("DELETE FROM plating_fee WHERE customer_id = :cid").setParameter("cid", CUST_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_fee WHERE customer_id = :cid").setParameter("cid", CUST_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_process WHERE customer_id = :cid").setParameter("cid", CUST_ID).executeUpdate();

        utx.commit();

        // Seed versioned data (separate transactions for determinism)
        seedVersionedData();
    }

    private void seedVersionedData() throws Exception {
        utx.begin();
        em.joinTransaction();

        // mat_process: PART_V1 → v1 (old, is_current=false) + v2 (current)
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        rowV1Id = v1;
        rowV2Id = v2;

        em.createNativeQuery(
                "INSERT INTO mat_process(id, customer_id, hf_part_no, version, is_current, seq_no, sub_seq_no, " +
                "unit_price, currency, status, created_at, updated_at, created_by, updated_by) " +
                "VALUES (:id, :cid, :pn, 1, false, 1, 1, 100.00, 'CNY', 'ACTIVE', NOW(), NOW(), :uid, :uid)")
                .setParameter("id", v1)
                .setParameter("cid", CUST_ID)
                .setParameter("pn", PART_V1)
                .setParameter("uid", USER_ID)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO mat_process(id, customer_id, hf_part_no, version, is_current, seq_no, sub_seq_no, " +
                "unit_price, currency, status, created_at, updated_at, created_by, updated_by) " +
                "VALUES (:id, :cid, :pn, 2, true, 1, 1, 200.00, 'CNY', 'ACTIVE', NOW(), NOW(), :uid, :uid)")
                .setParameter("id", v2)
                .setParameter("cid", CUST_ID)
                .setParameter("pn", PART_V1)
                .setParameter("uid", USER_ID)
                .executeUpdate();

        // mat_fee: PART_V1 → v1 current
        UUID feeId = UUID.randomUUID();
        rowFeeId = feeId;
        em.createNativeQuery(
                "INSERT INTO mat_fee(id, customer_id, hf_part_no, version, is_current, fee_type, seq_no, " +
                "fee_value, currency, status, created_at, updated_at, created_by, updated_by) " +
                "VALUES (:id, :cid, :pn, 1, true, 'INCOMING_FIXED', 1, 50.00, 'CNY', 'ACTIVE', NOW(), NOW(), :uid, :uid)")
                .setParameter("id", feeId)
                .setParameter("cid", CUST_ID)
                .setParameter("pn", PART_V1)
                .setParameter("uid", USER_ID)
                .executeUpdate();

        // plating_fee: PART_V1 → v1 current
        UUID platId = UUID.randomUUID();
        rowPlatingId = platId;
        em.createNativeQuery(
                "INSERT INTO plating_fee(id, customer_id, hf_part_no, version, is_current, plating_plan_code, plan_version, " +
                "plating_process_fee, status, created_at, updated_at, created_by, updated_by) " +
                "VALUES (:id, :cid, :pn, 1, true, 'PLAT-001', 'v1', 30.00, 'ACTIVE', NOW(), NOW(), :uid, :uid)")
                .setParameter("id", platId)
                .setParameter("cid", CUST_ID)
                .setParameter("pn", PART_V1)
                .setParameter("uid", USER_ID)
                .executeUpdate();

        utx.commit();
    }

    // =========================================================================
    // T1: list history multiple versions → paginated result with 2 items
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("T1: listHistory mat_process with 2 versions returns totalElements=2")
    void T1_listHistory_multipleVersions() {
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("customerId", CUST_ID.toString())
            .queryParam("hfPartNo", PART_V1)
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.totalElements", equalTo(2))
            .body("data.content.size()", equalTo(2));
    }

    // =========================================================================
    // T2: list history no data → empty page
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("T2: listHistory no matching data returns empty page")
    void T2_listHistory_noData_returnsEmpty() {
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("customerId", CUST_ID.toString())
            .queryParam("hfPartNo", "NONEXISTENT-PART-99999")
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.totalElements", equalTo(0))
            .body("data.content.size()", equalTo(0));
    }

    // =========================================================================
    // T3: list history page/size → pagination correct
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("T3: listHistory page=0 size=1 returns 1 item, totalElements=2")
    void T3_listHistory_pagination() {
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("customerId", CUST_ID.toString())
            .queryParam("hfPartNo", PART_V1)
            .queryParam("page", "0")
            .queryParam("size", "1")
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("data.totalElements", equalTo(2))
            .body("data.content.size()", equalTo(1))
            .body("data.totalPages", equalTo(2));
    }

    // =========================================================================
    // T4: getRowDetail found → returns all columns
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("T4: getRowDetail found row returns column map with expected fields")
    void T4_getRowDetail_found() {
        given()
            .pathParam("tableName", "mat_process")
            .pathParam("recordId", rowV2Id.toString())
        .when()
            .get(BASE + "/row/{tableName}/{recordId}")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.id", notNullValue())
            .body("data.version", equalTo(2))
            .body("data.is_current", equalTo(true))
            .body("data.currency", equalTo("CNY"));
    }

    // =========================================================================
    // T5: getRowDetail not found → 404
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("T5: getRowDetail for nonexistent recordId returns 404")
    void T5_getRowDetail_notFound() {
        String nonexistent = "00000000-0000-0000-0000-000000000099";
        given()
            .pathParam("tableName", "mat_process")
            .pathParam("recordId", nonexistent)
        .when()
            .get(BASE + "/row/{tableName}/{recordId}")
        .then()
            .statusCode(404);
    }

    // =========================================================================
    // T6: compareVersions → fieldDiffs correct
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("T6: compareVersions returns fieldDiffs with same/different flags")
    void T6_compareVersions_fieldDiffs() {
        // v1: unit_price=100.00, v2: unit_price=200.00; currency=CNY both versions
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("recordIdA", rowV1Id.toString())
            .queryParam("recordIdB", rowV2Id.toString())
        .when()
            .get(BASE + "/compare")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.versionA", equalTo(1))
            .body("data.versionB", equalTo(2))
            .body("data.fieldDiffs.size()", greaterThan(0))
            // unit_price differs
            .body("data.fieldDiffs.find { it.fieldName == 'unit_price' }.sameValue", equalTo(false))
            // currency is same in both
            .body("data.fieldDiffs.find { it.fieldName == 'currency' }.sameValue", equalTo(true));
    }

    // =========================================================================
    // T7: compareVersions record not found → 404
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("T7: compareVersions when recordIdA not found returns 404")
    void T7_compareVersions_recordNotFound() {
        String nonexistent = "00000000-0000-0000-0000-000000000098";
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("recordIdA", nonexistent)
            .queryParam("recordIdB", rowV2Id.toString())
        .when()
            .get(BASE + "/compare")
        .then()
            .statusCode(404);
    }

    // =========================================================================
    // T8: unsupported tableName → 400
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("T8: unsupported tableName returns 400")
    void T8_unsupportedTableName_returns400() {
        given()
            .queryParam("tableName", "injection_attempt; DROP TABLE mat_process")
            .queryParam("customerId", CUST_ID.toString())
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T9: @RoleAllowed annotation present on resource class
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("T9: VersioningQueryResource has @RoleAllowed annotation")
    void T9_roleAllowed_annotationPresent() {
        com.cpq.common.security.RoleAllowed ann =
                VersioningQueryResource.class.getAnnotation(com.cpq.common.security.RoleAllowed.class);
        assertNotNull(ann, "@RoleAllowed annotation should be present on VersioningQueryResource");
        List<String> roles = Arrays.asList(ann.value());
        assertTrue(roles.contains("SALES_REP"),     "should allow SALES_REP");
        assertTrue(roles.contains("SALES_MANAGER"), "should allow SALES_MANAGER");
        assertTrue(roles.contains("SYSTEM_ADMIN"),  "should allow SYSTEM_ADMIN");
    }

    // =========================================================================
    // T10: history includes is_current=false rows
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("T10: listHistory includes both current and historical versions")
    void T10_listHistory_includesBothCurrentAndHistorical() {
        // We seeded v1 (is_current=false) and v2 (is_current=true)
        // The list should contain both, ordered version DESC → first item is v2
        given()
            .queryParam("tableName", "mat_process")
            .queryParam("customerId", CUST_ID.toString())
            .queryParam("hfPartNo", PART_V1)
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("data.totalElements", equalTo(2))
            // First item (version DESC) should be v2 → isCurrent=true
            .body("data.content[0].isCurrent", equalTo(true))
            .body("data.content[0].version",   equalTo(2))
            // Second item should be v1 → isCurrent=false
            .body("data.content[1].isCurrent", equalTo(false))
            .body("data.content[1].version",   equalTo(1));
    }
}
