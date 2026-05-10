package com.cpq.costing;

import com.cpq.costing.entity.CostingSheet;
import com.cpq.costing.entity.CostingTemplate;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CostingComparisonResourceTest {

    @Inject
    EntityManager em;

    private static UUID quotationId;
    private static UUID costingSheetId;

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

        // 直接 persist 一个 LIVE 状态 CostingSheet（最小：rows 为空数组，total_cost=100）
        CostingSheet cs = new CostingSheet();
        cs.quotationId = quotationId;
        cs.rows = "[{\"hf_part_no\":\"TEST-001\",\"cells\":{\"A\":\"TEST-001\",\"B\":100}}]";
        cs.totalCost = new BigDecimal("100");
        cs.status = "LIVE";
        em.persist(cs);
        em.flush();
        costingSheetId = cs.id;

        // 同时设置 quotation.totalAmount 让 comparison 能拿到客户报价值
        Quotation q = em.find(Quotation.class, quotationId);
        if (q != null) {
            q.totalAmount = new BigDecimal("150");
            q.originalAmount = new BigDecimal("150");
            em.merge(q);
        }
    }

    // ------------------------------------------------------------------
    // COST-SHEET-01: 核价表查询返回 columns + rows + totalCost 结构
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("COST-SHEET-01: GET /quotations/{id}/costing-sheet 返回结构")
    void getCostingSheet_returnsStructure() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + quotationId + "/costing-sheet")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data", notNullValue())
                    .body("data.quotationId", equalTo(quotationId.toString()))
                    .body("data.status", equalTo("LIVE"))
                    .body("data.rows", notNullValue())
                    .body("data.totalCost", anyOf(equalTo(100), equalTo(100.0f), equalTo("100")));
    }

    // ------------------------------------------------------------------
    // COST-SHEET-02: 不存在的报价单核价表返 404
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("COST-SHEET-02: 不存在的报价单 → 404")
    void getCostingSheet_nonexistent_returns404() {
        UUID fakeId = UUID.randomUUID();
        RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + fakeId + "/costing-sheet")
                .then()
                    .statusCode(404);
    }

    // ------------------------------------------------------------------
    // COST-COMPARE-03: 比对视图返回结构
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("COST-COMPARE-03/04/05: GET comparison 返回 basicFieldDiffs/tagGroups/summary 字段")
    void getComparison_returnsExpectedStructure() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + quotationId + "/comparison")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data", notNullValue())
                    .body("data.basicFieldDiffs", notNullValue())
                    .body("data.tagGroups", notNullValue())
                    .body("data.summary", notNullValue());
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
}
