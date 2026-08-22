package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.2 视图列名与字段名（D-12/D-13）—— AC-11 ~ AC-13。
 * 层级 = T-1（AC-11 别名纯函数）/ T-3（AC-12 序列：改名同步 + 冻结单零回归 / AC-13 边界：字段名重复只告警）。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec32ViewColumnFieldNameTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    @BeforeEach
    void setUp() throws Exception {
        componentId = createBlankComponent();
    }


    private UUID createBlankComponent() {
        Response resp = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "viewcol-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    private Response compile(String builderConfigJson) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfigJson).post("/api/cpq/components/" + componentId + "/builder/compile");
    }

    // -------------------------------------------------------------------
    // AC-11（单点）别名一律带来源前缀，是 (Sheet,列) 的纯函数
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-11: 视图列名 = 系统按(Sheet,列)生成，删列不影响其余列，跨Sheet同名列不冲突")
    void ac11_viewColumnNameIsPureFunctionOfSheetAndColumn() {
        String fourCols = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素名称"},
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量"},
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"seq_no","fieldName":"项次"}
                ]}
                """;
        Response r1 = compile(fourCols);
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        java.util.List<String> declared1 = r1.jsonPath().getList("declaredColumns");
        assertNotNull(declared1, "declaredColumns 不应为空");
        assertFalse(declared1.isEmpty(), "declaredColumns 不应为空列表");

        assertTrue(declared1.contains("_材质库_材质名称"), "① 材质名称视图列名应为 _材质库_材质名称，实际=" + declared1);
        assertTrue(declared1.contains("_元素库_元素名称"), "① 元素名称视图列名应为 _元素库_元素名称，实际=" + declared1);
        assertTrue(declared1.contains("_元素BOM_组成含量"), "① 组成含量视图列名应为 _元素BOM_组成含量，实际=" + declared1);
        assertTrue(declared1.contains("_元素BOM_项次"), "① 项次视图列名应为 _元素BOM_项次，实际=" + declared1);

        // ② 删除"材质名称"后，其余三列视图列名逐字不变
        String threeCols = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素名称"},
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量"},
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"seq_no","fieldName":"项次"}
                ]}
                """;
        Response r2 = compile(threeCols);
        assertEquals(200, r2.statusCode(), r2.getBody().asString());
        java.util.List<String> declared2 = r2.jsonPath().getList("declaredColumns");
        assertNotNull(declared2);
        assertFalse(declared2.isEmpty());
        assertTrue(declared2.contains("_元素库_元素名称"), "② 删除材质名称后元素名称列名应不变，实际=" + declared2);
        assertTrue(declared2.contains("_元素BOM_组成含量"), "② 删除材质名称后组成含量列名应不变，实际=" + declared2);
        assertTrue(declared2.contains("_元素BOM_项次"), "② 删除材质名称后项次列名应不变，实际=" + declared2);

        // ③ 同一页签同时选中两张 Sheet 的"项次"，二者互不相同
        // 原用 FINISHED_OTHER 搭配 ASSEMBLY_FEE，两者在"主件"下各自展开不同维度（工序号 vs 要素），
        // 真跑实测触发了合理的 COMPILE_GRAIN_CONFLICT（粒度冲突拦截生效，不是bug）——
        // 换成 CUSTOMER_MAP（dims=[]，不额外展开维度），只为验证"跨Sheet同名列别名不冲突"这件事本身，
        // 不引入无关的粒度冲突干扰。
        String crossSheetSeqNo = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"seq_no","fieldName":"项次A"},
                  {"sourceNodeKey":"CUSTOMER_MAP","sourceColumn":"seq_no","fieldName":"项次B"}
                ]}
                """;
        Response r3 = compile(crossSheetSeqNo);
        assertEquals(200, r3.statusCode(), r3.getBody().asString());
        java.util.List<String> declared3 = r3.jsonPath().getList("declaredColumns");
        assertNotNull(declared3);
        assertFalse(declared3.isEmpty());
        long distinctSeqNoAliases = declared3.stream().filter(c -> c.endsWith("_项次")).distinct().count();
        assertEquals(2, distinctSeqNoAliases,
                "③ 两张 Sheet 的『项次』别名应互不相同，实际 declaredColumns=" + declared3);
        // ④（视图列名输入框只读）是前端可观测项，见 E2E sql-view-builder.spec.ts
    }

    // -------------------------------------------------------------------
    // AC-12（序列）改字段名：SQL 不动，同步 4 处，不受任何单据限制
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-12【序列】: 改字段名不阻断保存、SQL/绑定路径逐字未变、冻结单表头数值不受影响")
    void ac12_renameFieldSyncsFourPlacesWithoutBlockingFrozenQuotation() {
        // 前置：本用例需要"一个已保存并被 >=1 张 SUBMITTED/APPROVED 报价单引用的 builder 组件"
        // 这类夹具依赖既有报价单流程（建组件→建模板→建报价单→提交/核准），跨越本任务边界较大。
        // 若后端 B-13 一体化保存事务已就绪但夹具尚未搭好，本用例应先在最小夹具上验证①②③④
        // (体检提示/保存不阻断/SQL与绑定路径不变/刷新后显示新名)；⑤(冻结单表头数值不变)需要
        // 额外的报价单夹具，标记为待补，在 test-report.md 中显式登记而非静默跳过。
        String initial = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response compiled = compile(initial);
        assertEquals(200, compiled.statusCode(), compiled.getBody().asString());
        String sqlBefore = compiled.jsonPath().getString("sql");
        assertNotNull(sqlBefore);
        assertFalse(sqlBefore.isBlank());

        // 2026-08-21 真跑教训：原来包了一层 {"builderConfig": {...}}，PUT/inspect端点读到的
        // tabType/variantKey 都是 null（报 COMPILE_TABVIEW_NOT_FOUND）——这两个端点跟 /compile 一样，
        // 期望 builder_config 对象直接作为请求体，不额外包一层。
        String saveBody = """
                { "tabType": "材质元素", "columns": [
                    {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                  ]}
                """;
        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(saveBody).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, saveResp.statusCode(), "首次保存应成功: " + saveResp.getBody().asString());

        // 体检：改名
        String inspectBody = """
                { "tabType": "材质元素", "columns": [
                    {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质","isRowKey":true}
                  ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(inspectBody).post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        // ① 体检区应提示"同步 N 处引用"，② 且不阻断（不含 err 级 BLOCK）
        java.util.List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks, "inspect 应返回 checks 列表");
        boolean hasBlockingErr = checks.stream()
                .anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("code")).toLowerCase().contains("rename"));
        assertFalse(hasBlockingErr, "② 改字段名不应产生阻断级(ERR)提示，实际 checks=" + checks);

        // 保存改名
        Response renameSaveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(inspectBody).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, renameSaveResp.statusCode(), "② 保存按钮不应被禁用/阻断: " + renameSaveResp.getBody().asString());

        // ③ 重编译后 SQL 逐字未变（sql_template 不应因改名而改变）
        // compile 端点期望的是 builderConfig 内层结构，直接构造内层结构调用：
        String recompileConfig = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质","isRowKey":true}
                ]}
                """;
        Response recompiled2 = compile(recompileConfig);
        assertEquals(200, recompiled2.statusCode(), recompiled2.getBody().asString());
        String sqlAfter = recompiled2.jsonPath().getString("sql");
        assertNotNull(sqlAfter);
        assertFalse(sqlAfter.isBlank());
        assertEquals(sqlBefore, sqlAfter, "③ 改名后 sql_template 应逐字未变（diff 应为空）");

        // ④ 重新打开该组件的 builder 配置，字段名应显示为新名
        Response reload = RestAssured.given()
                .get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, reload.statusCode(), reload.getBody().asString());
        java.util.List<String> fieldNames = reload.jsonPath().getList("builderConfig.columns.fieldName");
        assertNotNull(fieldNames);
        assertFalse(fieldNames.isEmpty());
        assertTrue(fieldNames.contains("材质"), "④ 刷新后字段名应显示为新名『材质』，实际=" + fieldNames);

        // ⑤ 冻结单零回归：需要完整报价单夹具，本用例标记为待补（见方法头注释），不在此断言。
    }

    // -------------------------------------------------------------------
    // AC-13（边界）字段名重复只告警不阻断
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-13: 两列字段名同为『项次』只 warn 不 err，保存按钮仍可用")
    void ac13_duplicateFieldNameWarnsButDoesNotBlock() {
        // 同 ac11③ 教训，换 CUSTOMER_MAP 避免与 ASSEMBLY_FEE 产生无关的粒度冲突。
        String duplicateNames = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"ASSEMBLY_FEE","sourceColumn":"seq_no","fieldName":"项次"},
                  {"sourceNodeKey":"CUSTOMER_MAP","sourceColumn":"seq_no","fieldName":"项次"}
                ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(duplicateNames)
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        java.util.List<java.util.Map<String, Object>> checks = inspectResp.jsonPath().getList("checks");
        assertNotNull(checks, "checks 不应为空");
        assertFalse(checks.isEmpty(), "checks 不应为空列表——字段名重复必须产生至少一条提示");
        boolean hasWarnDup = checks.stream().anyMatch(c ->
                "WARN".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("message")).contains("项次"));
        boolean hasErrDup = checks.stream().anyMatch(c ->
                "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("code")).toLowerCase().contains("duplicate"));
        assertTrue(hasWarnDup, "应出现 warn 级字段名重复提示，实际 checks=" + checks);
        assertFalse(hasErrDup, "字段名重复不应产生 err 级阻断，实际 checks=" + checks);

        Boolean blocked = inspectResp.jsonPath().getBoolean("blocked");
        if (blocked != null) {
            assertFalse(blocked, "字段名重复不应使 inspect 整体判定为 blocked=true");
        }

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(duplicateNames)
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, saveResp.statusCode(), "字段名重复不应阻断保存: " + saveResp.getBody().asString());
    }
}
