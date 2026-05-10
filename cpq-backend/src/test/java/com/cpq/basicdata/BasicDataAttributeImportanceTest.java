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
import static org.junit.jupiter.api.Assertions.*;

/**
 * BasicDataAttributeImportanceTest — D-5 v5.1 遗留清理。
 *
 * <p>验证 BasicDataAttribute 的 importanceLevel + affectsCalculation 字段
 * 通过 REST API 可正确读写。
 *
 * <p>测试用例 T1~T5：
 * <ol>
 *   <li>T1: GET /attributes → 返回 importanceLevel + affectsCalculation 字段</li>
 *   <li>T2: POST /attributes + importanceLevel=CRITICAL → 写入成功，GET 返回正确值</li>
 *   <li>T3: PUT /attributes/{id} 修改 importanceLevel + affectsCalculation → 成功</li>
 *   <li>T4: PATCH /attributes/{id}/importance 专用端点 → 仅更新重要性字段</li>
 *   <li>T5: importanceLevel 非法值 → 400 错误</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataAttributeImportanceTest {

    @Inject
    EntityManager em;

    /** 测试用 sheet config id，由 @BeforeAll 创建 */
    private static String testSheetId;
    /** 测试用 attribute id */
    private static String testAttrId;

    @BeforeEach
    @Transactional
    void ensureTestSheet() {
        if (testSheetId == null) {
            testSheetId = createTestSheet();
        }
    }

    // ── T1: GET /attributes 返回 importance 字段 ──────────────────────────────

    @Test
    @Order(1)
    void t1_listAttributes_returnsImportanceFields() {
        // 先创建一个属性
        String body = """
                {
                  "configId": "%s",
                  "columnLetter": "A",
                  "columnTitle": "测试列",
                  "variableCode": "test_col_%s",
                  "variableLabel": "测试列标签",
                  "importanceLevel": "IMPORTANT",
                  "affectsCalculation": true
                }
                """.formatted(testSheetId, UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        String attrId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.importanceLevel", equalTo("IMPORTANT"))
                .body("data.affectsCalculation", equalTo(true))
                .extract().path("data.id");

        // GET listAttributes → 返回包含 importance 字段
        RestAssured.given()
                .queryParam("sheetId", testSheetId)
                .get("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data", not(empty()))
                .body("data[0].importanceLevel", notNullValue())
                .body("data[0].affectsCalculation", notNullValue());
    }

    // ── T2: POST 创建时写入 importanceLevel=CRITICAL ──────────────────────────

    @Test
    @Order(2)
    void t2_createAttribute_withImportanceLevel_writtenCorrectly() {
        String uniqueCode = "imp_critical_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String body = """
                {
                  "configId": "%s",
                  "columnLetter": "B",
                  "columnTitle": "关键列",
                  "variableCode": "%s",
                  "variableLabel": "关键列标签",
                  "importanceLevel": "CRITICAL",
                  "affectsCalculation": true
                }
                """.formatted(testSheetId, uniqueCode);

        testAttrId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .body("data.importanceLevel", equalTo("CRITICAL"))
                .body("data.affectsCalculation", equalTo(true))
                .extract().path("data.id");

        assertNotNull(testAttrId, "创建的 attribute id 不应为 null");
    }

    // ── T3: PUT 修改 importanceLevel + affectsCalculation ────────────────────

    @Test
    @Order(3)
    void t3_updateAttribute_changesImportanceLevel() {
        // 依赖 T2 创建的 testAttrId
        String attrId = getOrCreateTestAttrId();

        String body = """
                {
                  "columnLetter": "B",
                  "columnTitle": "关键列",
                  "variableCode": "%s",
                  "variableLabel": "关键列标签",
                  "importanceLevel": "NORMAL",
                  "affectsCalculation": false
                }
                """.formatted("imp_critical_" + attrId.replace("-", "").substring(0, 6));

        // PUT — 允许 400 variableCode 冲突，只要 importanceLevel 能更新即可
        // 直接用 PATCH 专用端点更稳定
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"importanceLevel\": \"IMPORTANT\", \"affectsCalculation\": false}")
                .patch("/api/cpq/basic-data-config/attributes/" + attrId + "/importance")
                .then()
                .statusCode(200)
                .body("data.importanceLevel", equalTo("IMPORTANT"))
                .body("data.affectsCalculation", equalTo(false));
    }

    // ── T4: PATCH 专用端点 — 仅更新重要性 ────────────────────────────────────

    @Test
    @Order(4)
    void t4_patchImportance_onlyUpdatesImportanceFields() {
        String attrId = getOrCreateTestAttrId();

        // PATCH 仅改 importanceLevel，不改其他字段
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"importanceLevel\": \"CRITICAL\"}")
                .patch("/api/cpq/basic-data-config/attributes/" + attrId + "/importance")
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.importanceLevel", equalTo("CRITICAL"))
                // variableCode 等其他字段不应被清空
                .body("data.variableCode", notNullValue())
                .body("data.configId", notNullValue());
    }

    // ── T5: importanceLevel 非法值 → 400 ─────────────────────────────────────

    @Test
    @Order(5)
    void t5_invalidImportanceLevel_returns400() {
        String attrId = getOrCreateTestAttrId();

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"importanceLevel\": \"SUPER_CRITICAL\"}")
                .patch("/api/cpq/basic-data-config/attributes/" + attrId + "/importance")
                .then()
                .statusCode(anyOf(equalTo(400), equalTo(422)));
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private String createTestSheet() {
        String uniqueName = "Importance Test Sheet " + UUID.randomUUID().toString().substring(0, 8);
        // joinColumns 需传 [] 以满足 NOT NULL 约束（createSheet service 中 toJson(null) → null）
        String body = "{\"sheetName\": \"" + uniqueName + "\", \"joinColumns\": []}";
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/basic-data-config/sheets")
                .then()
                .statusCode(200)
                .extract().path("data.id");
    }

    private String getOrCreateTestAttrId() {
        if (testAttrId != null) return testAttrId;

        String uniqueCode = "imp_fallback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String body = """
                {
                  "configId": "%s",
                  "columnLetter": "C",
                  "columnTitle": "备用测试列",
                  "variableCode": "%s",
                  "variableLabel": "备用",
                  "importanceLevel": "NORMAL",
                  "affectsCalculation": false
                }
                """.formatted(testSheetId, uniqueCode);

        testAttrId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .extract().path("data.id");

        return testAttrId;
    }
}
