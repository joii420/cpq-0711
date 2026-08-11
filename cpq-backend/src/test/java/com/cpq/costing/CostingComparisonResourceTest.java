package com.cpq.costing;

import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for TDD §10 COST 核价表 / 比对视图.
 *
 * Covers:
 * COST-SHEET-01 (核价表生成) — via direct entity persist
 * COST-SHEET-02 (SUBMITTED 后冻结) — covered in QuotationSnapshotTest, here test status semantics
 * COST-COMPARE-03 (基础字段差异) — partial: 端点结构断言
 * COST-COMPARE-04 (业务标签分组) — partial
 * COST-COMPARE-05 (毛利率警告阈值) — assertion on summary 字段存在
 * COST-COMPARE-06 (毛利率阻止提交) — covered in QuotationSnapshotTest 路径，此处测端点结构
 *
 * Strategy: 不走完整 V5 导入链路，直接 persist Quotation + CostingSheet 验证端点。
 * 完整正向覆盖见 V5ChainEndToEndTest / QuotationSnapshotTest。
 */
@QuarkusTest
@TestProfile(CostingComparisonResourceTest.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CostingComparisonResourceTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    private static UUID quotationId;

    @BeforeEach
    @Transactional
    void setupOnce() {
        if (quotationId != null) return;
        // 创建客户 + 报价单（最小可行）
        String custBody = """
                {
                  "name": "COST Test Customer %d",
                  "level": "STANDARD",
                  "contacts": [
                    {"name": "C", "phone": "13800000099", "isPrimary": true}
                  ]
                }
                """.formatted(System.currentTimeMillis() % 100000);
        String custId = RestAssured.given()
                .contentType(ContentType.JSON).body(custBody)
                .post("/api/cpq/customers").then().statusCode(200)
                .extract().path("data.id");

        String qBody = """
                {
                  "customerId": "%s",
                  "name": "COST Test Quotation",
                  "quoteType": "STANDARD"
                }
                """.formatted(custId);
        String qId = RestAssured.given()
                .contentType(ContentType.JSON).body(qBody)
                .post("/api/cpq/quotations").then().statusCode(200)
                .extract().path("data.id");
        quotationId = UUID.fromString(qId);

        // 同时设置 quotation.totalAmount 让 comparison 能拿到客户报价值
        Quotation q = em.find(Quotation.class, quotationId);
        if (q != null) {
            q.totalAmount = new BigDecimal("150");
            q.originalAmount = new BigDecimal("150");
            em.merge(q);
        }
    }

    // task-0723 B6: 旧核价引擎退役 — COST-SHEET-01/02 (GET .../costing-sheet 端点测试) 已删除，
    // 该端点已下线。比对视图 (COST-COMPARE-*) 是独立活功能，保留。

    // ------------------------------------------------------------------
    // COST-COMPARE-03: 比对视图返回结构
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("COST-COMPARE-03/04/05: GET comparison 返回 basicFieldDiffs/tagGroups/summary 字段")
    void getComparison_returnsExpectedStructure() {
        String body = RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + quotationId + "/comparison")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data", notNullValue())
                    .body("data.basicFieldDiffs", notNullValue())
                    .body("data.tagGroups", notNullValue())
                    .body("data.summary", notNullValue())
                    .extract().asString();

        try {
            com.fasterxml.jackson.databind.JsonNode summary = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("data").path("summary");
            for (String field : new String[] {"costingTotal", "quotationTotal", "profit"}) {
                com.fasterxml.jackson.databind.JsonNode value = summary.path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    Assertions.assertTrue(value.isTextual(), field + " must be a decimal string");
                }
            }
            com.fasterxml.jackson.databind.JsonNode modifiedCount = summary.path("modifiedFieldsCount");
            Assertions.assertTrue(modifiedCount.isMissingNode() || modifiedCount.isNull()
                            || modifiedCount.isIntegralNumber(),
                    "modifiedFieldsCount must remain null or a structural JSON number");
        } catch (java.io.IOException e) {
            throw new AssertionError("comparison response must be valid JSON", e);
        }
    }

    // ------------------------------------------------------------------
    // COST-COMPARE-06: 比对视图 summary 字段含 modifiedFieldsCount/profitRate 等
    // ------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("COST-COMPARE-06: comparison summary 含毛利计算字段")
    void getComparison_summaryHasProfitFields() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + quotationId + "/comparison")
                .then()
                    .statusCode(200)
                    // summary 至少含一个毛利相关字段
                    .body("data.summary", anyOf(
                            hasKey("profitRate"),
                            hasKey("modifiedFieldsCount"),
                            hasKey("costingTotal"),
                            hasKey("quotationTotal")));
    }

    // ------------------------------------------------------------------
    // COST-COMPARE-05: 不存在的报价单比对 → 不返 500
    // ------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("COST-COMPARE-05: 不存在的报价单 comparison 不返 500")
    void getComparison_nonexistent_doesNot500() {
        UUID fakeId = UUID.randomUUID();
        int status = RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + fakeId + "/comparison")
                .then()
                .extract().statusCode();
        assert status < 500 : "comparison should not 500 for missing quotation, got " + status;
    }

    @Test
    @Order(6)
    @DisplayName("comparison export accepts decimal strings")
    void exportComparison_acceptsDecimalStrings() throws Exception {
        String body = """
                {
                  "columns":[{"tag":"MATERIAL","label":"Material"}],
                  "rows":[{"partNo":"P1","presence":"BOTH","cells":{
                    "MATERIAL":{"quote":"98765431.123456789012","costing":"0.000000000500","highlighted":true}
                  }}]
                }
                """;

        byte[] bytes = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/quotations/" + quotationId + "/comparison/export")
                .then()
                .statusCode(200)
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .extract().asByteArray();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            org.apache.poi.ss.usermodel.Cell quote = workbook.getSheetAt(0).getRow(1).getCell(2);
            org.apache.poi.ss.usermodel.Cell costing = workbook.getSheetAt(0).getRow(2).getCell(2);
            assertEquals(CellType.STRING, quote.getCellType());
            assertEquals("98765431.123456789", quote.getStringCellValue());
            assertEquals(CellType.STRING, costing.getCellType());
            assertEquals("0.000000001", costing.getStringCellValue());
        }
    }

    @Test
    @Order(7)
    @DisplayName("comparison export rejects numeric precision tokens")
    void exportComparison_rejectsNumericPrecisionTokens() {
        String body = """
                {
                  "columns":[{"tag":"MATERIAL","label":"Material"}],
                  "rows":[{"partNo":"P1","presence":"BOTH","cells":{
                    "MATERIAL":{"quote":98765431.123456789012,"costing":"1","highlighted":false}
                  }}]
                }
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/quotations/" + quotationId + "/comparison/export")
                .then()
                .statusCode(400)
                .body(allOf(
                        containsString("rows[0].cells.MATERIAL.quote"),
                        containsString("98765431.123456789012")));
    }
}
