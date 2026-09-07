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
 * 🪦 <b>本类原覆盖 需求文档.md §3.6 CI 断言 · 方言参数化（v8 期 AC-35 / AC-36 / AC-37）——
 * 其中 2 条已被 v9 用例接替、1 条只保留与方言无关的第④项</b>。
 *
 * <h3>裁决来源</h3>
 * 用户 2026-09-05 裁决（{@code 需求文档.md} 的 {@code D-123}）；三条的技术前提各不相同，逐条写在下表。
 *
 * <h3>📌 处置逐条说明（保留历史价值说明，勿删）</h3>
 * <table>
 *   <tr><th>方法</th><th>它验的是什么</th><th>处置</th><th>接替者</th></tr>
 *   <tr><td>{@code ac35_edgeCardinalityCiAssertion_negativeCase}</td>
 *       <td>CI 反证：正常数据下全部 {@code MANY_TO_ONE} 边的右侧连接键在目标表中唯一；
 *           把一条真实一对多的边人为改成 {@code MANY_TO_ONE} 后断言必须变红并指名
 *           （哪条边 / 哪个键 / 重复几组）</td>
 *       <td><b>作废（已被覆盖）</b>。原实现自身的失败形态已被 {@code D-120} 判定为
 *           <b>「断言从未执行」型假绿</b>：{@code V416} 把 28 条桥边改成 {@code NARROW} 后，
 *           图里 {@code MANY_TO_ONE} 只剩 1 条且指向 {@code physical_table IS NULL} 的 FUNCTION 节点，
 *           循环转 1 圈全跳过、一句断言都没跑，测试照样报绿。<br>
 *           ⚠️ 注意：<b>作废的是这份实现，不是这道防线</b> —— {@code D-120} 明确裁决「🚫 不作废」，
 *           改成「不变量 + 阳性对照」重写</td>
 *       <td>{@link SemanticEdgeCardinalityReconcileTest}（S-29-a 重写版：保留
 *           {@code asserted > 0} 下限守卫 + 28 条 NARROW 边输入收窄唯一性的阳性对照）<br>
 *           + <b>{@code AC-121}</b>（①当前数据为绿 ②③反证：新增右键重复的 {@code MANY_TO_ONE} 边必须被拒并指名）</td></tr>
 *   <tr><td>{@code ac36_handlerReconcileCheckExistsAndPassesNormally}</td>
 *       <td>{@code POST /validate} 的四道校验里存在 {@code HANDLER_RECONCILE} 分项，且正常路径下不告警</td>
 *       <td><b>作废（已被覆盖）</b>。{@code D-121} 已就 S-29-b 单独裁决「明确作废 + 留碑」：
 *           对账的另一侧整个换人了（V6 的 17 个 {@code Q*Handler} → {@code com.cpq.dataset} 的通用参数化导入器），
 *           实测 {@code semantic_node.source_handler} 非空 = <b>0</b></td>
 *       <td>{@link SemanticHandlerReconcileTest}（S-29-b 的碑与前提守卫）<br>
 *           + <b>{@code AC-104}</b>（列声明 ⇄ {@code information_schema} 双向无差集）
 *           + {@code DatasetSchemaSelfCheck}（启动期，Registry 声明 ⇄ {@code information_schema}）</td></tr>
 *   <tr><td>{@code ac37_dialectParameterizationProducesTwoForms} ①②③</td>
 *       <td>方言参数化：同一节点声明分别以 {@code QUOTE} / {@code COSTING} 编译，
 *           核对别名规则、子件收窄、{@code customer_no + is_current} vs {@code :versionFilter(...)}、
 *           {@code view_version} 约定列</td>
 *       <td><b>作废</b>。{@code D-77} 把 {@code dialect} 扩成三值
 *           {@code QUOTE}/{@code COST_BASIC}/{@code COST_DETAIL}，
 *           <b>{@code COSTING} 这个值本身没了</b> —— 本方法发 {@code {"dialect":"COSTING"}} 并断言 200，
 *           在 v9 下必然 400</td>
 *       <td><b>{@code AC-107}</b>（不含 {@code system_type}/{@code customer_no}）·
 *           <b>{@code AC-108}</b>（三方言轴收窄）· <b>{@code AC-109}</b>（{@code v_<主表>_all} +
 *           {@code :versionFilter(...::text...)} + {@code view_version}）·
 *           <b>{@code AC-110}</b>（列别名按方言分两种形态）—— 四条把①②③逐项接了过去</td></tr>
 * </table>
 *
 * <h3>✅ 保留的 1 项：原 AC-37④（{@code D-55}）</h3>
 * 「<b>字段绑定键跟 {@code field_type} 走，不跟侧走</b>」：{@code INPUT_*} 写 {@code default_source.path}、
 * {@code BASIC_DATA} 写 {@code basic_data_path}。这条与数据集、与方言都无关，v9 上依然成立，
 * 且 <b>{@code AC-101~AC-126} 里没有任何一条覆盖它</b> ⇒ 换 v9 对象后单独保留为
 * {@link #ac37d_bindingKeyFollowsFieldTypeNotDialect()}。
 * <p>⚠️ 它同时是 {@code AP-44}「字段类型联动协议」在配置器侧的落脚点 —— 删掉它，
 * 「加一个新 {@code field_type} 时绑定键写错」这类静默失败就没有任何机械信号了。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec36DialectAndCiAssertionTest — 🪦 AC-35/36/37①②③ 已作废（2026-09-05 D-123），仅保留 AC-37④ 绑定键契约")
class Sec36DialectAndCiAssertionTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    // ===================================================================
    // 🪦 作废前提守卫（会真的执行）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 作废前提守卫: source_handler 全空（AC-36）+ 方言 distinct 值不含 COSTING（AC-37①②③）")
    void tombstone_ac36Ac37PremiseStillHolds() {
        long activeNodes = scalar("SELECT count(*) FROM semantic_node WHERE status='ACTIVE'");
        long withHandler = scalar("SELECT count(*) FROM semantic_node WHERE source_handler IS NOT NULL");
        List<?> dialects = em.createNativeQuery(
                "SELECT DISTINCT dialect FROM semantic_node WHERE status='ACTIVE' ORDER BY 1").getResultList();
        System.out.println("[🪦 Sec36 留碑] ACTIVE 节点=" + activeNodes + " · source_handler 非空=" + withHandler
                + " · 方言 distinct=" + dialects);

        assertTrue(activeNodes > 0, "🚨 语义图为空 —— 这不是「作废前提成立」，是种子没就位。"
                + "本条判定为【未验证】，🚫 不许当成通过。");

        assertEquals(0L, withHandler,
                "🚦 AC-36（HANDLER_RECONCILE 分项存在且不告警）的作废前提被推翻：有 " + withHandler
                        + " 个节点重新登记了 source_handler。\n"
                        + "  作废理由（D-121）是「对账的另一侧整个换人了，登记侧恒为 0，判据没有对象」。\n"
                        + "  对象回来了 ⇒ 见 SemanticHandlerReconcileTest 的碑文重新评估是否把对账接回来。");

        assertFalse(dialects.contains("COSTING"),
                "🚦 AC-37①②③（QUOTE vs COSTING 两形态对照）的作废前提被推翻：方言里又出现了 COSTING。\n"
                        + "  作废理由是「D-77 把 dialect 扩成三值 QUOTE/COST_BASIC/COST_DETAIL，COSTING 这个值没了」。\n"
                        + "  它回来了 ⇒ 必须搞清楚是回滚了 D-77，还是有人新写了一份不该存在的声明。\n"
                        + "  实际 distinct=" + dialects);
        assertTrue(dialects.contains("QUOTE"), "语义图里连 QUOTE 方言都没有 —— 环境前置未就绪，本条判定为【未验证】。"
                + "实际 distinct=" + dialects);
    }

    // ===================================================================
    // ✅ 保留（换 v9 对象）：原 AC-37④ —— 绑定键跟 field_type 走，不跟侧走（D-55）
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-37④(v9): 同一节点同一次保存里，INPUT_NUMBER 写 default_source、BASIC_DATA 写 basic_data_path")
    void ac37d_bindingKeyFollowsFieldTypeNotDialect() {
        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.id FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        String anchorKey = String.valueOf(anchor[0]);
        String partNoColumn = String.valueOf(em.createNativeQuery(
                        "SELECT c.db_column FROM semantic_node_column c "
                                + "WHERE c.node_id=:nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                .setParameter("nid", anchor[1]).getSingleResult());
        System.out.println("[AC-37④(v9)] 锚点=" + anchorKey + " 料号列=" + partNoColumn);

        UUID componentId = UUID.fromString(RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "bindkey-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components").jsonPath().getString("data.id"));

        // 一次保存里同时放两种 field_type —— 唯一变量就是 fieldType 本身，方言/节点/表都相同。
        String config = "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"columns\":["
                + "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"" + partNoColumn
                + "\",\"fieldName\":\"料件号\",\"isRowKey\":true,\"isPartNo\":true},"
                + "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"content_pct\","
                + "\"fieldName\":\"组成含量_INPUT\",\"fieldType\":\"INPUT_NUMBER\"},"
                + "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"loss_rate\","
                + "\"fieldName\":\"损耗率_BASIC\",\"fieldType\":\"BASIC_DATA\"}]}";

        Response save = RestAssured.given().contentType(ContentType.JSON)
                .body(config).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, save.statusCode(), "保存应成功: " + save.getBody().asString());

        // 用 GET 组件详情核对 fields（黑盒契约，不拼裸 SQL 读 jsonb）
        Response detail = RestAssured.given().get("/api/cpq/components/" + componentId);
        assertEquals(200, detail.statusCode(), detail.getBody().asString());
        List<Map<String, Object>> fields = detail.jsonPath().getList("data.fields");
        assertNotNull(fields, "fields 不应为空，实际=" + detail.getBody().asString());
        assertFalse(fields.isEmpty(), "fields 不应为空列表");
        System.out.println("[AC-37④(v9)] fields=" + fields);

        Map<String, Object> inputField = fields.stream()
                .filter(f -> "组成含量_INPUT".equals(f.get("name"))).findFirst().orElse(null);
        Map<String, Object> basicField = fields.stream()
                .filter(f -> "损耗率_BASIC".equals(f.get("name"))).findFirst().orElse(null);
        assertNotNull(inputField, "应能找到 组成含量_INPUT 字段，实际=" + fields);
        assertNotNull(basicField, "应能找到 损耗率_BASIC 字段，实际=" + fields);

        assertTrue(inputField.containsKey("default_source") && inputField.get("default_source") != null,
                "INPUT_NUMBER 应写 default_source（绑定键跟 field_type 走），实际=" + inputField);
        assertTrue(basicField.containsKey("basic_data_path") && basicField.get("basic_data_path") != null,
                "BASIC_DATA 应写 basic_data_path（绑定键跟 field_type 走，不跟侧走），实际=" + basicField);
    }
}
