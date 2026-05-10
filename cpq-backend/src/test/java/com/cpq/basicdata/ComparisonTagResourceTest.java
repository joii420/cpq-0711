package com.cpq.basicdata;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * ComparisonTag REST API tests — TDD Chapter 5 (TAG-LIST-01 through TAG-CUSTOM-04).
 *
 * Test environment: cpq.security.rbac.enabled=false, so RBAC filter is bypassed.
 * All endpoints are accessible without a session in tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComparisonTagResourceTest {

    private static final String BASE = "/api/cpq/comparison-tags";

    @Inject
    EntityManager em;

    /** Remove any custom tags created by this test suite to keep tests idempotent. */
    @BeforeEach
    @Transactional
    void cleanCustomTestTags() {
        em.createNativeQuery(
                "DELETE FROM comparison_tag WHERE is_builtin = FALSE AND code LIKE 'CUSTOM_TEST_%'"
        ).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // TAG-LIST-01: GET ?status=ACTIVE returns >= 11 builtin tags
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("TAG-LIST-01: GET ?status=ACTIVE 返回 >= 11 条内置标签，含全部必需 code")
    void tagList01_activeTagsContainAllBuiltins() {
        RestAssured.given()
                .queryParam("status", "ACTIVE")
                .when()
                    .get(BASE)
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.size()", greaterThanOrEqualTo(11))
                    .body("data.code", hasItems(
                            "MATERIAL_COST_AG",
                            "MATERIAL_COST_CU",
                            "MATERIAL_COST_TOTAL",
                            "PROCESSING_COST",
                            "LABOR_COST",
                            "SETUP_COST",
                            "OVERHEAD_COST",
                            "PACKAGING_COST",
                            "CUSTOM_COST",
                            "UNIT_TOTAL_COST",
                            "TOTAL"
                    ));
    }

    // -----------------------------------------------------------------------
    // TAG-BUILTIN-DEL-02: DELETE builtin tag must return 400 with message
    //   containing "内置" or "builtin"
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("TAG-BUILTIN-DEL-02: DELETE 内置标签返回 400, message 含[内置]或[builtin]")
    void tagBuiltinDel02_deleteBuiltinTagReturns400() {
        // Fetch the id of a known builtin tag (MATERIAL_COST_AG)
        String builtinId = RestAssured.given()
                .queryParam("status", "ACTIVE")
                .when()
                    .get(BASE)
                .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getString("data.find { it.code == 'MATERIAL_COST_AG' }.id");

        Assertions.assertNotNull(builtinId, "Builtin tag MATERIAL_COST_AG must exist in DB seed");

        RestAssured.given()
                .when()
                    .delete(BASE + "/" + builtinId)
                .then()
                    .statusCode(400)
                    .body("code", equalTo(400))
                    .body("message", anyOf(
                            containsStringIgnoringCase("内置"),
                            containsStringIgnoringCase("builtin")
                    ));
    }

    // -----------------------------------------------------------------------
    // TAG-BUILTIN-CODE-03: PUT builtin tag with a different code must return 400
    //   with message containing "内置" or "code"
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("TAG-BUILTIN-CODE-03: PUT 修改内置标签 code 返回 400, message 含[内置]或[code]")
    void tagBuiltinCode03_changeCodeOfBuiltinReturns400() {
        // Fetch the id of a known builtin tag (TOTAL)
        String builtinId = RestAssured.given()
                .queryParam("status", "ACTIVE")
                .when()
                    .get(BASE)
                .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getString("data.find { it.code == 'TOTAL' }.id");

        Assertions.assertNotNull(builtinId, "Builtin tag TOTAL must exist in DB seed");

        String body = """
                {
                  "code": "TOTAL_RENAMED",
                  "label": "总价(改名)",
                  "groupName": "汇总"
                }
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .put(BASE + "/" + builtinId)
                .then()
                    .statusCode(400)
                    .body("code", equalTo(400))
                    .body("message", anyOf(
                            containsStringIgnoringCase("内置"),
                            containsStringIgnoringCase("builtin"),
                            containsStringIgnoringCase("code")
                    ));
    }

    // -----------------------------------------------------------------------
    // TAG-CUSTOM-04: Custom tag POST / PUT / DELETE full lifecycle — all 200
    // -----------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("TAG-CUSTOM-04: 自定义标签 POST/PUT/DELETE 全流程返回 200")
    void tagCustom04_customTagFullLifecycle() {
        String uniqueCode = "CUSTOM_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        // --- POST: create custom tag ---
        String createBody = String.format("""
                {
                  "code": "%s",
                  "label": "测试自定义成本",
                  "groupName": "测试分组",
                  "groupSortOrder": 99,
                  "tagSortOrder": 1,
                  "status": "ACTIVE",
                  "description": "由 TAG-CUSTOM-04 测试创建"
                }
                """, uniqueCode);

        String createdId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .when()
                    .post(BASE)
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.code", equalTo(uniqueCode))
                    .body("data.label", equalTo("测试自定义成本"))
                    .body("data.isBuiltin", equalTo(false))
                    .body("data.status", equalTo("ACTIVE"))
                    .extract()
                    .jsonPath()
                    .getString("data.id");

        Assertions.assertNotNull(createdId, "Created tag must have an id");

        // --- GET by id: verify persisted correctly ---
        RestAssured.given()
                .when()
                    .get(BASE + "/" + createdId)
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.id", equalTo(createdId))
                    .body("data.code", equalTo(uniqueCode));

        // --- PUT: update label and description (code unchanged) ---
        String updateBody = String.format("""
                {
                  "code": "%s",
                  "label": "测试自定义成本(已更新)",
                  "groupName": "测试分组",
                  "description": "已更新"
                }
                """, uniqueCode);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(updateBody)
                .when()
                    .put(BASE + "/" + createdId)
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.label", equalTo("测试自定义成本(已更新)"))
                    .body("data.description", equalTo("已更新"))
                    .body("data.code", equalTo(uniqueCode));

        // --- DELETE: custom tag must be physically deleted ---
        RestAssured.given()
                .when()
                    .delete(BASE + "/" + createdId)
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200));

        // --- Verify deleted: GET by id should return 404 ---
        RestAssured.given()
                .when()
                    .get(BASE + "/" + createdId)
                .then()
                    .statusCode(404);
    }
}
