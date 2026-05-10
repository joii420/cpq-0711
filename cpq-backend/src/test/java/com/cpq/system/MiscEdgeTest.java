package com.cpq.system;

import com.cpq.quotation.entity.QuotationWithdrawRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MiscEdgeTest — 杂项边界用例。
 *
 * <p>Covers:
 * <ul>
 *   <li>MD-FIELD-IMP-05: PATCH /api/cpq/basic-data-config/attributes/{id}/importance 端点可达
 *       (RBAC disabled; 验证 200 或 4xx，非 500)</li>
 *   <li>DDL-FIELD-IMPORTANCE-08: POST /api/system/ddl/extend-column 含 importance +
 *       affectsCalculation 字段后, basic_data_attribute 写入对应 importance_level /
 *       affects_calculation</li>
 *   <li>QAPP-WD-REQ-04: 同一报价单已有 PENDING 撤回请求时, 再次 POST
 *       /quotations/{id}/withdraw-request 返回 400</li>
 * </ul>
 *
 * <p>RBAC is disabled in test profile (cpq.security.rbac.enabled=false).
 * MD-FIELD-IMP-05 verifies endpoint reachability and the @RoleAllowed(SYSTEM_ADMIN)
 * annotation at reflection level (same approach as ElementPriceResourceTest T7).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MiscEdgeTest — MD-FIELD-IMP-05 / DDL-FIELD-IMPORTANCE-08 / QAPP-WD-REQ-04")
class MiscEdgeTest {

    private static final String BASE_BDC = "/api/cpq/basic-data-config";
    private static final String BASE_DDL = "/api/system/ddl";
    private static final String BASE_Q   = "/api/cpq/quotations";

    /** Stable UUID for admin user seeded once */
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000001");

    /** Stable UUID for test customer (quotation FK) */
    private static final UUID TEST_CUSTOMER_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000002");

    /** Stable UUID for test quotation used in QAPP-WD-REQ-04 */
    private static final UUID TEST_QUOTATION_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000003");

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static boolean seeded = false;

    /** DDL test column name — cleaned up in @AfterEach if set */
    private String ddlAddedColumn = null;

    // =========================================================================
    // Setup / teardown
    // =========================================================================

    @BeforeEach
    void seedOnce() throws Exception {
        if (seeded) return;

        utx.begin();
        em.joinTransaction();

        // Admin user
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, " +
                "is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'misc-admin', 'Misc Admin', 'misc-admin@test.com', 'hash', " +
                "'SYSTEM_ADMIN', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", ADMIN_USER_ID)
                .executeUpdate();

