package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🪦 <b>本类原覆盖 需求文档.md §3.3 字段面板与行粒度（v8 期 AC-14 ~ AC-19）—— 其中 5 条已于 2026-09-05 作废留碑</b>，
 * AC-14 换 v9 对象后保留。
 *
 * <h3>裁决来源</h3>
 * 用户 2026-09-05 裁决（{@code 需求文档.md} 的 {@code D-123}）；技术前提是 {@code D-77}/{@code D-78} +
 * {@code V413}（V6 的 23 节点 / 25 边 / 7 页签视图 / 145 列声明已从 {@code semantic_*} 删光）。
 *
 * <h3>📌 被作废的 5 个方法（保留历史价值说明，勿删）</h3>
 * <p>🔑 <b>五条的共因是同一个</b>：它们全都要求「一个页签视图上同时存在<b>粒度不同的两个 Sheet</b>」——
 * 主档（每个成品 1 行）+ 组装加工费（成品 × 工序号）+ 成品其他费用（成品 × 要素）。
 * v9 的页签视图<b>每个只挂 1 个 SHEET 节点</b>（2026-09-05 实测：{@code semantic_tab_view_node} 里
 * {@code role='AUX'} 且节点为 SHEET 的行 = 0），行粒度恒等于该锚点自己的 {@code grain_columns}
 * ⇒ <b>「粒度随所选字段动态变化」「粒度冲突」这两类场景在 v9 根本构造不出来</b>，判据没有对象。
 *
 * <table>
 *   <tr><th>方法</th><th>它验的是什么</th><th>接替者</th></tr>
 *   <tr><td>{@code ac15_grainDynamicallyDerivedAsColumnsSelected}</td>
 *       <td>序列：只拖主档 → 预览 1 行、粒度 1 维；加拖组装加工费 → 2 行、粒度「成品+工序号」；
 *           改拖成品其他费用 → 4 行、粒度「成品+要素」。数据由用例自建（{@code material_master} /
 *           {@code material_customer_map} / {@code material_bom_item} / {@code capacity} / {@code unit_price} 五表链）</td>
 *       <td>「预览行数 = 紧邻取的真实基准」这一半由 <b>{@code AC-117}</b>（COST_BASIC）/
 *           <b>{@code AC-119}</b>（COST_DETAIL）/ <b>{@code AC-120}</b>（QUOTE）承担；
 *           「粒度随字段动态推导」这一半<b>在 v9 无对应场景</b></td></tr>
 *   <tr><td>{@code ac16_backendMustExposeConflictMarkersForFrontendGreying}</td>
 *       <td>后端契约：已选「组装加工费」时，{@code field-tree} 要对「成品其他费用」分组返回
 *           {@code conflict} 标记，供前端拖拽期置灰</td>
 *       <td>无（v9 的 {@code field-tree} 只会返回锚点自己那一个 MAIN 组 + 可能的价格策略组，
 *           不存在「另一个粒度冲突的组」可被标记）</td></tr>
 *   <tr><td>{@code ac17_saveTimeGrainConflictFallbackStillBlocks}</td>
 *       <td>边界：人为构造跨维度混拖的冲突列集合 → 体检 ERR「粒度冲突（兜底拦截）」+ 保存 400</td>
 *       <td>无同类判据。⚠️ <b>{@code COMPILE_GRAIN_CONFLICT} 这道保存期兜底本身仍在代码里</b>，
 *           只是 v9 图上喂不出触发它的输入 —— 一旦将来页签视图重新多源，本防线必须重新接上
 *           （见 {@link #tombstone_grainPremiseStillHolds()}）</td></tr>
 *   <tr><td>{@code ac18_coarseGrainColumnCheckedSubtotalBlocks}</td>
 *       <td>产品级列（单重）在更细粒度（成品+工序号）下勾小计 → ERR 阻断（累加即重复计算）</td>
 *       <td>无（v9 单锚点 ⇒ 所有列同粒度，不存在「粗粒度列」）</td></tr>
 *   <tr><td>{@code ac19_auxSourceColumnCheckedSubtotalBlocks}</td>
 *       <td>附属源列勾小计 → ERR「按主源粒度重复」</td>
 *       <td>无。<b>2026-09-05 已在 v9 唯一的 AUX 对象上实测过</b>：QUOTE/材质元素 的
 *           {@code FUNC_ELEMENT_PRICE.unit_price} 勾 {@code inSubtotal} → {@code /inspect} 返回
 *           {@code blocked=false, items=[]}。这<b>不是缺陷</b> —— 价格策略函数是按
 *           (料号, 元素) 取价、与锚点同粒度，累加不构成重复计算；判据的前提（附属源比主源粗）不成立。
 *           <b>🚫 因此不能把它「换个对象保留」——那会得到一条恒绿的空断言（四类假绿之一）。</b></td></tr>
 * </table>
 *
 * <h3>✅ 换 v9 对象后保留的 1 个方法</h3>
 * {@link #ac14_fieldPanelEqualsSheetRealImportColumns()} —— 「字段面板 = 该 Sheet 的真实登记列、
 * 没有『全部列』折叠、中文名与登记逐字一致」是与数据集无关的通用契约，v9 上依然成立，
 * 且 {@code AC-116} 只验<b>数据集隔离</b>（核价侧不出现对方套的表），<b>不验字段清单本身的完备性</b>。
 * <br>🔄 判据升级：原用例抽查四个写死的中文名（{@code 计价单位/比例/基准值/毛用量}）——那是 V6 导入模板的表头。
 * 本轮改为<b>与 {@code semantic_node_column} 登记逐字比对（双向无差集）</b>，不再写死任何列名，
 * 顺带把「抽查」升级成「全量」。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec33FieldPanelGrainTest — 🪦 v8 期 AC-15/16/17/18/19 已作废（2026-09-05 D-123），AC-14 换 v9 对象保留")
class Sec33FieldPanelGrainTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    // ===================================================================
    // 🪦 作废前提守卫（会真的执行；前提被推翻就变红）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 作废前提守卫: 每个页签视图只挂 1 个 SHEET 节点（多粒度场景构造不出）")
    void tombstone_grainPremiseStillHolds() {
        long views = scalar("SELECT count(*) FROM semantic_tab_view WHERE status='ACTIVE'");
        long multiSheetViews = scalar(
                "SELECT count(*) FROM (SELECT tvn.view_id FROM semantic_tab_view_node tvn "
                        + "JOIN semantic_node n ON n.id = tvn.node_id "
                        + "WHERE n.node_kind='SHEET' AND tvn.status='ACTIVE' "
                        + "GROUP BY tvn.view_id HAVING count(*) > 1) t");
        long auxSheetNodes = scalar("SELECT count(*) FROM semantic_tab_view_node tvn "
                + "JOIN semantic_node n ON n.id = tvn.node_id "
                + "WHERE tvn.role='AUX' AND n.node_kind='SHEET' AND tvn.status='ACTIVE'");
        long addDims = scalar("SELECT count(*) FROM semantic_tab_view_node "
                + "WHERE status='ACTIVE' AND array_length(add_dims, 1) IS NOT NULL");

        System.out.println("[🪦 Sec33 留碑] ACTIVE 页签视图=" + views + " · 多 SHEET 视图=" + multiSheetViews
                + " · AUX 挂 SHEET=" + auxSheetNodes + " · 带 add_dims 的挂载=" + addDims);

        assertTrue(views > 0, "🚨 语义图里一个 ACTIVE 页签视图都没有 —— 这不是「作废前提成立」，是种子没就位。"
                + "本条判定为【未验证】，🚫 不许当成通过。");

        assertEquals(0L, multiSheetViews,
                "🚦 AC-15/16/17/18 的作废前提被推翻：有 " + multiSheetViews + " 个页签视图挂了 2 个及以上 SHEET 节点。\n"
                        + "  2026-09-05 作废这四条的唯一理由是「v9 单锚点 ⇒ 行粒度恒等于锚点自己的 grain_columns，"
                        + "『粒度随所选字段动态变化』和『粒度冲突』都构造不出来」。\n"
                        + "  现在多源了 ⇒ 粒度冲突、粗粒度列勾小计、field-tree 冲突标记这三道防线必须重新接上"
                        + "（历史实现见本类 git 历史）。\n"
                        + "  📌 特别提醒：保存期兜底 COMPILE_GRAIN_CONFLICT 的实现<b>一直都在</b>，"
                        + "作废的只是「喂得出触发输入的夹具」。");

        assertEquals(0L, auxSheetNodes,
                "🚦 AC-19（附属源列勾小计阻断）的作废前提被推翻：出现了 " + auxSheetNodes
                        + " 个以 AUX 角色挂载的 SHEET 节点。\n"
                        + "  作废理由是「v9 唯一的 AUX 是 FUNCTION 价格策略、与锚点同粒度，勾小计不构成重复计算"
                        + "（2026-09-05 实测 /inspect 返回 blocked=false, items=[]）」。\n"
                        + "  真正的『比主源粗的附属 Sheet』回来了 ⇒ 必须把该断言重新接上。");

        assertEquals(0L, addDims,
                "🚦 粒度族用例的作废前提被推翻：有 " + addDims + " 条 semantic_tab_view_node 带上了 add_dims"
                        + "（= 该挂载会给行粒度追加维度）。\n"
                        + "  这正是 AC-15「粒度随所选字段动态推导」要验的机制。它回来了 ⇒ 必须重新评估。");
    }

    // ===================================================================
    // AC-14（单点）字段清单 = 该 Sheet 的真实登记列
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-14(v9): 字段面板逐 Sheet 分组，字段中文名与 semantic_node_column 登记双向无差集，无『全部列』折叠")
    void ac14_fieldPanelEqualsSheetRealImportColumns() {
        // 逐方言 × 逐页签类型全量核对（🚫 不抽查、🚫 不写死列名）——对象与期望值都从库里现取。
        @SuppressWarnings("unchecked")
        List<Object[]> tabViews = em.createNativeQuery(
                        "SELECT v.dialect, v.tab_type, v.variant_key, n.node_key, n.short_name, n.id "
                                + "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.status='ACTIVE' ORDER BY v.dialect, v.tab_type, v.variant_key")
                .getResultList();
        assertFalse(tabViews.isEmpty(), "环境前置未就绪：一个 ACTIVE 页签视图都查不到，本条判定为【未验证】");
        System.out.println("[AC-14(v9)] 待核对的页签视图共 " + tabViews.size() + " 个");

        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Object[] tv : tabViews) {
            String dialect = String.valueOf(tv[0]);
            String tabType = String.valueOf(tv[1]);
            String variantKey = tv[2] == null ? "" : String.valueOf(tv[2]);
            String anchorKey = String.valueOf(tv[3]);

            var req = RestAssured.given().queryParam("tabType", tabType).queryParam("dialect", dialect);
            if (!variantKey.isBlank()) {
                req = req.queryParam("variantKey", variantKey);
            }
            Response resp = req.get("/api/cpq/config/semantic-graph/field-tree");
            if (resp.statusCode() != 200) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " field-tree HTTP="
                        + resp.statusCode() + " body=" + resp.getBody().asString());
                continue;
            }

            List<String> groupNames = resp.jsonPath().getList("groups.groupName");
            assertNotNull(groupNames, dialect + "/" + tabType + " 应返回 groups");
            if (groupNames.isEmpty()) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " groups 为空（假绿陷阱：空结果比不出字段数）");
                continue;
            }
            // ② 不存在「全部列」二级折叠
            if (groupNames.stream().anyMatch(g -> g != null && g.contains("全部列"))) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " 出现了『全部列』分组: " + groupNames);
            }

            // ①③ 该锚点分组的字段中文名，与 semantic_node_column 的登记显示名双向无差集
            @SuppressWarnings("unchecked")
            List<String> declared = em.createNativeQuery(
                            "SELECT c.display_name FROM semantic_node_column c "
                                    + "WHERE c.node_id = :nid AND c.status='ACTIVE'")
                    .setParameter("nid", tv[5]).getResultList();
            List<String> panel = resp.jsonPath().getList(
                    "groups.find { it.groupKey == '" + anchorKey + "' }.fields.displayName");
            if (panel == null) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " field-tree 里找不到锚点分组 " + anchorKey);
                continue;
            }
            List<String> missingInPanel = new ArrayList<>(declared);
            missingInPanel.removeAll(panel);
            List<String> extraInPanel = new ArrayList<>(panel);
            extraInPanel.removeAll(declared);
            if (!missingInPanel.isEmpty() || !extraInPanel.isEmpty()) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " 锚点 " + anchorKey
                        + " 字段面板与登记有差集：面板缺=" + missingInPanel + " 面板多=" + extraInPanel);
            }
            if (declared.isEmpty()) {
                violations.add(dialect + "/" + tabType + "/" + variantKey + " 锚点 " + anchorKey
                        + " 一列都没登记 —— 空集合比对恒真，属假绿，判为违规");
            }
            checked++;
        }

        System.out.println("[AC-14(v9)] 实际核对页签视图 " + checked + " 个，违规 " + violations.size() + " 条");
        assertTrue(checked > 0, "🚨 一个页签视图都没真正核对到 —— 断言从未执行 = 假绿，本条判定为【未验证】");
        assertEquals(List.of(), violations, "AC-14 逐页签核对发现差异（共 " + violations.size() + " 条）: " + violations);
    }
}
