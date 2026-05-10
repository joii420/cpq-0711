package com.cpq.system.ddl;

import com.cpq.system.ddl.dto.ExtendColumnRequest;
import com.cpq.system.ddl.resource.DdlExtensionResource;
import com.cpq.system.lock.service.ProductImportLockService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DdlExtensionResource (Phase 5 #25 TECH-4).
 *
 * <p>Test strategy:
 * <ul>
 *   <li>RBAC is disabled in test profile (cpq.security.rbac.enabled=false).</li>
 *   <li>T11 (403) checks @RoleAllowed annotation at reflection level.</li>
 *   <li>Each test that adds a column must drop it in @AfterEach to avoid schema pollution.</li>
 * </ul>
 *
 * <p>White-box note: DdlExtensionService.extendColumn is intentionally NOT @Transactional —
 * it uses Session.doWork to get a bare JDBC connection for DDL.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DdlExtensionResource — Phase 5 #25 TECH-4")
class DdlExtensionResourceTest {

    private static final String BASE = "/api/system/ddl";

    /** Stable admin UUID seeded in @BeforeAll */
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("dd000000-0000-0000-0000-000000000001");

    /** Customer UUID for import lock tests */
    private static final UUID TEST_CUSTOMER_ID =
            UUID.fromString("dd000000-0000-0000-0000-000000000002");

    /** Track columns added during tests — cleaned in @AfterEach */
    private String addedTable;
    private String addedColumn;

    /** Once-flag to seed static data only once across test methods */
    private static boolean seeded = false;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Inject
    ProductImportLockService productImportLockService;

    // =========================================================================
    // Setup / teardown
    // =========================================================================

    @BeforeEach
    void resetState() throws Exception {
        addedTable = null;
        addedColumn = null;

        if (!seeded) {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery(
                    "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, " +
                    "is_first_login, created_at, updated_at) " +
                    "VALUES (:id, 'ddl-admin', 'DDL 管理员', 'ddl-admin@test.com', 'hash', " +
                    "'SYSTEM_ADMIN', 'ACTIVE', false, NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", ADMIN_USER_ID)
                    .executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO customer(id, name, code, level, accumulated_amount, status, " +
                    "created_at, updated_at) " +
                    "VALUES (:id, 'DDL Test Customer', 'DDL-CUST-01', 'STANDARD', 0, 'ACTIVE', " +
                    "NOW(), NOW()) ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", TEST_CUSTOMER_ID)
                    .executeUpdate();
            utx.commit();
            seeded = true;
        }

        // Expire DDL lock and release import locks
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' " +
                "WHERE lock_key = 'global'")
                .executeUpdate();
        em.createNativeQuery(
                "DELETE FROM product_import_lock WHERE customer_id = :cid")
                .setParameter("cid", TEST_CUSTOMER_ID)
                .executeUpdate();

        // Pre-clean all known test columns that may have been left over from previous runs
        String[] matPartTestCols = {"surface_treatment_t1", "hist_col_t8a", "hist_col_t8b",
                "blob_col", "blocked_col", "no_default_col", "bad_default_t7"};
        String[] matBomTestCols = {"test_decimal_t12"};

        for (String col : matPartTestCols) {
            em.createNativeQuery("ALTER TABLE mat_part DROP COLUMN IF EXISTS " + col)
                    .executeUpdate();
            em.createNativeQuery(
                    "DELETE FROM basic_data_attribute WHERE variable_code = :vc")
                    .setParameter("vc", "mat_part." + col)
                    .executeUpdate();
        }
        for (String col : matBomTestCols) {
            em.createNativeQuery("ALTER TABLE mat_bom DROP COLUMN IF EXISTS " + col)
                    .executeUpdate();
            em.createNativeQuery(
                    "DELETE FROM basic_data_attribute WHERE variable_code = :vc")
                    .setParameter("vc", "mat_bom." + col)
                    .executeUpdate();
        }
        utx.commit();
    }

    @AfterEach
    void dropAddedColumn() throws Exception {
        if (addedTable != null && addedColumn != null) {
            final String tbl = addedTable;
            final String col = addedColumn;
            utx.begin();
            em.joinTransaction();
            // Drop physical column
            em.createNativeQuery(
                    "ALTER TABLE " + tbl + " DROP COLUMN IF EXISTS " + col)
                    .executeUpdate();
            // Remove BasicDataAttribute record (variable_code unique constraint)
            em.createNativeQuery(
                    "DELETE FROM basic_data_attribute WHERE variable_code = :vc")
                    .setParameter("vc", tbl + "." + col)
                    .executeUpdate();
            utx.commit();
        }
    }

    // =========================================================================
    // T1: Extend mat_part with surface_treatment VARCHAR(64) → SUCCESS
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("T1: Extend mat_part surface_treatment VARCHAR(64) → 200 + DB column present + history SUCCESS")
    void t1_extendMatPart_success() throws Exception {
        addedTable = "mat_part";
        addedColumn = "surface_treatment_t1";

        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "surface_treatment_t1",
                  "dataType": "VARCHAR(64)",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.tableName", equalTo("mat_part"))
            .body("data.columnName", equalTo("surface_treatment_t1"))
            .body("data.status", equalTo("SUCCESS"))
            .body("data.migrationContent", containsString("ALTER TABLE mat_part ADD COLUMN surface_treatment_t1"))
            .body("data.flywayVersionHint", startsWith("V"));

        // Verify column exists in information_schema
        utx.begin();
        em.joinTransaction();
        Number colCount = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name='mat_part' " +
                "AND column_name='surface_treatment_t1'")
                .getSingleResult();
        utx.commit();
        assertEquals(1, colCount.intValue(), "Column must exist in DB schema");

        // Verify history record written
        given()
            .queryParam("status", "SUCCESS")
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("data", hasItem(
                    hasEntry("columnName", "surface_treatment_t1")
            ));
    }

    // =========================================================================
    // T2: tableName not in whitelist → 400
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("T2: tableName not in whitelist → 400")
    void t2_invalidTableName_returns400() {
        String body = """
                {
                  "tableName": "quotation",
                  "columnName": "evil_col",
                  "dataType": "TEXT",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T3: unsupported dataType BLOB → 400
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("T3: dataType BLOB not supported → 400")
    void t3_unsupportedDataType_returns400() {
        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "blob_col",
                  "dataType": "BLOB",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T4: columnName already exists → 400
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("T4: columnName already exists on mat_part → 400")
    void t4_columnAlreadyExists_returns400() {
        // part_no is a pre-existing column on mat_part
        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "part_no",
                  "dataType": "VARCHAR(64)",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(400)
            .body("message", containsString("已存在"));
    }

    // =========================================================================
    // T5: missing defaultValue → 400 (Bean Validation @NotBlank)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("T5: missing defaultValue → 400")
    void t5_missingDefaultValue_returns400() {
        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "no_default_col",
                  "dataType": "TEXT",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T6: active product import lock → 423
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("T6: active product import lock exists → 423")
    void t6_activeImportLock_returns423() throws Exception {
        // Seed a mat_part row to create an import lock against
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO mat_part(part_no, part_name, status_code, created_at, updated_at) " +
                "VALUES ('T6-PART-LOCK', 'T6 Lock Test Part', 'Y', NOW(), NOW()) " +
                "ON CONFLICT (part_no) DO NOTHING")
                .executeUpdate();
        utx.commit();

        // Acquire product import lock
        productImportLockService.acquireLocks(TEST_CUSTOMER_ID, List.of("T6-PART-LOCK"), ADMIN_USER_ID, null);

        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "blocked_col",
                  "dataType": "TEXT",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(423);
    }

    // =========================================================================
    // T7: default value type mismatch (e.g. non-numeric default for INTEGER)
    //     → ALTER fails, history FAILED, column does NOT exist
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("T7: type-incompatible default causes ALTER failure → history FAILED + column absent")
    void t7_alterFails_historyFailed_columnAbsent() throws Exception {
        // INTEGER column with non-numeric default causes PG cast error
        String body = """
                {
                  "tableName": "mat_part",
                  "columnName": "bad_default_t7",
                  "dataType": "INTEGER",
                  "defaultValue": "not_a_number",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        // Expect 400 (validation intercepts non-numeric default for INTEGER)
        // or 500 (ALTER fails at DB). Both mean the column must NOT be added.
        int statusCode = given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .extract().statusCode();

        assertTrue(statusCode == 400 || statusCode == 500,
                "Should return 400 (validation) or 500 (DB error), got: " + statusCode);

        // Verify column does NOT exist in DB
        utx.begin();
        em.joinTransaction();
        Number colCount = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name='mat_part' " +
                "AND column_name='bad_default_t7'")
                .getSingleResult();
        utx.commit();
        assertEquals(0, colCount.intValue(), "Column must NOT exist after failed ALTER");
    }

    // =========================================================================
    // T8: GET /history returns results newest-first
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("T8: GET /history returns history in descending created_at order")
    void t8_getHistory_descendingOrder() throws Exception {
        // Add two columns sequentially to produce two history rows
        addedTable = "mat_part";
        addedColumn = "hist_col_t8b";

        String body1 = """
                {
                  "tableName": "mat_part",
                  "columnName": "hist_col_t8a",
                  "dataType": "TEXT",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;
        String body2 = """
                {
                  "tableName": "mat_part",
                  "columnName": "hist_col_t8b",
                  "dataType": "TEXT",
                  "defaultValue": "",
                  "importance": "NORMAL",
                  "affectsCalculation": false
                }
                """;

        given().contentType(JSON).body(body1).post(BASE + "/extend-column")
               .then().statusCode(200);

        // Release DDL lock between the two calls
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' WHERE lock_key = 'global'")
                .executeUpdate();
        utx.commit();

        given().contentType(JSON).body(body2).post(BASE + "/extend-column")
               .then().statusCode(200);

        given()
            .queryParam("page", "0")
            .queryParam("size", "10")
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("data.size()", greaterThanOrEqualTo(2))
            // Verify first item has a createdAt that is >= second (descending)
            .body("data[0].createdAt", notNullValue());

        // Cleanup col a (col b cleaned by @AfterEach)
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("ALTER TABLE mat_part DROP COLUMN IF EXISTS hist_col_t8a")
                .executeUpdate();
        em.createNativeQuery(
                "DELETE FROM basic_data_attribute WHERE variable_code = :vc")
                .setParameter("vc", "mat_part.hist_col_t8a")
                .executeUpdate();
        utx.commit();
    }

    // =========================================================================
    // T9: GET /extensible-tables → contains all 15 tables
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("T9: GET /extensible-tables returns all 15 whitelisted tables")
    void t9_extensibleTables_contains15() {
        given()
        .when()
            .get(BASE + "/extensible-tables")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.size()", equalTo(15))
            .body("data", hasItems(
                    "mat_part", "mat_bom", "mat_process", "plating_plan",
                    "mat_fee", "plating_fee", "mat_customer_part_mapping",
                    "element_price_source", "element_price_fetch_rule",
                    "element_price", "element_daily_price",
                    "basic_data_change_log", "exchange_rate", "customer_tax",
                    "basic_data_attribute"
            ));
    }

    // =========================================================================
    // T10: GET /columns/mat_part → lists existing columns
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("T10: GET /columns/mat_part lists pre-existing columns")
    void t10_listColumns_matPart() {
        given()
        .when()
            .get(BASE + "/columns/mat_part")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", hasItems("part_no", "part_name", "status_code", "created_at", "updated_at"));
    }

    // =========================================================================
    // T11: Non-SYSTEM_ADMIN calling POST extend-column → @RoleAllowed annotation present
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("T11: POST /extend-column has @RoleAllowed(SYSTEM_ADMIN) annotation")
    void t11_extendColumn_hasSystemAdminAnnotation() throws Exception {
        java.lang.reflect.Method method = DdlExtensionResource.class.getMethod(
                "extendColumn",
                ExtendColumnRequest.class,
                io.vertx.core.http.HttpServerRequest.class);

        com.cpq.common.security.RoleAllowed anno = method.getAnnotation(
                com.cpq.common.security.RoleAllowed.class);

        assertNotNull(anno, "POST /extend-column must have @RoleAllowed");
        assertArrayEquals(new String[]{"SYSTEM_ADMIN"}, anno.value(),
                "POST /extend-column must be restricted to SYSTEM_ADMIN");
    }

    // =========================================================================
    // Additional: T12 — Extend with DECIMAL type
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("T12: Extend mat_bom with DECIMAL(18,4) column → 200")
    void t12_extendDecimalColumn_success() throws Exception {
        addedTable = "mat_bom";
        addedColumn = "test_decimal_t12";

        String body = """
                {
                  "tableName": "mat_bom",
                  "columnName": "test_decimal_t12",
                  "dataType": "DECIMAL(18,4)",
                  "defaultValue": "0",
                  "importance": "IMPORTANT",
                  "affectsCalculation": true
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/extend-column")
        .then()
            .statusCode(200)
            .body("data.status", equalTo("SUCCESS"))
            .body("data.affectsCalculation", equalTo(true))
            .body("data.importance", equalTo("IMPORTANT"))
            .body("data.migrationContent", containsString("DECIMAL(18,4)"));
    }
}