        // Customer (needed for quotation FK)
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, " +
                "created_at, updated_at) " +
                "VALUES (:id, 'Misc Test Customer', 'MISC-CUST-01', 'STANDARD', 0, 'ACTIVE', " +
                "NOW(), NOW()) ON CONFLICT (id) DO NOTHING")
                .setParameter("id", TEST_CUSTOMER_ID)
                .executeUpdate();

        // Quotation for QAPP-WD-REQ-04 — status APPROVED (required by requestWithdraw)
        em.createNativeQuery(
                "INSERT INTO quotation(id, quotation_number, customer_id, name, sales_rep_id, " +
                "status, total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                "tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, 'QT-MISC-WD-TEST', :cid, 'Misc WD Test Quotation', :uid, " +
                "'APPROVED', 0, 0, 100, 100, 0, 0, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", TEST_QUOTATION_ID)
                .setParameter("cid", TEST_CUSTOMER_ID)
                .setParameter("uid", ADMIN_USER_ID)
                .executeUpdate();

        utx.commit();
        seeded = true;
    }

    @AfterEach
    void cleanupDdlColumn() throws Exception {
        if (ddlAddedColumn == null) return;
        final String col = ddlAddedColumn;
        ddlAddedColumn = null;
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("ALTER TABLE mat_part DROP COLUMN IF EXISTS " + col)
                .executeUpdate();
        em.createNativeQuery(
                "DELETE FROM basic_data_attribute WHERE variable_code = :vc")
                .setParameter("vc", "mat_part." + col)
                .executeUpdate();
        utx.commit();
    }

    // =========================================================================
    // MD-FIELD-IMP-05
    // 仅 SYSTEM_ADMIN 可改字段重要性。
    //
    // 实现: RBAC 在 test profile 下已关闭 (cpq.security.rbac.enabled=false)。
    //   1. 反射验证 @RoleAllowed({"SYSTEM_ADMIN"}) 注解存在于端点方法。
    //   2. 端点烟雾: 用合法 importanceLevel=CRITICAL 调 PATCH, 断言 200 或 4xx(非 500)。
    //      (404 = attribute 不存在时的正常业务错误; 400 = 非法值; 200 = 成功)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("MD-FIELD-IMP-05: PATCH /attributes/{id}/importance 有 @RoleAllowed(SYSTEM_ADMIN) 注解 + 端点可达")
    void mdFieldImp_05_annotationPresentAndEndpointReachable() throws Exception {
        // --- 1. Reflection: verify @RoleAllowed(SYSTEM_ADMIN) on the PATCH method ---
        java.lang.reflect.Method method =
                com.cpq.basicdata.resource.BasicDataConfigResource.class.getMethod(
                        "updateAttributeImportance",
                        UUID.class,
                        com.cpq.basicdata.resource.BasicDataConfigResource.UpdateAttributeImportanceRequest.class);

        com.cpq.common.security.RoleAllowed anno =
                method.getAnnotation(com.cpq.common.security.RoleAllowed.class);

        assertNotNull(anno,
                "PATCH /attributes/{id}/importance must have @RoleAllowed");
        assertArrayEquals(new String[]{"SYSTEM_ADMIN"}, anno.value(),
                "PATCH /attributes/{id}/importance must be restricted to SYSTEM_ADMIN");

        // --- 2. Smoke: call with a random (non-existent) id — expect 404 or 400, NOT 500 ---
        UUID nonExistentId = UUID.randomUUID();

        int sc = given()
                .contentType(ContentType.JSON)
                .body("{\"importanceLevel\": \"CRITICAL\", \"affectsCalculation\": true}")
            .when()
                .patch(BASE_BDC + "/attributes/" + nonExistentId + "/importance")
            .then()
                .extract().statusCode();

        // 404 = attribute not found (business rule); 400 = validation error; 200 = success
        // Any of these is correct — just must NOT be 500
        assertTrue(sc == 200 || sc == 404 || sc == 400,
                "PATCH /importance must return 200/404/400, not 500. Got: " + sc);

        // --- 3. Smoke with a real attribute: create sheet + attribute, then PATCH importance ---
        // Create sheet
        String sheetId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"sheetName\": \"MD-IMP-05 Sheet " + UUID.randomUUID().toString().substring(0, 8) +
                      "\", \"joinColumns\": []}")
                .post(BASE_BDC + "/sheets")
                .then()
                .statusCode(200)
                .extract().path("data.id");

        // Create attribute
        String attrId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "configId": "%s",
                          "columnLetter": "A",
                          "columnTitle": "MD IMP 05 Field",
                          "variableCode": "md_imp_05_%s",
                          "variableLabel": "MD IMP 05",
                          "importanceLevel": "NORMAL",
                          "affectsCalculation": false
                        }
                        """.formatted(sheetId, UUID.randomUUID().toString().replace("-", "").substring(0, 8)))
                .post(BASE_BDC + "/attributes")
                .then()
                .statusCode(200)
                .extract().path("data.id");

        // PATCH importance — must succeed (200) and update the fields
        given()
                .contentType(ContentType.JSON)
                .body("{\"importanceLevel\": \"CRITICAL\", \"affectsCalculation\": true}")
            .when()
                .patch(BASE_BDC + "/attributes/" + attrId + "/importance")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.importanceLevel", equalTo("CRITICAL"))
                .body("data.affectsCalculation", equalTo(true));
    }

    // =========================================================================
    // DDL-FIELD-IMPORTANCE-08
    // DDL 扩列时同步写入字段重要性。
    //
    // POST /api/system/ddl/extend-column 含 importance=IMPORTANT + affectsCalculation=true
    // 执行后验证 basic_data_attribute 表中 importance_level=IMPORTANT + affects_calculation=true
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("DDL-FIELD-IMPORTANCE-08: extend-column 含 importance/affectsCalculation → basic_data_attribute 写入正确")
    void ddlFieldImportance_08_extendColumnWritesImportance() throws Exception {
        ddlAddedColumn = "misc_imp_t2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);

        // Release DDL lock to prevent 423 from previous test runs
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' " +
                "WHERE lock_key = 'global'")
                .executeUpdate();
        utx.commit();

        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "%s",
                  "dataType": "VARCHAR(64)",
                  "defaultValue": "",
                  "importance": "IMPORTANT",
                  "affectsCalculation": true
                }
                """.formatted(ddlAddedColumn);

        given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post(BASE_DDL + "/extend-column")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("SUCCESS"))
                // Response DTO also reflects the importance fields
                .body("data.importance", equalTo("IMPORTANT"))
                .body("data.affectsCalculation", equalTo(true));

        // Verify basic_data_attribute row was written with correct importance fields
        final String variableCode = "mat_part." + ddlAddedColumn;
        utx.begin();
        em.joinTransaction();
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT importance_level, affects_calculation FROM basic_data_attribute " +
                "WHERE variable_code = :vc")
                .setParameter("vc", variableCode)
                .getSingleResult();
        utx.commit();

        assertNotNull(row, "basic_data_attribute row must exist after extend-column");
        assertEquals("IMPORTANT", row[0],
                "importance_level must be IMPORTANT");
        assertEquals(Boolean.TRUE, row[1],
                "affects_calculation must be true");
    }

    // =========================================================================
    // QAPP-WD-REQ-04
    // 同一报价单仅一个 PENDING 撤回请求。
    //
    // 实现:
    //   1. 通过 EntityManager persist 一个 PENDING QuotationWithdrawRequest
    //   2. 调 POST /quotations/{id}/withdraw-request 期望 400 ("已有待处理的撤回请求")
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("QAPP-WD-REQ-04: 同一报价单已有 PENDING 撤回请求时, 再次申请返回 400")
    void qappWdReq_04_duplicatePendingWithdrawReturns400() throws Exception {
        // Clean existing withdraw requests for this quotation to start fresh
        utx.begin();
        em.joinTransaction();

        em.createNativeQuery(
                "DELETE FROM quotation_withdraw_request WHERE quotation_id = :qid")
                .setParameter("qid", TEST_QUOTATION_ID)
                .executeUpdate();

        // Also ensure quotation status is APPROVED (required by requestWithdraw service)
        em.createNativeQuery(
                "UPDATE quotation SET status = 'APPROVED' WHERE id = :id")
                .setParameter("id", TEST_QUOTATION_ID)
                .executeUpdate();

        // Persist a PENDING QuotationWithdrawRequest directly via EntityManager
        QuotationWithdrawRequest existing = new QuotationWithdrawRequest();
        existing.quotationId = TEST_QUOTATION_ID;
        existing.requestedBy = ADMIN_USER_ID;
        existing.reason = "先期撤回原因 (QAPP-WD-REQ-04)";
        existing.status = "PENDING";
        em.persist(existing);
        em.flush();

        utx.commit();

        // Now call POST /quotations/{id}/withdraw-request — should return 400
        given()
                .contentType(ContentType.JSON)
                .body("{\"reason\": \"重复撤回申请\"}")
            .when()
                .post(BASE_Q + "/" + TEST_QUOTATION_ID + "/withdraw-request")
            .then()
                .statusCode(400)
                .body("message", containsString("已有待处理的撤回请求"));
    }
}
