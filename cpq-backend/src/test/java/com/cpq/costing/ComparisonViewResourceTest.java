package com.cpq.costing;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for task-0717 报价单比对视图（新 {@code /comparison-view/*} 端点）。
 * 契约见 dev-docs/task-0717-比对视图/api.md；验收标准见 backtask.md §验收标准(AC-1~AC-6)。
 *
 * <p>Fixture 策略（避免 CardSnapshotService 的懒算/覆写机制干扰构造的 presence 边缘场景，
 * 见开发过程中对 {@code CardSnapshotService#ensureCardValues}/{@code snapshotNewLinesCardValues}
 * 的实测：任何 {@code quote_card_values IS NULL} 的行都会被懒算路径选中并落「失败哨兵」覆盖）：
 * <ul>
 *   <li><b>live fixture</b>（frozen=false）：所有合成行的 {@code quote_card_values} 从一开始就非空
 *       （customerTemplateId 留空 → ensureCardValues 只会选中 quote_card_values IS NULL 的行，
 *       非空行绝不会被懒算路径触碰/覆写），覆盖 BOTH + QUOTE_ONLY。</li>
 *   <li><b>frozen fixture</b>（frozen=true）：直接 native SQL 写 {@code costing_order.frozen_dto}/
 *       {@code costing_render}，完全绕开 CardSnapshotService 懒算，可自由构造 BOTH/QUOTE_ONLY/
 *       COSTING_ONLY 三种 presence（AC-4 全覆盖）。</li>
 *   <li><b>真实数据 AC-3 核对</b>：额外用共享 DB 中已存在的一条 SUBMITTED 报价单（quote_card_values/
 *       costing_card_values 均非空的真实产品行）核对 productTotal 与库中 JSON 逐值一致，不依赖硬编码期望值
 *       （测试内现读现算，随数据漂移自动跟随，找不到该行时 {@code assumeTrue} 优雅跳过）。</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComparisonViewResourceTest {

    @Inject
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── live fixture ────────────────────────────────────────────────────────
    private static UUID liveQuotationId;
    private static final UUID QUOTE_TAB_ID = UUID.randomUUID();
    private static final UUID QUOTE_SUBTOTAL_TAB_ID = UUID.randomUUID();
    private static final UUID COSTING_TAB_ID = UUID.randomUUID();
    private static final UUID COSTING_SUBTOTAL_TAB_ID = UUID.randomUUID();

    // ── frozen fixture ──────────────────────────────────────────────────────
    private static UUID frozenQuotationId;

    @BeforeEach
    @Transactional
    void setupOnce() {
        if (liveQuotationId != null) return;
        liveQuotationId = createQuotationViaApi("CMPV Live Quotation");
        seedLiveFixture(liveQuotationId);

        frozenQuotationId = createQuotationViaApi("CMPV Frozen Quotation");
        seedFrozenFixture(frozenQuotationId);
    }

    // ==========================================================================
    // Fixture builders
    // ==========================================================================

    private UUID createQuotationViaApi(String name) {
        String custBody = """
                {
                  "name": "CMPV Test Customer %d",
                  "level": "STANDARD",
                  "contacts": [
                    {"name": "C", "phone": "13800000098", "isPrimary": true}
                  ]
                }
                """.formatted(System.nanoTime() % 100000000);
        String custId = RestAssured.given()
                .contentType(ContentType.JSON).body(custBody)
                .post("/api/cpq/customers").then().statusCode(200)
                .extract().path("data.id");

        String qBody = """
                {
                  "customerId": "%s",
                  "name": "%s",
                  "quoteType": "STANDARD"
                }
                """.formatted(custId, name);
        String qId = RestAssured.given()
                .contentType(ContentType.JSON).body(qBody)
                .post("/api/cpq/quotations").then().statusCode(200)
                .extract().path("data.id");
        return UUID.fromString(qId);
    }

    /** 结构：报价侧 1 个 NORMAL tab(1 个 is_subtotal 字段) + 1 个 SUBTOTAL tab；核价侧对称。 */
    private String quoteStructureJson() {
        return """
                {"tabs":[
                  {"componentId":"%s","tabName":"投料","sortOrder":0,
                    "fields":[{"name":"材料小计","label":"材料小计","isSubtotal":true},
                               {"name":"备注","label":"备注","isSubtotal":false}]},
                  {"componentId":"%s","tabName":"产品小计","sortOrder":1,"fields":[]}
                ]}
                """.formatted(QUOTE_TAB_ID, QUOTE_SUBTOTAL_TAB_ID);
    }

    private String costingStructureJson() {
        return """
                {"tabs":[
                  {"componentId":"%s","tabName":"物料BOM","sortOrder":0,
                    "fields":[{"name":"BOM成本","label":"BOM成本","isSubtotal":true}]},
                  {"componentId":"%s","tabName":"核价小计","sortOrder":1,"fields":[]}
                ]}
                """.formatted(COSTING_TAB_ID, COSTING_SUBTOTAL_TAB_ID);
    }

    private String quoteCardValuesJson(double matSubtotal, double productTotal) {
        return """
                {"tabs":[
                  {"componentId":"%s","componentType":"NORMAL","tabName":"投料","subtotal":%s,"subtotalByColumn":{"材料小计":%s}},
                  {"componentId":"%s","componentType":"SUBTOTAL","tabName":"产品小计","subtotal":%s}
                ]}
                """.formatted(QUOTE_TAB_ID, matSubtotal, matSubtotal, QUOTE_SUBTOTAL_TAB_ID, productTotal);
    }

    private String costingCardValuesJson(double bomSubtotal, double productTotal) {
        return """
                {"tabs":[
                  {"componentId":"%s","componentType":"NORMAL","tabName":"物料BOM","subtotal":%s,"subtotalByColumn":{"BOM成本":%s}},
                  {"componentId":"%s","componentType":"SUBTOTAL","tabName":"核价小计","subtotal":%s}
                ]}
                """.formatted(COSTING_TAB_ID, bomSubtotal, bomSubtotal, COSTING_SUBTOTAL_TAB_ID, productTotal);
    }

    private void seedLiveFixture(UUID quotationId) {
        // 结构快照（QUOTE_CARD / COSTING_CARD）
        persistStructure(quotationId, "QUOTE_CARD", quoteStructureJson());
        persistStructure(quotationId, "COSTING_CARD", costingStructureJson());

        // PN-BOTH-1：报价 + 核价均有值
        QuotationLineItem both = new QuotationLineItem();
        both.quotationId = quotationId;
        both.productPartNoSnapshot = "CMPV-BOTH-1";
        both.productNameSnapshot = "比对测试产品-BOTH";
        both.quoteCardValues = quoteCardValuesJson(100.1234, 150.50);
        both.costingCardValues = costingCardValuesJson(80.0, 90.0);
        both.persist();

        // PN-QUOTE-ONLY-1：只有报价侧（costing_card_values 留 NULL）
        QuotationLineItem quoteOnly = new QuotationLineItem();
        quoteOnly.quotationId = quotationId;
        quoteOnly.productPartNoSnapshot = "CMPV-QUOTE-ONLY-1";
        quoteOnly.productNameSnapshot = "比对测试产品-QUOTE-ONLY";
        quoteOnly.quoteCardValues = quoteCardValuesJson(55.5, 60.0);
        quoteOnly.costingCardValues = null;
        quoteOnly.persist();

        em.flush();
    }

    private void persistStructure(UUID quotationId, String viewKind, String structureJson) {
        QuotationViewStructure vs = new QuotationViewStructure();
        vs.quotationId = quotationId;
        vs.viewKind = viewKind;
        vs.structure = structureJson;
        vs.createdAt = OffsetDateTime.now();
        vs.persist();
    }

    /**
     * frozen fixture：直接 native SQL 写 costing_order（frozen_dto + costing_render），
     * 绕开 CardSnapshotService 懒算，自由构造 BOTH/QUOTE_ONLY/COSTING_ONLY 三种 presence。
     */
    private void seedFrozenFixture(UUID quotationId) {
        UUID liId1 = UUID.randomUUID(); // BOTH
        UUID liId2 = UUID.randomUUID(); // QUOTE_ONLY
        UUID liId3 = UUID.randomUUID(); // COSTING_ONLY

        String frozenDto = ("""
                {"id":"%s","lineItems":[
                  {"id":"%s","productPartNo":"CMPV-FZ-BOTH-1","productName":"冻结-BOTH",
                    "quoteCardValues":%s,"costingCardValues":%s},
                  {"id":"%s","productPartNo":"CMPV-FZ-QUOTE-ONLY-1","productName":"冻结-QUOTE-ONLY",
                    "quoteCardValues":%s,"costingCardValues":null},
                  {"id":"%s","productPartNo":"CMPV-FZ-COSTING-ONLY-1","productName":"冻结-COSTING-ONLY",
                    "quoteCardValues":null,"costingCardValues":%s}
                ]}
                """).formatted(
                quotationId,
                liId1, jsonStringLiteral(quoteCardValuesJson(120.0, 130.0)), jsonStringLiteral(costingCardValuesJson(70.0, 75.0)),
                liId2, jsonStringLiteral(quoteCardValuesJson(20.0, 25.0)),
                liId3, jsonStringLiteral(costingCardValuesJson(9.0, 9.5))
        );

        // costingRender 覆盖第一条(BOTH)的核价侧为一个不同的值，验证 frozen 读取确实走 costingRender 覆盖层
        // （镜像前端 CostingReviewPage.buildFrozenView 的覆盖算法），而非原样落回 frozenDto 自带核价值。
        String overriddenCosting = costingCardValuesJson(88.8, 99.9);
        String costingRender = ("""
                {"%s":{"costingCardValues":%s}}
                """).formatted(liId1, jsonStringLiteral(overriddenCosting));

        em.createNativeQuery(
                "INSERT INTO costing_order (id, quotation_id, entered_costing_at, created_at, " +
                "costing_order_number, status, frozen_dto, total_amount, costing_render, costing_total_amount, updated_at) " +
                "VALUES (:id, :qid, now(), now(), :num, 'PENDING', CAST(:frozen AS jsonb), 0, CAST(:render AS jsonb), 0, now())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("qid", quotationId)
                .setParameter("num", "HJ-CMPV-TEST-" + System.nanoTime())
                .setParameter("frozen", frozenDto)
                .setParameter("render", costingRender)
                .executeUpdate();

        em.flush();
    }

    /** 把一段 JSON 文本原样转成「JSON 字符串字面量」（供拼进外层 JSON，模拟 quoteCardValues 字段本身是 String）。 */
    private String jsonStringLiteral(String rawJson) {
        try {
            return MAPPER.writeValueAsString(rawJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==========================================================================
    // T_META: meta 目录
    // ==========================================================================

    @Test
    @Order(1)
    @DisplayName("AC-5: GET meta 返回两侧页签目录，metrics 含 is_subtotal 字段 + __TAB_TOTAL__")
    void getMeta_returnsTabsAndMetrics() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/meta")
                .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.quoteTabs", hasSize(2))
                .body("data.quoteTabs[0].componentId", equalTo(QUOTE_TAB_ID.toString()))
                .body("data.quoteTabs[0].metrics.key", hasItems("材料小计", "__TAB_TOTAL__"))
                .body("data.costingTabs", hasSize(2))
                .body("data.costingTabs[0].componentId", equalTo(COSTING_TAB_ID.toString()))
                .body("data.costingTabs[0].metrics.key", hasItems("BOM成本", "__TAB_TOTAL__"));
    }

    // ==========================================================================
    // T_CONFIG: config 读写 + 桶隔离（AC-1/AC-2）
    // ==========================================================================

    @Test
    @Order(2)
    @DisplayName("api.md §3: GET config 未保存过 → columns=null")
    void getConfig_neverSaved_returnsNullColumns() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=SALES")
                .then()
                .statusCode(200)
                .body("data.quotationId", equalTo(liveQuotationId.toString()))
                .body("data.bucket", equalTo("SALES"))
                .body("data.columns", nullValue());
    }

    @Test
    @Order(3)
    @DisplayName("AC-1+AC-2: PUT SALES 配置后 GET 原样回显（含 threshold/sortOrder），FINANCE 桶不受影响")
    void putThenGetConfig_roundTripAndBucketIsolation() {
        String salesColumns = """
                { "columns": [
                  {"id":"col-1","kind":"PRODUCT_TOTAL","sortOrder":0,"threshold":0},
                  {"id":"col-2","kind":"TAB_PAIR","sortOrder":1,"threshold":5,
                   "quoteComponentId":"%s","quoteMetric":"材料小计","quoteLabel":"投料·材料小计",
                   "costingComponentId":"%s","costingMetric":"BOM成本","costingLabel":"物料BOM·BOM成本"}
                ]}
                """.formatted(QUOTE_TAB_ID, COSTING_TAB_ID);

        RestAssured.given()
                .contentType(ContentType.JSON).body(salesColumns)
                .when().put("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=SALES")
                .then()
                .statusCode(200)
                .body("data.bucket", equalTo("SALES"))
                .body("data.columns", hasSize(2))
                .body("data.columns[1].threshold", equalTo(5))
                .body("data.columns[1].quoteMetric", equalTo("材料小计"));

        // GET 原样回显（往返无损）
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=SALES")
                .then()
                .statusCode(200)
                .body("data.columns", hasSize(2))
                .body("data.columns[0].id", equalTo("col-1"))
                .body("data.columns[1].id", equalTo("col-2"))
                .body("data.columns[1].threshold", equalTo(5));

        // FINANCE 桶未保存过 → 仍为 null（桶隔离，AC-1）
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=FINANCE")
                .then()
                .statusCode(200)
                .body("data.columns", nullValue());

        // 再给 FINANCE 桶存一份不同内容，验证互不覆盖
        String financeColumns = """
                { "columns": [ {"id":"col-f1","kind":"PRODUCT_TOTAL","sortOrder":0,"threshold":10} ] }
                """;
        RestAssured.given()
                .contentType(ContentType.JSON).body(financeColumns)
                .when().put("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=FINANCE")
                .then()
                .statusCode(200)
                .body("data.columns", hasSize(1));

        // SALES 桶配置不受 FINANCE 写入影响
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=SALES")
                .then()
                .statusCode(200)
                .body("data.columns", hasSize(2));

        // SQL 核验：两行分别存在，bucket 各自独立
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT bucket, jsonb_array_length(columns) FROM quotation_comparison_config " +
                "WHERE quotation_id = :qid ORDER BY bucket")
                .setParameter("qid", liveQuotationId)
                .getResultList();
        Assertions.assertEquals(2, rows.size(), "AC-1: 应有 SALES + FINANCE 两行独立配置");
    }

    @Test
    @Order(4)
    @DisplayName("bucket 非法 → 400")
    void config_invalidBucket_returns400() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=BOGUS")
                .then().statusCode(400);
    }

    @Test
    @Order(5)
    @DisplayName("columns 结构非法（缺 kind）→ 400")
    void putConfig_invalidColumns_returns400() {
        String bad = """
                { "columns": [ {"id":"col-x","threshold":0} ] }
                """;
        RestAssured.given()
                .contentType(ContentType.JSON).body(bad)
                .when().put("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/config?bucket=SALES")
                .then().statusCode(400);
    }

    // ==========================================================================
    // T_DATA_LIVE: frozen=false（live fixture）
    // ==========================================================================

    @Test
    @Order(6)
    @DisplayName("AC-3+AC-4(live): BOTH 行 productTotal/tabTotal/subtotals 与合成卡片值逐值一致")
    void getData_live_bothPresence_valuesMatchSeed() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/data")
                .then()
                .statusCode(200)
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.presence", equalTo("BOTH"))
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.quote.productTotal", equalTo(150.50f))
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.quote.tabs['" + QUOTE_TAB_ID + "'].tabTotal", equalTo(100.1234f))
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.quote.tabs['" + QUOTE_TAB_ID + "'].subtotals['材料小计']", equalTo(100.1234f))
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.costing.productTotal", equalTo(90.0f))
                .body("data.rows.find { it.partNo == 'CMPV-BOTH-1' }.costing.tabs['" + COSTING_TAB_ID + "'].subtotals['BOM成本']", equalTo(80.0f));
    }

    @Test
    @Order(7)
    @DisplayName("AC-4(live): QUOTE_ONLY 行 costing=null")
    void getData_live_quoteOnlyPresence() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + liveQuotationId + "/comparison-view/data")
                .then()
                .statusCode(200)
                .body("data.rows.find { it.partNo == 'CMPV-QUOTE-ONLY-1' }.presence", equalTo("QUOTE_ONLY"))
                .body("data.rows.find { it.partNo == 'CMPV-QUOTE-ONLY-1' }.costing", nullValue())
                .body("data.rows.find { it.partNo == 'CMPV-QUOTE-ONLY-1' }.quote.productTotal", equalTo(60.0f));
    }

    // ==========================================================================
    // T_DATA_FROZEN: frozen=true（frozen fixture，AC-4 presence 矩阵全覆盖）
    // ==========================================================================

    @Test
    @Order(8)
    @DisplayName("AC-4(frozen): BOTH/QUOTE_ONLY/COSTING_ONLY presence 矩阵全覆盖 + 核价侧读 costingRender 覆盖值")
    void getData_frozen_presenceMatrixAndCostingRenderOverlay() {
        RestAssured.given()
                .when().get("/api/cpq/quotations/" + frozenQuotationId + "/comparison-view/data?frozen=true")
                .then()
                .statusCode(200)
                // BOTH：核价侧应读 costingRender 覆盖值（88.8/99.9），而非 frozenDto 自带的（70.0/75.0）
                .body("data.rows.find { it.partNo == 'CMPV-FZ-BOTH-1' }.presence", equalTo("BOTH"))
                .body("data.rows.find { it.partNo == 'CMPV-FZ-BOTH-1' }.quote.productTotal", equalTo(130.0f))
                .body("data.rows.find { it.partNo == 'CMPV-FZ-BOTH-1' }.costing.productTotal", equalTo(99.9f))
                // QUOTE_ONLY
                .body("data.rows.find { it.partNo == 'CMPV-FZ-QUOTE-ONLY-1' }.presence", equalTo("QUOTE_ONLY"))
                .body("data.rows.find { it.partNo == 'CMPV-FZ-QUOTE-ONLY-1' }.costing", nullValue())
                .body("data.rows.find { it.partNo == 'CMPV-FZ-QUOTE-ONLY-1' }.quote.productTotal", equalTo(25.0f))
                // COSTING_ONLY
                .body("data.rows.find { it.partNo == 'CMPV-FZ-COSTING-ONLY-1' }.presence", equalTo("COSTING_ONLY"))
                .body("data.rows.find { it.partNo == 'CMPV-FZ-COSTING-ONLY-1' }.quote", nullValue())
                .body("data.rows.find { it.partNo == 'CMPV-FZ-COSTING-ONLY-1' }.costing.productTotal", equalTo(9.5f));
    }

    // ==========================================================================
    // T_AC3_REAL: 真实共享 DB 数据核对（现读现算，不硬编码期望值）
    // ==========================================================================

    @Test
    @Order(9)
    @DisplayName("AC-3(真实数据): 已提交报价单某产品行 productTotal 与库中 quote_card_values.tabs[SUBTOTAL].subtotal 逐值一致")
    void getData_live_realSubmittedQuotation_productTotalMatchesStoredJson() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT li.quotation_id, COALESCE((SELECT part_no FROM product WHERE id = li.product_id), " +
                "li.product_part_no_snapshot) AS part_no, li.quote_card_values " +
                "FROM quotation_line_item li " +
                "WHERE li.quote_card_values IS NOT NULL AND li.costing_card_values IS NOT NULL " +
                "  AND jsonb_typeof(li.quote_card_values::jsonb->'tabs') = 'array' " +
                "LIMIT 1")
                .getResultList();
        assumeTrue(!rows.isEmpty(), "需要共享 DB 中至少一条 quote_card_values/costing_card_values 均非空的真实产品行");

        Object[] row = rows.get(0);
        UUID qId = (UUID) row[0];
        String partNo = (String) row[1];
        String quoteCardValuesJson = row[2].toString();
        assumeTrue(partNo != null && !partNo.isBlank(), "该行需有可用料号");

        // 现算期望值：从库中原始 JSON 里找 componentType==SUBTOTAL 的 tab.subtotal（与
        // ComparisonViewService#extractSide 同一算法，独立实现校验，非直接复用被测代码）。
        BigDecimal expectedProductTotal = null;
        try {
            JsonNode root = MAPPER.readTree(quoteCardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                if ("SUBTOTAL".equals(tab.path("componentType").asText(null))) {
                    JsonNode sub = tab.path("subtotal");
                    if (!sub.isMissingNode() && !sub.isNull()) {
                        expectedProductTotal = BigDecimal.valueOf(sub.asDouble());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Assertions.fail("解析真实 quote_card_values 失败: " + e.getMessage());
        }
        assumeTrue(expectedProductTotal != null, "该行需有 SUBTOTAL tab 且带 subtotal");

        String body = RestAssured.given()
                .when().get("/api/cpq/quotations/" + qId + "/comparison-view/data")
                .then()
                .statusCode(200)
                .extract().asString();

        JsonNode data;
        try {
            data = MAPPER.readTree(body).path("data").path("rows");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JsonNode matchedRow = null;
        for (JsonNode r : data) {
            if (partNo.equals(r.path("partNo").asText(null))) {
                matchedRow = r;
                break;
            }
        }
        Assertions.assertNotNull(matchedRow, "AC-3: data 端点应含该真实料号 " + partNo);
        JsonNode productTotalNode = matchedRow.path("quote").path("productTotal");
        Assertions.assertFalse(productTotalNode.isMissingNode() || productTotalNode.isNull(),
                "AC-3: 真实料号 " + partNo + " 的 quote.productTotal 不应缺失");
        BigDecimal actual = BigDecimal.valueOf(productTotalNode.asDouble());
        Assertions.assertEquals(0, expectedProductTotal.compareTo(actual),
                "AC-3 单源一致: data 端点 productTotal(" + actual + ") 应与库中 quote_card_values.tabs[SUBTOTAL].subtotal("
                        + expectedProductTotal + ") 逐值相等，料号=" + partNo);
    }

    // ==========================================================================
    // 回归：不存在的报价单 → 不 500
    // ==========================================================================

    @Test
    @Order(20)
    @DisplayName("回归: 不存在的报价单 meta/data/config 不返 500")
    void nonexistentQuotation_doesNot500() {
        UUID fakeId = UUID.randomUUID();
        int metaStatus = RestAssured.given()
                .when().get("/api/cpq/quotations/" + fakeId + "/comparison-view/meta")
                .then().extract().statusCode();
        Assertions.assertTrue(metaStatus < 500, "meta 不应 500，got " + metaStatus);

        int dataStatus = RestAssured.given()
                .when().get("/api/cpq/quotations/" + fakeId + "/comparison-view/data")
                .then().extract().statusCode();
        Assertions.assertTrue(dataStatus < 500, "data 不应 500，got " + dataStatus);

        int configStatus = RestAssured.given()
                .when().get("/api/cpq/quotations/" + fakeId + "/comparison-view/config?bucket=SALES")
                .then().extract().statusCode();
        Assertions.assertTrue(configStatus < 500, "config 不应 500，got " + configStatus);
    }
}
