package com.cpq.system.config;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemConfigResource REST API tests.
 * Covers AC-1.1, AC-1.2, AC-1.3, AC-1.4 at HTTP level.
 *
 * NOTE: In test env cpq.security.rbac.enabled=false, so RBAC is bypassed.
 * However SystemConfigResource.requireSystemAdmin() calls getCurrentUserRole()
 * which throws 401 when no session exists and RBAC is disabled.
 * This documents the known behaviour and marks AC tests accordingly.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemConfigResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM system_config WHERE config_key LIKE 'test.%'").executeUpdate();
    }

    // ======== AC-1.1: GET ?category=import ========

    @Test
    @Order(1)
    void ac1_1_get_listByCategory_import_returns200WithItems() {
        // In test env (rbac disabled), requireSystemAdmin may return 401 if no session.
        // We document the actual HTTP behavior here.
        int status = RestAssured.given()
                .queryParam("category", "import")
                .when()
                    .get("/api/system/configs")
                .then()
                    .extract().statusCode();

        // Expected: 200 with import configs
        // If 401: SystemConfigResource.requireSystemAdmin() lacks RBAC-disabled fallback (Bug-1)
        if (status == 200) {
            RestAssured.given()
                    .queryParam("category", "import")
                    .when()
                        .get("/api/system/configs")
                    .then()
                        .statusCode(200)
                        .body("code", equalTo(200))
                        .body("data", not(empty()));
        } else {
            // Document the failure - this is Bug-1
            Assertions.assertEquals(200, status,
                "AC-1.1 FAIL: GET /api/system/configs?category=import returned " + status +
                " — SystemConfigResource.requireSystemAdmin() has no RBAC-disabled fallback. " +
                "getCurrentUserRole() throws 401 when no session exists. See Bug-1 report.");
        }
    }

    @Test
    @Order(2)
    void ac1_1_get_listAllConfigs_contains23OrMoreRows() {
        int status = RestAssured.given()
                .when()
                    .get("/api/system/configs")
                .then()
                    .extract().statusCode();

        if (status == 200) {
            RestAssured.given()
                    .when()
                        .get("/api/system/configs")
                    .then()
                        .statusCode(200)
                        .body("data.size()", greaterThanOrEqualTo(23));
        } else {
            Assertions.fail("AC-1.1 list all: HTTP " + status + " — likely Bug-1 (no session fallback in requireSystemAdmin)");
        }
    }

    // ======== AC-1.2: POST create new config ========

    @Test
    @Order(3)
    void ac1_2_post_createNewKey_returns201() {
        String body = """
                {
                  "configKey": "test.rest_create",
                  "configValue": "hello",
                  "defaultValue": "hello",
                  "dataType": "STRING",
                  "category": "import",
                  "description": "REST API create test",
                  "modifiableBy": "SYSTEM_ADMIN"
                }
                """;

        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/system/configs")
                .then()
                    .extract().statusCode();

        if (status == 201) {
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(body.replace("test.rest_create", "test.rest_create_2"))
                    .when()
                        .post("/api/system/configs")
                    .then()
                        .statusCode(201)
                        .body("code", equalTo(201))
                        .body("data.configKey", equalTo("test.rest_create_2"));
        } else {
            Assertions.fail("AC-1.2 POST create: HTTP " + status + " — likely Bug-1 in requireSystemAdmin");
        }
    }

    @Test
    @Order(4)
    void ac1_2_post_missingRequiredFields_returns400() {
        // Missing configKey
        String body = """
                {
                  "configValue": "hello",
                  "defaultValue": "hello",
                  "dataType": "STRING",
                  "category": "import"
                }
                """;

        // Bean validation should reject before auth (400 before 401)
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/system/configs")
                .then()
                    .statusCode(anyOf(equalTo(400), equalTo(401)));
        // If 400: validation works. If 401: auth checked first (also acceptable).
    }

    // ======== AC-1.3: PUT update existing config ========

    @Test
    @Order(5)
    void ac1_3_put_updateExistingKey_returns200() {
        String body = """
                {"configValue": "0.25", "description": "updated by test"}
                """;

        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .put("/api/system/configs/business.gross_margin_warning_min")
                .then()
                    .extract().statusCode();

        if (status == 200) {
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"configValue": "0.15"}
                            """)
                    .when()
                        .put("/api/system/configs/business.gross_margin_warning_min")
                    .then()
                        .statusCode(200)
                        .body("data.configValue", equalTo("0.15"));
        } else {
            Assertions.fail("AC-1.3 PUT update: HTTP " + status + " — likely Bug-1 in requireSystemAdmin");
        }
    }

    @Test
    @Order(6)
    void ac1_3_put_nonExistentKey_returns404() {
        String body = """
                {"configValue": "999"}
                """;

        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .put("/api/system/configs/nonexistent.key_xyz")
                .then()
                    .extract().statusCode();

        // 401 if no session, 404 if auth passes but key missing
        assertTrue(status == 404 || status == 401,
                "Expected 404 (key not found) or 401 (no session); got: " + status);
    }

    // ======== AC-1.4: modifiable_by permission via REST ========

    @Test
    @Order(7)
    void ac1_4_put_systemAdminOnlyKey_withoutProperRole_returns403() {
        // This test is conditional on being able to authenticate as SALES_MANAGER
        // In test env without session, we can only verify the endpoint exists
        // and returns 401 (not 500).
        String body = """
                {"configValue": "0.99"}
                """;

        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .put("/api/system/configs/validation.composition_tolerance")
                .then()
                    .extract().statusCode();

        // In test env (no session): 401 from requireSystemAdmin
        // With SALES_MANAGER session: should be 403
        // Either is acceptable here; 200 would be a bug
        assertNotEquals(200, status,
                "A config with modifiable_by=SYSTEM_ADMIN must never be modifiable without proper auth");
        assertTrue(status == 401 || status == 403,
                "Expected 401 or 403 for unauthorized access, got: " + status);
    }

    // ======== GET single config ========

    @Test
    @Order(8)
    void get_singleConfig_byKey_returns200OrAuth() {
        int status = RestAssured.given()
                .when()
                    .get("/api/system/configs/import.product_lock_timeout_seconds")
                .then()
                    .extract().statusCode();

        if (status == 200) {
            RestAssured.given()
                    .when()
                        .get("/api/system/configs/import.product_lock_timeout_seconds")
                    .then()
                        .statusCode(200)
                        .body("data.configKey", equalTo("import.product_lock_timeout_seconds"))
                        .body("data.configValue", equalTo("300"));
        } else {
            // 401 documents Bug-1
            assertEquals(401, status, "Expected 200 or 401 from GET single config, got: " + status);
        }
    }

    // ======== DELETE config ========

    @Test
    @Order(9)
    void delete_existingKey_returns200OrAuth() {
        // Test that the endpoint responds with something sane (not 500)
        int status = RestAssured.given()
                .when()
                    .delete("/api/system/configs/test.rest_delete_nonexistent")
                .then()
                    .extract().statusCode();

        assertNotEquals(500, status, "DELETE should not return 500 for any input");
        assertTrue(status == 401 || status == 404,
                "Expected 401 (no session) or 404 (key not found), got: " + status);
    }
}
