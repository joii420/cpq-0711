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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.2 视图列名与字段名（D-12 / D-13）—— AC-11 ~ AC-13。
 *
 * <h3>🔄 2026-09-05（用户裁决 {@code D-123}）：本类<b>整体保留</b>，只把夹具换成 v9 对象</h3>
 * 三条 AC 验的都是<b>与数据集无关的通用性质</b>，v9 上依然成立，且 <b>AC-101~AC-126 里没有任何一条覆盖它们</b>：
 * <ul>
 *   <li><b>AC-11</b> 视图列名 = {@code (Sheet, 列)} 的纯函数（改字段名不影响它、删一列不影响其余列）。
 *       ⚠️ {@code AC-110} 只验「别名规则按方言分两种形态」，<b>不验纯函数性/稳定性</b> —— 两者不可互相替代。</li>
 *   <li><b>AC-12</b> 改字段名不阻断保存、SQL 逐字未变、刷新后显示新名。</li>
 *   <li><b>AC-13</b> 字段名重复只 {@code WARN} 不 {@code ERR}、保存仍可用。</li>
 * </ul>
 *
 * <h3>📌 唯一被作废的是 AC-11③（保留历史价值说明，勿删）</h3>
 * <p>原 {@code ac11③}：「同一页签同时选中<b>两张 Sheet</b> 的『项次』，二者别名互不相同」——
 * 它需要一个页签视图上挂两个 SHEET 节点。v9 的页签视图<b>每个只挂 1 个 SHEET</b>
 * （2026-09-05 实测：{@code semantic_tab_view_node} 里 {@code role='AUX'} 且节点为 SHEET 的行 = 0；
 * 唯一的 AUX 是 QUOTE/材质元素 上的 {@code FUNC_ELEMENT_PRICE}，那是 FUNCTION 不是 Sheet）
 * ⇒ <b>「跨 Sheet 同名列」这个场景在 v9 构造不出来</b>，判据没有对象。
 * <br>裁决来源：用户 2026-09-05（{@code D-123}），技术前提 {@code D-78}（V6 AC 整块作废）+ {@code V413}。
 * <br>接替者：无同类判据；防线由 {@link #tombstone_crossSheetPremiseStillHolds()} 守着 ——
 * 一旦某个页签视图重新挂上第 2 个 SHEET 节点，本条变红要求把跨 Sheet 别名唯一性重新验起来。
 *
 * <p>层级 = T-1（AC-11 别名纯函数）/ T-3（AC-12 序列：改名同步 / AC-13 边界：字段名重复只告警）。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec32ViewColumnFieldNameTest — AC-11/12/13 换 v9 对象后保留；仅 AC-11③（跨 Sheet）作废留碑")
class Sec32ViewColumnFieldNameTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    /** v9 QUOTE/材质元素 的锚点 node_key（从库里现查，🚫 不写死 —— 种子由脚本机器生成）。 */
    private String anchorKey;
    /** 该锚点的 Sheet 简称，视图列名 {@code _<简称>_<列显示名>} 的前半段。 */
    private String anchorShortName;

    @BeforeEach
    void setUp() {
        componentId = createBlankComponent();
        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.short_name FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        anchorKey = String.valueOf(anchor[0]);
        anchorShortName = String.valueOf(anchor[1]);
        assertNotNull(anchorKey, "环境前置未就绪：查不到 QUOTE/材质元素 的锚点节点，本类判定为【未验证】");
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

    /** 取锚点上某个 db_column 的<b>登记显示名</b> —— 视图列名的后半段就是它（而不是用户填的 fieldName）。 */
    private String displayNameOf(String dbColumn) {
        return String.valueOf(em.createNativeQuery(
                        "SELECT c.display_name FROM semantic_node_column c "
                                + "JOIN semantic_node n ON n.id = c.node_id "
                                + "WHERE n.dialect='QUOTE' AND n.node_key=:k AND c.db_column=:col AND c.status='ACTIVE'")
                .setParameter("k", anchorKey).setParameter("col", dbColumn).getSingleResult());
    }

    // ===================================================================
    // 🪦 AC-11③ 作废前提守卫（会真的执行）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 AC-11③ 作废前提守卫: 没有任何页签视图挂着 2 个及以上 SHEET 节点（跨 Sheet 场景构造不出）")
    void tombstone_crossSheetPremiseStillHolds() {
        long views = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM semantic_tab_view WHERE status='ACTIVE'").getSingleResult()).longValue();
        long multiSheetViews = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM (SELECT tvn.view_id FROM semantic_tab_view_node tvn "
                        + "JOIN semantic_node n ON n.id = tvn.node_id "
                        + "WHERE n.node_kind='SHEET' AND tvn.status='ACTIVE' "
                        + "GROUP BY tvn.view_id HAVING count(*) > 1) t").getSingleResult()).longValue();
        System.out.println("[🪦 AC-11③ 留碑] ACTIVE 页签视图=" + views + " · 挂 2 个及以上 SHEET 的视图=" + multiSheetViews);

        assertTrue(views > 0, "🚨 语义图里一个 ACTIVE 页签视图都没有 —— 这不是「作废前提成立」，是种子没就位。"
                + "本条判定为【未验证】，🚫 不许当成通过。");
        assertEquals(0L, multiSheetViews,
                "🚦 AC-11③「跨 Sheet 同名列别名不冲突」的作废前提被推翻：有 " + multiSheetViews
                        + " 个页签视图挂了 2 个及以上 SHEET 节点。\n"
                        + "  2026-09-05 作废该断言的唯一理由是「v9 每个页签视图只挂 1 个 SHEET，跨 Sheet 场景构造不出来」。\n"
                        + "  现在能构造了 ⇒ 必须把跨 Sheet 别名唯一性重新验起来（历史实现见本类 git 历史）。\n"
                        + "  🚫 不许直接删掉本断言了事 —— 那等于把「两张 Sheet 的同名列会不会撞同一个视图列名」这道防线一起删了。");
    }

    // ===================================================================
    // AC-11（单点）视图列名 = (Sheet, 列) 的纯函数
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-11(v9): 视图列名 = _<Sheet简称>_<列登记名>，与用户填的字段名无关；删一列不影响其余列")
    void ac11_viewColumnNameIsPureFunctionOfSheetAndColumn() {
        String dnPartNo = displayNameOf("material_part_no");
        String dnElement = displayNameOf("element_code");
        String dnContent = displayNameOf("content_pct");
        String dnSeq = displayNameOf("item_seq");
        System.out.println("[AC-11(v9)] 简称=" + anchorShortName + " 列登记名=["
                + dnPartNo + "," + dnElement + "," + dnContent + "," + dnSeq + "]");

        String fourCols = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"material_part_no","fieldName":"随便起的名A","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"%s","sourceColumn":"element_code","fieldName":"随便起的名B"},
                  {"sourceNodeKey":"%s","sourceColumn":"content_pct","fieldName":"随便起的名C"},
                  {"sourceNodeKey":"%s","sourceColumn":"item_seq","fieldName":"随便起的名D"}
                ]}
                """.formatted(anchorKey, anchorKey, anchorKey, anchorKey);
        Response r1 = compile(fourCols);
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        List<String> declared1 = r1.jsonPath().getList("declaredColumns");
        assertNotNull(declared1, "declaredColumns 不应为空");
        assertFalse(declared1.isEmpty(), "declaredColumns 不应为空列表");
        System.out.println("[AC-11(v9)] 四列 declaredColumns=" + declared1);

        // ① 列名是 (Sheet简称, 列登记名) 的纯函数 —— 与用户填的 fieldName（这里故意起成「随便起的名X」）无关
        String expPartNo = "_" + anchorShortName + "_" + dnPartNo;
        String expElement = "_" + anchorShortName + "_" + dnElement;
        String expContent = "_" + anchorShortName + "_" + dnContent;
        String expSeq = "_" + anchorShortName + "_" + dnSeq;
        assertTrue(declared1.contains(expPartNo), "① 应含 " + expPartNo + "，实际=" + declared1);
        assertTrue(declared1.contains(expElement), "① 应含 " + expElement + "，实际=" + declared1);
        assertTrue(declared1.contains(expContent), "① 应含 " + expContent + "，实际=" + declared1);
        assertTrue(declared1.contains(expSeq), "① 应含 " + expSeq + "，实际=" + declared1);
        assertTrue(declared1.stream().noneMatch(c -> c.contains("随便起的名")),
                "① 视图列名不得掺入用户填的字段名（那样就不是 (Sheet,列) 的纯函数了），实际=" + declared1);

        // ② 删掉第一列后，其余三列的视图列名逐字不变
        String threeCols = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"element_code","fieldName":"随便起的名B","isRowKey":true},
                  {"sourceNodeKey":"%s","sourceColumn":"content_pct","fieldName":"随便起的名C"},
                  {"sourceNodeKey":"%s","sourceColumn":"item_seq","fieldName":"随便起的名D"}
                ]}
                """.formatted(anchorKey, anchorKey, anchorKey);
        Response r2 = compile(threeCols);
        assertEquals(200, r2.statusCode(), r2.getBody().asString());
        List<String> declared2 = r2.jsonPath().getList("declaredColumns");
        assertNotNull(declared2);
        assertFalse(declared2.isEmpty());
        assertTrue(declared2.contains(expElement), "② 删首列后其余列名应逐字不变，缺 " + expElement + "，实际=" + declared2);
        assertTrue(declared2.contains(expContent), "② 删首列后其余列名应逐字不变，缺 " + expContent + "，实际=" + declared2);
        assertTrue(declared2.contains(expSeq), "② 删首列后其余列名应逐字不变，缺 " + expSeq + "，实际=" + declared2);
        assertFalse(declared2.contains(expPartNo), "② 被删的列不应还在，实际=" + declared2);

        // ③【已作废】跨 Sheet 同名列 —— v9 构造不出（见类头碑文 + tombstone_crossSheetPremiseStillHolds）
    }

    // ===================================================================
    // AC-12（序列）改字段名：SQL 不动，同步 4 处，不受任何单据限制
    // ===================================================================
    @Test
    @Order(3)
    @DisplayName("AC-12(v9)【序列】: 改字段名不阻断保存、SQL 逐字未变、重新打开显示新名")
    void ac12_renameFieldSyncsWithoutBlocking() {
        String initial = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"material_part_no","fieldName":"材质料号","isRowKey":true,"isPartNo":true}
                ]}
                """.formatted(anchorKey);
        Response compiled = compile(initial);
        assertEquals(200, compiled.statusCode(), compiled.getBody().asString());
        String sqlBefore = compiled.jsonPath().getString("sql");
        assertNotNull(sqlBefore);
        assertFalse(sqlBefore.isBlank());

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(initial).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, saveResp.statusCode(), "首次保存应成功: " + saveResp.getBody().asString());

        // 体检：改名（fieldName 材质料号 → 材质）
        String renamed = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"material_part_no","fieldName":"材质","isRowKey":true,"isPartNo":true}
                ]}
                """.formatted(anchorKey);
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(renamed).post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<Map<String, Object>> items = inspectResp.jsonPath().getList("items");
        assertNotNull(items, "inspect 应返回 items 列表，原始响应=" + inspectResp.getBody().asString());
        System.out.println("[AC-12(v9)] 改名体检 items=" + items);
        boolean hasBlockingErr = items.stream()
                .anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("code")).toLowerCase().contains("rename"));
        assertFalse(hasBlockingErr, "① 改字段名不应产生阻断级(ERR)提示，实际 items=" + items);

        Response renameSaveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(renamed).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, renameSaveResp.statusCode(),
                "② 改名不应阻断保存: " + renameSaveResp.getBody().asString());

        // ③ 重编译后 SQL 逐字未变（视图列名是 (Sheet,列) 的纯函数，与 fieldName 无关）
        Response recompiled = compile(renamed);
        assertEquals(200, recompiled.statusCode(), recompiled.getBody().asString());
        String sqlAfter = recompiled.jsonPath().getString("sql");
        assertNotNull(sqlAfter);
        assertFalse(sqlAfter.isBlank());
        assertEquals(sqlBefore, sqlAfter, "③ 改名后 sql_template 应逐字未变（diff 应为空）");

        // ④ 重新打开该组件的 builder 配置，字段名应显示为新名
        Response reload = RestAssured.given().get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, reload.statusCode(), reload.getBody().asString());
        List<String> fieldNames = reload.jsonPath().getList("builderConfig.columns.fieldName");
        assertNotNull(fieldNames);
        assertFalse(fieldNames.isEmpty());
        assertTrue(fieldNames.contains("材质"), "④ 刷新后字段名应显示为新名『材质』，实际=" + fieldNames);

        // ⑤ 冻结单零回归：需要完整报价单夹具（建组件→建模板→建报价单→提交），跨越本任务边界；
        //    自 2026-08-21 起一直登记为「待补」，本轮退役工作不改变其状态，仍是【未验证】。
    }

    // ===================================================================
    // AC-13（边界）字段名重复只告警不阻断
    // ===================================================================
    @Test
    @Order(4)
    @DisplayName("AC-13(v9): 两列字段名同为『项次』只 warn 不 err，保存仍成功")
    void ac13_duplicateFieldNameWarnsButDoesNotBlock() {
        // 补一列料号列满足 B-27「标识列至少配一个」，否则会串进一条与本用例无关的 ERR（污染 blocked 断言）。
        String duplicateNames = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"material_part_no","fieldName":"材质料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"%s","sourceColumn":"item_seq","fieldName":"项次"},
                  {"sourceNodeKey":"%s","sourceColumn":"loss_rate","fieldName":"项次"}
                ]}
                """.formatted(anchorKey, anchorKey, anchorKey);
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(duplicateNames).post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<Map<String, Object>> items = inspectResp.jsonPath().getList("items");
        assertNotNull(items, "items 不应为空");
        assertFalse(items.isEmpty(), "items 不应为空列表——字段名重复必须产生至少一条提示，"
                + "原始响应=" + inspectResp.getBody().asString());
        System.out.println("[AC-13(v9)] 重名体检 items=" + items);

        boolean hasWarnDup = items.stream().anyMatch(c ->
                "WARN".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("message")).contains("项次"));
        boolean hasErrDup = items.stream().anyMatch(c ->
                "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                        && String.valueOf(c.get("code")).toLowerCase().contains("duplicate"));
        assertTrue(hasWarnDup, "应出现 warn 级字段名重复提示，实际 items=" + items);
        assertFalse(hasErrDup, "字段名重复不应产生 err 级阻断，实际 items=" + items);

        Boolean blocked = inspectResp.jsonPath().getBoolean("blocked");
        if (blocked != null) {
            assertFalse(blocked, "字段名重复不应使 inspect 整体判定为 blocked=true，"
                    + "原始响应=" + inspectResp.getBody().asString());
        }

        Response saveResp = RestAssured.given().contentType(ContentType.JSON)
                .body(duplicateNames).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, saveResp.statusCode(), "字段名重复不应阻断保存: " + saveResp.getBody().asString());
    }
}
