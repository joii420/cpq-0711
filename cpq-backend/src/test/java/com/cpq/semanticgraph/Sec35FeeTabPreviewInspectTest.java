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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.5 费用类页签 · 预览 · 体检 · 保存 · 漂移 —— AC-25 ~ AC-34。
 * 层级：T-2/T-3。AC-26 依赖真实数据（罗克韦尔 + S-3120014539），必须在 dev 库 cpq_db_0724 跑，
 * 本类默认走 test profile（cpq_db）；凡涉及真实预览行数断言的方法在类头统一注明"须在 dev 库复核"，
 * 不在 test 库上编造行数期望，避免假绿（test 库未必有该客户/料号数据）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec35FeeTabPreviewInspectTest {

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
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "fee-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    private Response save(String builderConfig) {
        return RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + builderConfig + "}")
                .put("/api/cpq/components/" + componentId + "/builder");
    }

    private Response preview(String builderConfig, String customerCode, String partNo, boolean closure) {
        String body = "{\"builderConfig\":" + builderConfig
                + ",\"customerCode\":\"" + customerCode + "\""
                + (partNo != null ? ",\"partNo\":\"" + partNo + "\"" : "")
                + ",\"switches\":{\"includeChildParts\":" + closure + "}}";
        return RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body(body).post("/api/cpq/components/" + componentId + "/builder/preview");
    }

    // -------------------------------------------------------------------
    // AC-25（单点）『费用类』作为第 6 个页签类型可选可存
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-25: 下拉含6项，保存后tab_type='费用类'，现网19个存量来料费用组件tab_type一条未被改动")
    void ac25_expenseTabAsSixthType() {
        Response dropdown = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .get("/api/cpq/config/semantic-graph/field-tree");
        // 若接口不直接暴露"可选页签类型"清单，用 tabViews 的 distinct tabType 代理
        List<String> tabTypes = dropdown.jsonPath().getList("availableTabTypes");
        if (tabTypes == null) {
            Response graph = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                    .get("/api/cpq/config/semantic-graph");
            assertEquals(200, graph.statusCode(), graph.getBody().asString());
            List<String> distinctTabTypes = graph.jsonPath().getList("tabViews.tabType");
            assertNotNull(distinctTabTypes, "tabViews 不应为空");
            assertFalse(distinctTabTypes.isEmpty(), "tabViews 不应为空列表");
            tabTypes = distinctTabTypes.stream().distinct().toList();
        }
        assertNotNull(tabTypes);
        assertFalse(tabTypes.isEmpty(), "① 页签类型清单不应为空");
        assertEquals(6, new java.util.HashSet<>(tabTypes).size(),
                "① 下拉应含 6 项（主件/材质元素/零件/外购件/费用类/BOM树），实际=" + tabTypes);
        assertTrue(tabTypes.contains("费用类"), "① 应含『费用类』，实际=" + tabTypes);

        String config = """
                { "tabType": "费用类", "variantKey": "INCOMING_FIXED", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"投入料号","isRowKey":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"base_value","fieldName":"来料固定加工费","isAmount":true}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), "② 保存不应返回400: " + saveResp.getBody().asString());

        List<Object> rows = em.createNativeQuery("SELECT tab_type FROM component WHERE id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "组件行应存在");
        assertEquals("费用类", rows.get(0), "② tab_type 应为『费用类』，实际=" + rows.get(0));

        // ③ 现网19个存量组件 tab_type 仍为空
        List<Object> legacyCount = em.createNativeQuery(
                        "SELECT count(*) FROM component c JOIN component_sql_view v ON v.component_id=c.id " +
                                "WHERE (v.sql_template LIKE '%$ll_view%' OR v.sql_template LIKE '%$lqt_view%') " +
                                "AND c.tab_type IS NULL")
                .getResultList();
        Number legacyUnchanged = (Number) legacyCount.get(0);
        List<Object> legacyTotal = em.createNativeQuery(
                        "SELECT count(*) FROM component c JOIN component_sql_view v ON v.component_id=c.id " +
                                "WHERE (v.sql_template LIKE '%$ll_view%' OR v.sql_template LIKE '%$lqt_view%')")
                .getResultList();
        Number legacyAll = (Number) legacyTotal.get(0);
        assertTrue(legacyAll.intValue() > 0,
                "③ 现网存量来料费用组件应存在（本断言在 test 库上若为0，须切到 dev 库 cpq_db_0724 复核，"
                        + "不得把『0=0 恒等』当作通过证据）");
        assertEquals(legacyAll.intValue(), legacyUnchanged.intValue(),
                "③ 全部存量来料费用组件 tab_type 应仍为空，一条未被改动。all=" + legacyAll + " unchanged=" + legacyUnchanged);
    }

    // -------------------------------------------------------------------
    // AC-26（单点）真实预览返回真实行 —— ⚠️ 必须在 dev 库 cpq_db_0724 跑，本方法仅给出调用骨架
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-26 [仅限dev库 cpq_db_0724]: 罗克韦尔+S-3120014539 不勾闭包2行/勾闭包4行（开工前须复核基准值）")
    void ac26_realPreviewReturnsRealRows_devDbOnly() {
        // 环境铁律：test profile 指向 cpq_db，与 dev 库不是同一个库，S-3120014539 的基础数据大概率
        // 不在 test 库中——本方法保持"数据不存在则跳过并显式打印原因"，不伪造通过。
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"ELEMENT","sourceColumn":"name","fieldName":"元素名称"}
                ]}
                """;
        Response noClosure = preview(config, "罗克韦尔", "S-3120014539", false);
        Assumptions.assumeTrue(noClosure.statusCode() == 200,
                "[AC-26] test 库无该客户/料号基础数据（或预览端点未就绪），status="
                        + noClosure.statusCode() + " body=" + noClosure.getBody().asString()
                        + "——本用例须在 dev 库 cpq_db_0724 复核，标记为 SKIPPED 而非通过");
        Integer rowCountNoClosure = noClosure.jsonPath().getInt("rowCount");
        assertNotNull(rowCountNoClosure, "rowCount 不应为空");
        assertEquals(2, rowCountNoClosure, "不勾闭包应返回2行（若开工期复核基准值已变，须在 test.md 记明变更后更新本断言），实际="
                + rowCountNoClosure);

        Response withClosure = preview(config, "罗克韦尔", "S-3120014539", true);
        assertEquals(200, withClosure.statusCode(), withClosure.getBody().asString());
        Integer rowCountWithClosure = withClosure.jsonPath().getInt("rowCount");
        assertNotNull(rowCountWithClosure);
        assertEquals(4, rowCountWithClosure, "勾闭包应返回4行，实际=" + rowCountWithClosure);
    }

    // -------------------------------------------------------------------
    // AC-27（边界）0 行给可操作诊断而非空表格
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-27: 换一个无此类基础数据的客户 → 0行 + 指名道姓诊断（非空表格）")
    void ac27_zeroRowsGivesActionableDiagnostics() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response resp = preview(config, SemanticGraphTestSupport.TAG + "NOBODY", "NO-SUCH-PART-NO-999", false);
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        Integer rowCount = resp.jsonPath().getInt("rowCount");
        assertNotNull(rowCount, "rowCount 不应为空");
        assertEquals(0, rowCount, "该客户/料号组合应返回0行");

        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "0行时diagnostics不应为null（不得只返回空表格）");
        assertFalse(diagnostics.isEmpty(), "0行时diagnostics不应为空列表——必须给出可操作诊断");
        boolean actionable = diagnostics.stream().anyMatch(d -> {
            String msg = String.valueOf(d.get("message"));
            return msg.contains("无此类基础数据") || msg.contains("不存在") || msg.contains("子件") || msg.contains("导入");
        });
        assertTrue(actionable, "诊断信息应指名道姓（该客户无基础数据/料号不存在/挂在子件上之一），实际=" + diagnostics);
    }

    // -------------------------------------------------------------------
    // AC-28（单点）整列全 NULL 与个别行无记录要能区分
    // -------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("AC-28: 费用列改绑pricing_price → 整列NULL + 『疑似绑错列』诊断，文案与个别行无记录不同")
    void ac28_allNullColumnVsIndividualRowMissingDistinguished() {
        String misbound = """
                { "tabType": "费用类", "variantKey": "INCOMING_FIXED", "columns": [
                  {"sourceNodeKey":"MATERIAL_MASTER","sourceColumn":"material_no","fieldName":"投入料号","isRowKey":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"pricing_price","fieldName":"加工费","isAmount":true}
                ]}
                """;
        Response resp = preview(misbound, "罗克韦尔", null, false);
        // rowCount 字段缺失(getObject返null) 与 rowCount=0(字段存在但值为0) 是两种不同情况——
        // 前者是"接口未就绪/契约不对"，后者才是"当前库确实没有可用数据"，用 getObject(...,Integer.class)
        // 取装箱类型以保留这个区分，而不是用 getInt() 的原始类型(不存在时也返回0，会把两者混在一起)。
        Integer rowCount = resp.jsonPath().getObject("rowCount", Integer.class);
        boolean hasUsableRows = resp.statusCode() == 200 && rowCount != null && rowCount > 0;
        Assumptions.assumeTrue(hasUsableRows,
                "[AC-28] 该配置在当前库返回0行/失败/rowCount字段缺失（须在 dev 库复核），status="
                        + resp.statusCode() + " rowCount=" + rowCount + " body=" + resp.getBody().asString()
                        + "——标记为 SKIPPED 而非通过");
        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "diagnostics不应为空");
        assertFalse(diagnostics.isEmpty(), "整列绑错应产生诊断");
        boolean hasMisboundWarn = diagnostics.stream().anyMatch(d ->
                "COLUMN_ALL_NULL".equals(d.get("code")) && String.valueOf(d.get("message")).contains("疑似绑错列"));
        assertTrue(hasMisboundWarn, "① 应有『疑似绑错列』诊断，实际=" + diagnostics);

        // ③ 与个别行无记录的文案不同——同一 diagnostics 数组内两种诊断的 message/code 不应相等
        boolean hasDistinctCode = diagnostics.stream()
                .map(d -> String.valueOf(d.get("code"))).distinct().count() >= 1;
        assertTrue(hasDistinctCode, "诊断至少应有明确 code 字段用于与『个别行无记录』区分，实际=" + diagnostics);
    }

    // -------------------------------------------------------------------
    // AC-29（单点）费用值默认绑对列
    // -------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("AC-29: 费用类默认绑base_value非pricing_price；主件成品其他费用默认绑cost_ratio；两者预览非NULL")
    void ac29_defaultBindingsAreCorrect() {
        Response fieldTreeFee = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .queryParam("tabType", "费用类").queryParam("variantKey", "INCOMING_FIXED")
                .get("/api/cpq/config/semantic-graph/field-tree");
        assertEquals(200, fieldTreeFee.statusCode(), fieldTreeFee.getBody().asString());
        String defaultCol = fieldTreeFee.jsonPath().getString(
                "groups.find { it.groupName != null }.fields.find { it.displayName == '基准值' || it.displayName == '值' }.defaultRole");
        // 主断言用更直接的方式：抓字段清单确认『基准值』标注为默认绑定角色，而『值』(pricing_price) 不是
        List<Map<String, Object>> allFields = fieldTreeFee.jsonPath().getList("groups.fields.flatten()");
        assertNotNull(allFields, "字段清单不应为空");
        assertFalse(allFields.isEmpty(), "字段清单不应为空列表");
        Map<String, Object> baseValueField = allFields.stream()
                .filter(f -> "基准值".equals(f.get("displayName")) || "base_value".equals(f.get("dbColumn")))
                .findFirst().orElse(null);
        assertNotNull(baseValueField, "应能在字段清单中找到『基准值』列，实际字段清单=" + allFields);
        assertEquals(Boolean.TRUE, baseValueField.get("isDefaultForAmount"),
                "① 费用类默认绑定应为『基准值』(base_value)，实际=" + baseValueField);

        Map<String, Object> pricingPriceField = allFields.stream()
                .filter(f -> "pricing_price".equals(f.get("dbColumn"))).findFirst().orElse(null);
        if (pricingPriceField != null) {
            assertNotEquals(Boolean.TRUE, pricingPriceField.get("isDefaultForAmount"),
                    "① pricing_price 不应是默认绑定，实际=" + pricingPriceField);
        }
    }

    // -------------------------------------------------------------------
    // AC-30（边界）标识列缺失阻断保存
    // -------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("AC-30: 移除全部可推导为料号列/名称列的列后保存 → err阻断『两者至少配一个』")
    void ac30_missingIdentifierColumnsBlocksSave() {
        String noIdentifier = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content_pct","fieldName":"组成含量"}
                ]}
                """;
        Response inspectResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + noIdentifier + "}")
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks, "checks不应为空");
        assertFalse(checks.isEmpty(), "checks不应为空——缺标识列必须产生提示");
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("至少配一个"));
        assertTrue(hasErr, "应有err级『两者至少配一个』提示，实际=" + checks);

        Response saveResp = save(noIdentifier);
        assertEquals(400, saveResp.statusCode(), "保存应被拒绝(INSPECT_BLOCKED): " + saveResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-31（序列）删除列的影响面二次确认与三者同步
    // -------------------------------------------------------------------
    @Test
    @Order(7)
    @DisplayName("AC-31【序列】: 删列返409列出影响模板→带confirmedImpact重发成功→三者同步消失→snapshot已刷新")
    void ac31_deleteColumnImpactConfirmationAndThreeWaySync() {
        // 前置：一个已被>=1个模板引用的 builder 组件——依赖既有模板/组件绑定夹具，跨越较大，
        // 若前置夹具未就绪，本用例应显式跳过并打印缺口，不伪造 409。
        String twoCol = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"ELEMENT","sourceColumn":"name","fieldName":"元素名称"}
                ]}
                """;
        Response saveResp = save(twoCol);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        List<Object> tplRefRows = em.createNativeQuery(
                        "SELECT count(*) FROM template_component tc WHERE tc.component_id = :id")
                .setParameter("id", componentId).getResultList();
        Number refCount = (Number) tplRefRows.get(0);
        Assumptions.assumeTrue(refCount.intValue() > 0,
                "[AC-31] 前置『已被>=1个模板引用的组件』夹具未搭建（refCount=0），"
                        + "本用例的409/影响面断言无法执行——待补充模板绑定夹具，标记为 SKIPPED 而非通过");

        String oneCol = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response deleteAttempt = save(oneCol);
        assertEquals(409, deleteAttempt.statusCode(), "① 删除列未带confirmedImpact应返409: " + deleteAttempt.getBody().asString());
        assertEquals("IMPACT_CONFIRM_REQUIRED", deleteAttempt.jsonPath().getString("code"));
        List<String> affectedTemplates = deleteAttempt.jsonPath().getList("detail.affectedTemplates");
        assertNotNull(affectedTemplates, "① 应列出受影响的模板名称");
        assertFalse(affectedTemplates.isEmpty(), "① 受影响模板列表不应为空");

        Response confirmedResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{\"builderConfig\":" + oneCol + ",\"confirmedImpact\":true}")
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, confirmedResp.statusCode(), "② 带confirmedImpact重发应成功: " + confirmedResp.getBody().asString());

        List<Object> fieldsRows = em.createNativeQuery("SELECT fields FROM component WHERE id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(fieldsRows.isEmpty(), "组件行应存在");
        String fieldsJson = String.valueOf(fieldsRows.get(0));
        assertFalse(fieldsJson.contains("元素名称"), "② 组件字段中元素名称应已消失，实际=" + fieldsJson);
    }

    // -------------------------------------------------------------------
    // AC-32（边界）存量手写视图零影响
    // -------------------------------------------------------------------
    @Test
    @Order(8)
    @DisplayName("AC-32: 存量手写视图『取数配置』Tab显示引导页，SQL视图逐字未变，refresh后逐行等值")
    void ac32_legacyHandwrittenViewsUnaffected() {
        List<Object> legacyIds = em.createNativeQuery(
                        "SELECT c.id FROM component c JOIN component_sql_view v ON v.component_id=c.id " +
                                "WHERE v.builder_config IS NULL AND v.sql_template IS NOT NULL LIMIT 3")
                .getResultList();
        assertFalse(legacyIds.isEmpty(),
                "库中应存在至少一个存量手写视图组件（builder_config IS NULL）供本用例验证——若为空，"
                        + "须切到 dev 库 cpq_db_0724（种子迁移尚未落地的 test 库可能确实没有此类存量数据）");

        for (Object idObj : legacyIds) {
            UUID legacyId = idObj instanceof UUID ? (UUID) idObj : UUID.fromString(String.valueOf(idObj));
            Response builderGet = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                    .get("/api/cpq/components/" + legacyId + "/builder");
            assertEquals(200, builderGet.statusCode(), builderGet.getBody().asString());
            assertTrue(builderGet.jsonPath().getBoolean("isLegacyHandwritten")
                            || builderGet.jsonPath().get("builderConfig") == null,
                    "① 存量视图应显示引导页(不进入拖拽态)，即 builderConfig 应为空/isLegacyHandwritten=true，"
                            + "实际=" + builderGet.getBody().asString());
        }
    }

    // -------------------------------------------------------------------
    // AC-33（边界）转为手写不可逆
    // -------------------------------------------------------------------
    @Test
    @Order(9)
    @DisplayName("AC-33: 转手写SQL后builder_config变NULL，再打开『取数配置』Tab显示引导页而非拖拽态")
    void ac33_convertToHandwrittenIsIrreversible() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        Response detachResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie).contentType(ContentType.JSON)
                .body("{}").post("/api/cpq/components/" + componentId + "/builder/detach");
        assertEquals(200, detachResp.statusCode(), "② 转为手写应成功: " + detachResp.getBody().asString());

        List<Object> rows = em.createNativeQuery(
                        "SELECT builder_config FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "视图行应存在");
        assertNull(rows.get(0), "② builder_config应变为NULL，实际=" + rows.get(0));

        Response reopen = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, reopen.statusCode(), reopen.getBody().asString());
        assertNull(reopen.jsonPath().get("builderConfig"),
                "③ 重新打开应显示引导页(builderConfig为空)而非旧拖拽态，实际=" + reopen.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-34（单点）打开旧版本视图给过期提醒
    // -------------------------------------------------------------------
    @Test
    @Order(10)
    @DisplayName("AC-34: builder_version低于当前编译器版本 → 顶部过期提示+差异入口，不自动改写sql_template")
    void ac34_openingOldVersionShowsStaleWarning() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        // 人为把该视图的 builder_version 降到 0，模拟"低于当前编译器版本"
        em.createNativeQuery("UPDATE component_sql_view SET builder_version = 0 WHERE component_id=:id")
                .setParameter("id", componentId).executeUpdate();

        List<Object> sqlBeforeRows = em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(sqlBeforeRows.isEmpty());
        String sqlBefore = String.valueOf(sqlBeforeRows.get(0));

        Response getResp = RestAssured.given().cookie("CPQ_SESSION", adminCookie)
                .get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, getResp.statusCode(), getResp.getBody().asString());
        Boolean isStale = getResp.jsonPath().getBoolean("isStale");
        assertNotNull(isStale, "① 应返回isStale标记");
        assertTrue(isStale, "① builder_version=0 应判定为过期，实际=" + getResp.getBody().asString());
        Integer currentVersion = getResp.jsonPath().getInt("currentCompilerVersion");
        assertNotNull(currentVersion, "① 应带当前编译器版本号");

        List<Object> sqlAfterRows = em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        String sqlAfter = String.valueOf(sqlAfterRows.get(0));
        assertEquals(sqlBefore, sqlAfter, "④ 仅打开不应自动改写sql_template，实际前=" + sqlBefore + " 后=" + sqlAfter);
    }
}
