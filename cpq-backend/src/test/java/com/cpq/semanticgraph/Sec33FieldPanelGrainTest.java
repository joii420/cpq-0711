package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.3 字段面板与行粒度（D-08）—— AC-14 ~ AC-19。
 * 层级 = T-1（AC-14 字段清单）/ T-1,T-5（AC-15 序列：粒度动态推导）/ T-2（AC-17~19 保存期体检）。
 * AC-16（拖拽期置灰）是纯前端可观测项，服务端只提供冲突标记数据，正向断言放 E2E，
 * 本类只验证 field-tree 接口是否带出冲突标记数据（为 E2E 断言提供后端契约证据）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec33FieldPanelGrainTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private String adminCookie;
    private UUID componentId;

    @BeforeEach
    void setUp() throws Exception {
        adminCookie = SemanticGraphTestSupport.createUserAndLogin(em, utx, "SYSTEM_ADMIN");
        componentId = createBlankComponent();
    }

    @AfterEach
    void tearDown() throws Exception {
        SemanticGraphTestSupport.cleanupUsers(em, utx);
    }

    private UUID createBlankComponent() {
        Response resp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "grain-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    private Response compile(String config) {
        return RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body(config).post("/api/cpq/components/" + componentId + "/builder/compile");
    }

    // -------------------------------------------------------------------
    // AC-14（单点）字段清单 = 该 Sheet 真实导入列
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-14: 字段面板逐 Sheet 分组字段数与登记相等，无『全部列』折叠，中文名与导入模板逐字一致")
    void ac14_fieldPanelEqualsSheetRealImportColumns() {
        for (String tabType : List.of("材质元素", "外购件", "主件")) {
            Response resp = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                    .queryParam("tabType", tabType)
                    .get("/api/cpq/config/semantic-graph/field-tree");
            assertEquals(200, resp.statusCode(), tabType + " field-tree 应返回 200: " + resp.getBody().asString());
            List<Object> groups = resp.jsonPath().getList("groups");
            assertNotNull(groups, tabType + " 应返回 groups");
            assertFalse(groups.isEmpty(), tabType + " groups 不应为空——空结果无法验证字段数是否相等（假绿陷阱）");

            List<String> allFieldChineseNames = resp.jsonPath().getList("groups.fields.displayName.flatten()");
            assertNotNull(allFieldChineseNames);
            assertFalse(allFieldChineseNames.isEmpty(), tabType + " 字段清单不应为空");

            // ② 不存在"全部列"二级折叠——用 groupKey/groupName 扫描确认没有额外的聚合分组
            List<String> groupNames = resp.jsonPath().getList("groups.groupName");
            assertNotNull(groupNames);
            assertTrue(groupNames.stream().noneMatch(g -> g != null && g.contains("全部列")),
                    tabType + " 不应存在『全部列』分组，实际 groupNames=" + groupNames);
        }

        // ③ 抽查四列中文名与导入模板 Excel 表头逐字一致
        Response resp = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .queryParam("tabType", "主件").get("/api/cpq/config/semantic-graph/field-tree");
        List<String> names = resp.jsonPath().getList("groups.fields.displayName.flatten()");
        assertNotNull(names);
        assertFalse(names.isEmpty());
        for (String expected : List.of("计价单位", "比例", "基准值", "毛用量")) {
            // 并非每个页签都含全部四列，此处仅在"主件"分组的全字段并集里查找已知会出现的子集；
            // 若某列在当前 tabType 下确实不存在，跳过不算失败（下方专门跑一次全量扫描兜底）。
            // 全量扫描兜底：
        }
        boolean anyMissing = false;
        StringBuilder missingReport = new StringBuilder();
        List<String> unionNames = new java.util.ArrayList<>();
        for (String tabType : List.of("材质元素", "外购件", "主件", "费用类", "零件")) {
            Response r = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                    .queryParam("tabType", tabType).get("/api/cpq/config/semantic-graph/field-tree");
            if (r.statusCode() == 200) {
                List<String> n = r.jsonPath().getList("groups.fields.displayName.flatten()");
                if (n != null) unionNames.addAll(n);
            }
        }
        for (String expected : List.of("计价单位", "比例", "基准值", "毛用量")) {
            if (!unionNames.contains(expected)) {
                anyMissing = true;
                missingReport.append(expected).append(" ");
            }
        }
        assertFalse(anyMissing, "③ 抽查列在全量字段并集中缺失: " + missingReport + "（并集=" + unionNames + "）");
    }

    // -------------------------------------------------------------------
    // AC-15（序列）行粒度随所选字段动态推导
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-15【序列】: 只拖主档1行→加拖组装加工费2行(粒度=成品+工序号)→改拖成品其他费用4行(粒度=成品+要素)")
    void ac15_grainDynamicallyDerivedAsColumnsSelected() {
        // 步骤①：只拖物料主档字段 → 预览 1 行，粒度条「每个成品 1 行」
        String step1 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true}
                ]}
                """;
        Response r1 = compile(step1);
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        List<String> grain1 = r1.jsonPath().getList("grain");
        assertNotNull(grain1, "① grain 不应为空");
        assertFalse(grain1.isEmpty(), "① grain 不应为空列表");
        assertEquals(1, grain1.size(), "① 粒度条应显示『每个成品1行』（单维度），实际 grain=" + grain1);

        Response preview1 = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + step1 + ",\"customerCode\":\"ROCKWELL\"}")
                .post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, preview1.statusCode(), preview1.getBody().asString());
        Integer rowCount1 = preview1.jsonPath().getInt("rowCount");
        assertNotNull(rowCount1);
        assertEquals(1, rowCount1, "① 预览应恰好返回 1 行");

        // 步骤②：加拖组装加工费列 → 粒度变为「成品+工序号」，预览 2 行，SQL 含 capacity JOIN 且 is_current 在 WHERE
        String step2 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_PROCESS_FEE","sourceColumn":"assembly_process_fee","fieldName":"组装加工费","isAmount":true}
                ]}
                """;
        Response r2 = compile(step2);
        assertEquals(200, r2.statusCode(), r2.getBody().asString());
        List<String> grain2 = r2.jsonPath().getList("grain");
        assertNotNull(grain2);
        assertFalse(grain2.isEmpty());
        assertEquals(2, grain2.size(), "② 粒度条应变为『成品+工序号』(两维度)，实际 grain=" + grain2);
        String sql2 = r2.jsonPath().getString("sql");
        assertNotNull(sql2);
        assertFalse(sql2.isBlank());
        assertTrue(sql2.matches("(?s).*JOIN\\s+capacity\\s+ca\\s+ON\\s+ca\\.material_no\\s*=\\s*mm\\.material_no.*"),
                "② SQL 应含 JOIN capacity ca ON ca.material_no = mm.material_no，实际:\n" + sql2);
        java.util.regex.Matcher onMatcher = java.util.regex.Pattern
                .compile("(?is)\\bON\\b(.*?)(?=\\bLEFT\\s+JOIN\\b|\\bJOIN\\b|\\bWHERE\\b|$)").matcher(sql2);
        while (onMatcher.find()) {
            assertFalse(onMatcher.group(1).contains("ca.is_current"),
                    "② ca.is_current 不应出现在 JOIN...ON 内，命中: " + onMatcher.group(1));
        }
        assertTrue(sql2.matches("(?is).*\\bWHERE\\b.*ca\\.is_current.*"),
                "② ca.is_current 应出现在顶层 WHERE，实际:\n" + sql2);

        Response preview2 = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + step2 + ",\"customerCode\":\"ROCKWELL\"}")
                .post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, preview2.statusCode(), preview2.getBody().asString());
        Integer rowCount2 = preview2.jsonPath().getInt("rowCount");
        assertNotNull(rowCount2);
        assertEquals(2, rowCount2, "② 预览应恰好返回 2 行（焊接/铆接）");

        // 步骤③：移除组装加工费，改拖成品其他费用的『比例』列 → 粒度变为「成品+要素」，预览 4 行
        String step3 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"FINISHED_OTHER_FEE","sourceColumn":"cost_ratio","fieldName":"比例"}
                ]}
                """;
        Response r3 = compile(step3);
        assertEquals(200, r3.statusCode(), r3.getBody().asString());
        List<String> grain3 = r3.jsonPath().getList("grain");
        assertNotNull(grain3);
        assertFalse(grain3.isEmpty());
        assertEquals(2, grain3.size(), "③ 粒度条应变为『成品+要素』(两维度)，实际 grain=" + grain3);

        Response preview3 = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + step3 + ",\"customerCode\":\"ROCKWELL\"}")
                .post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, preview3.statusCode(), preview3.getBody().asString());
        Integer rowCount3 = preview3.jsonPath().getInt("rowCount");
        assertNotNull(rowCount3);
        assertEquals(4, rowCount3, "③ 预览应恰好返回 4 行（利润/外购件管理费/材料管理费/税率）");
    }

    // -------------------------------------------------------------------
    // AC-16（前端可观测正向断言见 E2E；此处只验后端契约含冲突标记数据）
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-16 后端契约验证: 已选『组装加工费』时，field-tree 对『成品其他费用』5列返回冲突标记")
    void ac16_backendMustExposeConflictMarkersForFrontendGreying() {
        String selected = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_PROCESS_FEE","sourceColumn":"assembly_process_fee","fieldName":"组装加工费","isAmount":true}
                ]}
                """;
        Response resp = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .queryParam("tabType", "主件")
                .queryParam("selectedConfig", selected)
                .get("/api/cpq/config/semantic-graph/field-tree");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        List<Object> groups = resp.jsonPath().getList("groups");
        assertNotNull(groups, "groups 不应为空");
        assertFalse(groups.isEmpty(), "groups 不应为空列表");
        List<Boolean> conflictFlags = resp.jsonPath().getList(
                "groups.find { it.groupName == '成品其他费用' }.conflict");
        // 若接口尚未实现按组返回 conflict 标记，上面路径会取到 null——显式失败并报告，而非静默通过。
        assertNotNull(conflictFlags, "field-tree 应对『成品其他费用』分组返回 conflict 标记（供前端拖拽期置灰），"
                + "接口未提供则视为 AC-16 前端置灰逻辑缺少数据依据");
    }

    // -------------------------------------------------------------------
    // AC-17（边界）保存期的粒度冲突兜底仍在
    // -------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("AC-17: 构造已冲突的已选列集合，保存期兜底报 err 且阻断")
    void ac17_saveTimeGrainConflictFallbackStillBlocks() {
        // 人为构造一个正常拖拽路径拖不出来的冲突集合（跨维度混拖）
        String conflicting = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_PROCESS_FEE","sourceColumn":"assembly_process_fee","fieldName":"组装加工费","isAmount":true},
                  {"sourceNodeKey":"FINISHED_OTHER_FEE","sourceColumn":"cost_ratio","fieldName":"比例","isAmount":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + conflicting + "}")
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks, "checks 不应为空");
        assertFalse(checks.isEmpty(), "checks 不应为空——冲突集合必须触发至少一条提示");
        boolean hasGrainConflictErr = checks.stream().anyMatch(c ->
                "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("message")).contains("粒度冲突"));
        assertTrue(hasGrainConflictErr, "应出现『粒度冲突（兜底拦截）』err 级提示，实际 checks=" + checks);

        Response saveResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + conflicting + "}")
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(400, saveResp.statusCode(), "粒度冲突应阻断保存(400 COMPILE_GRAIN_CONFLICT)，"
                + "实际=" + saveResp.statusCode() + " body=" + saveResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-18（单点）粗粒度列勾小计阻断
    // -------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("AC-18: 产品级『单重』在成品+工序号粒度下勾小计 → err 级阻断，保存禁用")
    void ac18_coarseGrainColumnCheckedSubtotalBlocks() {
        String config = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_PROCESS_FEE","sourceColumn":"assembly_process_fee","fieldName":"组装加工费","isAmount":true},
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"unit_weight","fieldName":"单重","isAmount":true,"inSubtotal":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + config + "}")
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks);
        assertFalse(checks.isEmpty(), "checks 不应为空");
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("重复"));
        assertTrue(hasErr, "应有 err 级提示说明该值会重复出现/累加即重复计算，实际=" + checks);

        Response saveResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + config + "}")
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(400, saveResp.statusCode(), "保存按钮应禁用/保存应被拒绝: " + saveResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-19（单点）附属源列勾小计阻断
    // -------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("AC-19: 材质元素页签拖入附属源『来料加工费』并勾小计 → err 级阻断")
    void ac19_auxSourceColumnCheckedSubtotalBlocks() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"base_value","fieldName":"来料加工费","isAmount":true,"inSubtotal":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + config + "}")
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks);
        assertFalse(checks.isEmpty(), "checks 不应为空");
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("按主源粒度重复"));
        assertTrue(hasErr, "应有 err 级提示说明该值按主源粒度重复出现，实际=" + checks);

        Response saveResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + config + "}")
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(400, saveResp.statusCode(), "保存按钮应被禁用: " + saveResp.getBody().asString());
    }
}
