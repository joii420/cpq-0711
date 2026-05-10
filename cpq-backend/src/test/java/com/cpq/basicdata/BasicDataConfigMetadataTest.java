package com.cpq.basicdata;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * BasicDataConfigMetadataTest — D-9 V58 字段编辑 API 验证。
 *
 * <p>验证 BasicDataConfig 的 target_table / target_discriminator 以及
 * BasicDataAttribute 的 is_required 字段可通过 REST API 正确读写。
 *
 * <p>同时验证辅助端点 GET /extensible-tables 返回正确格式。
 *
 * <p>测试用例：
 * <ol>
 *   <li>T6: PUT sheets/{id} 更新 target_table → DB 写入成功，GET 返回正确值</li>
 *   <li>T7: PUT sheets/{id} 更新 target_discriminator JSON → DB 写入成功且可读回</li>
 *   <li>T8: PUT attributes/{id} 更新 is_required = true → DB 写入成功</li>
 *   <li>T9: GET /extensible-tables 返回非空列表，含 tableName/displayName/customerScoped/group</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataConfigMetadataTest {

    private static String testSheetId;
    private static String testAttrId;

    @BeforeEach
    void ensureTestData() {
        if (testSheetId == null) {
            testSheetId = createTestSheet();
        }
        if (testAttrId == null) {
            testAttrId = createTestAttribute(testSheetId);
        }
    }

    // ── T6: PUT sheets/{id} 更新 target_table ────────────────────────────────

    @Test
    @Order(6)
    void t6_updateSheet_targetTable_writtenCorrectly() {
        String body = """
                {
                  "sheetName": "MetaTest_Sheet_%s",
                  "targetTable": "mat_part"
                }
                """.formatted(testSheetId.substring(0, 8));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .put("/api/cpq/basic-data-config/sheets/" + testSheetId)
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.targetTable", equalTo("mat_part"));

        // 回读确认持久化
        RestAssured.given()
                .get("/api/cpq/basic-data-config/sheets/" + testSheetId)
                .then()
                .statusCode(200)
                .body("data.targetTable", equalTo("mat_part"));
    }

    // ── T7: PUT sheets/{id} 更新 target_discriminator ────────────────────────

    @Test
    @Order(7)
    void t7_updateSheet_targetDiscriminator_writtenAndReadBack() {
        String body = """
                {
                  "sheetName": "MetaTest_Sheet_%s",
                  "targetTable": "mat_bom",
                  "targetDiscriminator": {"bom_type": "INCOMING", "extra": 42}
                }
                """.formatted(testSheetId.substring(0, 8));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .put("/api/cpq/basic-data-config/sheets/" + testSheetId)
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.targetTable", equalTo("mat_bom"))
                .body("data.targetDiscriminator", notNullValue())
                .body("data.targetDiscriminator.bom_type", equalTo("INCOMING"))
                .body("data.targetDiscriminator.extra", equalTo(42));

        // 回读确认 JSONB 持久化正确
        RestAssured.given()
                .get("/api/cpq/basic-data-config/sheets/" + testSheetId)
                .then()
                .statusCode(200)
                .body("data.targetDiscriminator.bom_type", equalTo("INCOMING"));
    }

    // ── T8: PUT attributes/{id} 更新 is_required = true ─────────────────────

    @Test
    @Order(8)
    void t8_updateAttribute_isRequired_writtenCorrectly() {
        String body = """
                {
                  "columnLetter": "A",
                  "columnTitle": "必填列",
                  "variableCode": "%s",
                  "variableLabel": "必填列标签",
                  "isRequired": true
                }
                """.formatted("meta_req_" + testAttrId.replace("-", "").substring(0, 6));

        // 直接更新已有属性
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"isRequired\": true}")
                .put("/api/cpq/basic-data-config/attributes/" + testAttrId)
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.isRequired", equalTo(true));

        // 回读确认
        RestAssured.given()
                .queryParam("sheetId", testSheetId)
                .get("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .body("data.find { it.id == '" + testAttrId + "' }.isRequired", equalTo(true));
    }

    // ── T9: GET /extensible-tables 返回正确格式 ──────────────────────────────

    @Test
    @Order(9)
    void t9_getExtensibleTables_returnsAllTables() {
        RestAssured.given()
                .get("/api/cpq/basic-data-config/extensible-tables")
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data", not(empty()))
                .body("data[0].tableName", notNullValue())
                .body("data[0].displayName", notNullValue())
                .body("data[0].group", notNullValue())
                // 验证列表中包含已知表名
                .body("data.tableName", hasItem("mat_part"))
                .body("data.tableName", hasItem("mat_bom"));
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private String createTestSheet() {
        String uniqueName = "MetaTest Sheet " + UUID.randomUUID().toString().substring(0, 8);
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"sheetName\": \"" + uniqueName + "\", \"joinColumns\": []}")
                .post("/api/cpq/basic-data-config/sheets")
                .then()
                .statusCode(200)
                .extract().path("data.id");
    }

    private String createTestAttribute(String sheetId) {
        String uniqueCode = "meta_attr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "configId": "%s",
                          "columnLetter": "A",
                          "columnTitle": "Meta测试列",
                          "variableCode": "%s",
                          "variableLabel": "Meta测试列标签"
                        }
                        """.formatted(sheetId, uniqueCode))
                .post("/api/cpq/basic-data-config/attributes")
                .then()
                .statusCode(200)
                .extract().path("data.id");
    }
}
