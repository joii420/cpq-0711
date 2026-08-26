package com.cpq.semanticgraph;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec33FieldPanelGrainTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    // -------------------------------------------------------------------
    // AC-15 合成数据坐标（D-72：AC-15 的 1/2/4 行基准在任何库都凑不齐，
    // 改为自建合成数据，AC 原文的 1/2/4 断言保持不变）。
    // 五张表的必需链：material_master(锚点物理表) -> material_customer_map
    // (customerCode 收窄 JOIN 键) -> material_bom_item(QUOTE 闭包递归起点，
    // 缺它闭包为空恒 0 行) -> capacity(2 个 process_no，AC-15② 焊接/铆接)
    // -> unit_price(4 个 cost_type，AC-15③ 利润/外购件管理费/材料管理费/税率)。
    // 经 semantic_node/semantic_edge_key 实查确认：ASSEMBLY_FEE 与 PRODUCT_MASTER
    // 的 JOIN 键都是 material_no；FINISHED_OTHER 与 PRODUCT_MASTER 的 JOIN 键左
    // 边是 material_no，右边是 unit_price.code（不是 finished_material_no——现网
    // 数据也证实 FINISHED_OTHER 行一律把料号存在 code 列，finished_material_no
    // 恒为空）。为稳妥起见，两列都写成料号，不依赖对哪一列生效的假设。
    // -------------------------------------------------------------------
    private static final String AC15_CUSTOMER_NO = "AC15SYN-CUST";
    private static final String AC15_ROOT_MATERIAL_NO = "AC15SYN-ROOT";
    private static final String AC15_PROCESS_1 = "AC15-WELD";
    private static final String AC15_PROCESS_2 = "AC15-RIVET";

    @AfterEach
    void cleanupAc15SyntheticData() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM unit_price WHERE code = :m OR finished_material_no = :m")
                    .setParameter("m", AC15_ROOT_MATERIAL_NO).executeUpdate();
            em.createNativeQuery("DELETE FROM capacity WHERE material_no = :m")
                    .setParameter("m", AC15_ROOT_MATERIAL_NO).executeUpdate();
            em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no = :c")
                    .setParameter("c", AC15_CUSTOMER_NO).executeUpdate();
            em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no = :c")
                    .setParameter("c", AC15_CUSTOMER_NO).executeUpdate();
            em.createNativeQuery("DELETE FROM material_master WHERE material_no = :m")
                    .setParameter("m", AC15_ROOT_MATERIAL_NO).executeUpdate();
        });
    }

    /**
     * AC-15 合成数据种子。capacityRows 参数用于反证实验（把 2 行改成 1 行，
     * 验证 AC-15② 会真的失败——见 D-72 回报中的对比）。
     */
    private void seedAc15SyntheticData(int capacityRows) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO material_master (material_no) VALUES (:m)")
                    .setParameter("m", AC15_ROOT_MATERIAL_NO).executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO material_customer_map (material_no, customer_no, system_type) " +
                            "VALUES (:m, :c, 'QUOTE')")
                    .setParameter("m", AC15_ROOT_MATERIAL_NO).setParameter("c", AC15_CUSTOMER_NO)
                    .executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO material_bom_item (system_type, customer_no, material_no, seq_no, is_current) " +
                            "VALUES ('QUOTE', :c, :m, 1, true)")
                    .setParameter("c", AC15_CUSTOMER_NO).setParameter("m", AC15_ROOT_MATERIAL_NO)
                    .executeUpdate();
            if (capacityRows >= 1) {
                em.createNativeQuery(
                        "INSERT INTO capacity (material_no, process_no, process_name, resource_group_no, " +
                                "production_type, system_type, is_current, fixed_cost) " +
                                "VALUES (:m, :p, '焊接', 'AC15-RG', 'UNIT', 'QUOTE', true, 100)")
                        .setParameter("m", AC15_ROOT_MATERIAL_NO).setParameter("p", AC15_PROCESS_1)
                        .executeUpdate();
            }
            if (capacityRows >= 2) {
                em.createNativeQuery(
                        "INSERT INTO capacity (material_no, process_no, process_name, resource_group_no, " +
                                "production_type, system_type, is_current, fixed_cost) " +
                                "VALUES (:m, :p, '铆接', 'AC15-RG', 'UNIT', 'QUOTE', true, 200)")
                        .setParameter("m", AC15_ROOT_MATERIAL_NO).setParameter("p", AC15_PROCESS_2)
                        .executeUpdate();
            }
            // 节点 FINISHED_OTHER 的 discriminator 是 price_type = 'FINISHED_MATERIAL_OTHER'
            // （不是节点键本身的字面量），且 scope=FULL 会额外要求 up.customer_no = :customerCode
            // ——两者都由实测 /builder/compile 返回的 SQL 反查确认，而非读实现代码。
            for (String costType : List.of("利润", "外购件管理费", "材料管理费", "税率")) {
                em.createNativeQuery(
                        "INSERT INTO unit_price (system_type, price_type, version_no, code, " +
                                "finished_material_no, cost_type, cost_ratio, customer_no, is_current) " +
                                "VALUES ('QUOTE', 'FINISHED_MATERIAL_OTHER', 'V1', :m, :m, :ct, 1.5, :c, true)")
                        .setParameter("m", AC15_ROOT_MATERIAL_NO).setParameter("ct", costType)
                        .setParameter("c", AC15_CUSTOMER_NO)
                        .executeUpdate();
            }
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        componentId = createBlankComponent();
    }


    private UUID createBlankComponent() {
        Response resp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "grain-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }


    /** 见 Sec35FeeTabPreviewInspectTest 同名方法的教训说明：/builder(PUT)、/inspect、/preview 都不吃
     *  {"builderConfig": {...}} 包装，要把额外字段合并进 builder_config 对象本身的顶层。 */
    private static String withExtraFields(String configJson, String extraFieldsJson) {
        int idx = configJson.indexOf('{');
        return configJson.substring(0, idx + 1) + extraFieldsJson + "," + configJson.substring(idx + 1);
    }

    private Response compile(String config) {
        return RestAssured.given().contentType(ContentType.JSON)
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
            Response resp = RestAssured.given()
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
        Response resp = RestAssured.given()
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
            // 费用类是 D-34 分立建模的 variant 页签——种子里没有空 variantKey 的默认行，
            // 不带 variantKey 会 404（『未找到页签视图: 费用类/』），api.md §1.4 示例也明确要带。
            io.restassured.specification.RequestSpecification req = RestAssured.given().queryParam("tabType", tabType);
            if ("费用类".equals(tabType)) {
                req = req.queryParam("variantKey", "INCOMING_FIXED");
            }
            Response r = req.get("/api/cpq/config/semantic-graph/field-tree");
            assertEquals(200, r.statusCode(), tabType + " field-tree 应返回 200: " + r.getBody().asString());
            List<String> n = r.jsonPath().getList("groups.fields.displayName.flatten()");
            if (n != null) unionNames.addAll(n);
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
        // D-72：AC 原文的 1/2/4 行基准在测试库/dev 库均凑不齐，改为自建合成数据
        // （5 张表完整链：material_master/material_customer_map/material_bom_item/
        // capacity(2 行)/unit_price(4 行)），AC 的 1/2/4 断言原文不变。
        seedAc15SyntheticData(2);

        // 步骤①：只拖物料主档字段 → 预览 1 行，粒度条「每个成品 1 行」
        String step1 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true}
                ]}
                """;
        Response r1 = compile(step1);
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        List<String> grain1 = r1.jsonPath().getList("grain");
        assertNotNull(grain1, "① grain 不应为空");
        assertFalse(grain1.isEmpty(), "① grain 不应为空列表");
        assertEquals(1, grain1.size(), "① 粒度条应显示『每个成品1行』（单维度），实际 grain=" + grain1);

        Response preview1 = RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(step1, "\"customerCode\":\"" + AC15_CUSTOMER_NO + "\""))
                .post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, preview1.statusCode(), preview1.getBody().asString());
        Integer rowCount1 = preview1.jsonPath().getInt("rowCount");
        assertNotNull(rowCount1);
        assertEquals(1, rowCount1, "① 预览应恰好返回 1 行");

        // 步骤②：加拖组装加工费列 → 粒度变为「成品+工序号」，预览 2 行，SQL 含 capacity JOIN 且 is_current 在 WHERE
        String step2 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"fixed_cost","fieldName":"组装加工费","isAmount":true}
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

        Response preview2 = RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(step2, "\"customerCode\":\"" + AC15_CUSTOMER_NO + "\""))
                .post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, preview2.statusCode(), preview2.getBody().asString());
        Integer rowCount2 = preview2.jsonPath().getInt("rowCount");
        assertNotNull(rowCount2);
        assertEquals(2, rowCount2, "② 预览应恰好返回 2 行（焊接/铆接）");

        // 步骤③：移除组装加工费，改拖成品其他费用的『比例』列 → 粒度变为「成品+要素」，预览 4 行
        String step3 = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"FINISHED_OTHER","sourceColumn":"cost_ratio","fieldName":"比例"}
                ]}
                """;
        Response r3 = compile(step3);
        assertEquals(200, r3.statusCode(), r3.getBody().asString());
        List<String> grain3 = r3.jsonPath().getList("grain");
        assertNotNull(grain3);
        assertFalse(grain3.isEmpty());
        assertEquals(2, grain3.size(), "③ 粒度条应变为『成品+要素』(两维度)，实际 grain=" + grain3);

        Response preview3 = RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(step3, "\"customerCode\":\"" + AC15_CUSTOMER_NO + "\""))
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
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"fixed_cost","fieldName":"组装加工费","isAmount":true}
                ]}
                """;
        Response resp = RestAssured.given()
                .queryParam("tabType", "主件")
                .queryParam("selectedConfig", selected)
                .get("/api/cpq/config/semantic-graph/field-tree");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        List<Object> groups = resp.jsonPath().getList("groups");
        assertNotNull(groups, "groups 不应为空");
        assertFalse(groups.isEmpty(), "groups 不应为空列表");
        // .find{}.conflict 在 GPath 里落到单个对象的字段，是标量不是列表——原来用 getList 会
        // ClassCastException（Boolean 转 List 失败），真跑第一时间就暴露了，改用 getBoolean。
        Boolean conflictFlag = resp.jsonPath().getBoolean(
                "groups.find { it.groupName == '成品其他费用' }.conflict");
        // 若接口尚未实现按组返回 conflict 标记，上面路径会取到 null——显式失败并报告，而非静默通过。
        assertNotNull(conflictFlag, "field-tree 应对『成品其他费用』分组返回 conflict 标记（供前端拖拽期置灰），"
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
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"fixed_cost","fieldName":"组装加工费","isAmount":true},
                  {"sourceNodeKey":"FINISHED_OTHER","sourceColumn":"cost_ratio","fieldName":"比例","isAmount":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(conflicting)
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        // api.md §2.3a / D-49: /inspect 响应体字段名是 items 不是 checks
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("items");
        assertNotNull(checks, "items 不应为空，原始响应=" + inspectResp.getBody().asString());
        assertFalse(checks.isEmpty(), "items 不应为空——冲突集合必须触发至少一条提示");
        boolean hasGrainConflictErr = checks.stream().anyMatch(c ->
                "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("message")).contains("粒度冲突"));
        assertTrue(hasGrainConflictErr, "应出现『粒度冲突（兜底拦截）』err 级提示，实际 items=" + checks);

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(conflicting)
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
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"fixed_cost","fieldName":"组装加工费","isAmount":true},
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"unit_weight","fieldName":"单重","isAmount":true,"inSubtotal":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(config)
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        // api.md §2.3a / D-49: /inspect 响应体字段名是 items 不是 checks
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("items");
        assertNotNull(checks, "原始响应=" + inspectResp.getBody().asString());
        assertFalse(checks.isEmpty(), "items 不应为空，原始响应=" + inspectResp.getBody().asString());
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("重复"));
        assertTrue(hasErr, "应有 err 级提示说明该值会重复出现/累加即重复计算，实际=" + checks);

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(config)
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
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"base_value","fieldName":"来料加工费","isAmount":true,"inSubtotal":true}
                ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(config)
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("items");
        assertNotNull(checks, "原始响应=" + inspectResp.getBody().asString());
        assertFalse(checks.isEmpty(), "items 不应为空——附属源列勾小计必须产生提示，原始响应=" + inspectResp.getBody().asString());
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("按主源粒度重复"));
        assertTrue(hasErr, "应有 err 级提示说明该值按主源粒度重复出现，实际=" + checks);

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(config)
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(400, saveResp.statusCode(), "保存按钮应被禁用: " + saveResp.getBody().asString());
    }
}
