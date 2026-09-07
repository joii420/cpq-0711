package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二批 · <b>AC-30（QUOTE 材质元素的价格策略组不得重复）</b>。
 *
 * <h3>AC 原文（需求文档.md §3.3，2026-09-06 由 task-260819 B-24/D-65 转入本期，对应 B-23）</h3>
 * 操作：{@code GET /config/semantic-graph/field-tree?tabType=材质元素&dialect=<三方言各一次>}，
 * 清点 {@code groupKind='PRICE'} 的分组数。断言：
 * <ol>
 *   <li><b>QUOTE 恰好返回 2 组</b> {@code [ELEMENT_BOM/MAIN, FUNC_ELEMENT_PRICE/PRICE]}
 *       —— 改动前为 3 组（{@code FUNC_ELEMENT_PRICE} 出现两次）；</li>
 *   <li>保留下来的那块必须是 <b>{@code isCore=true} / {@code viewColumn='元素单价'}</b> 的那个；</li>
 *   <li>阴性对照：{@code COST_BASIC} / {@code COST_DETAIL} 仍各 <b>1 组</b>（本就不复现，不得被顺带改坏）；</li>
 *   <li>阴性对照：{@code Sec34PriceStrategyTest} 的 5 条用例仍绿（🚫 不在本类跑，由执行命令带上）。</li>
 * </ol>
 *
 * <h3>🚨 三条必须写清的事实</h3>
 * <ol>
 *   <li><b>这个缺陷不是 task-260904 引入的。</b> 主线 2026-09-06 实测 master(8081) 与本任务
 *       worktree(8089) 结果<b>逐字相同</b>，原属 {@code task-260819} 的 B-24/D-65，用户裁决转入本期。
 *       ⇒ 🚫 <b>本用例变红时不得按「本次改动引入的回归」归因</b>。</li>
 *   <li><b>只有 QUOTE 复现。</b> 根因：{@code FUNC_ELEMENT_PRICE} 既以 {@code AUX} 挂在
 *       QUOTE/材质元素的 {@code semantic_tab_view_node} 上（通用 {@code tvns} 循环加一次），
 *       <b>又</b>是锚点 {@code PRICE} 边的目标（末尾专用 {@code priceEdge} 块再加一次）。
 *       只有 QUOTE 挂了那行 {@code AUX} ⇒ ③ 那条阴性对照<b>是有意义的判据，不是凑数</b>：
 *       它锁住「修 QUOTE 时不许把另两套本来就对的形态改坏」。</li>
 *   <li><b>「2」这个数字的来源是文档不是魔数。</b> {@code api.md §1.3} 原写
 *       「QUOTE 材质元素挂 2 组（{@code ELEMENT_BOM(MAIN)} + {@code FUNC_ELEMENT_PRICE(AUX)}）」
 *       —— 那是<b>期望形态</b>；现网 3 组是偏离，修好即回归期望。
 *       ⇒ 本类是全套用例里<b>唯一</b>写死组数的地方，且写死的是<b>契约值</b>不是数据快照。</li>
 * </ol>
 *
 * <h3>⚠️ 一条不许写进断言的错误推断（主线已证伪作废，留档防重蹈）</h3>
 * 初判「用户从错的那块拖列 → {@code basic_data_path} 指向视图未声明的列 → 报价单静默空白（数据损坏）」。
 * <b>已证伪</b>：前端 {@code configPayloadFor} 只发 {@code sourceNodeKey / sourceColumn / fieldName /
 * 角色布尔位}，<b>不发 {@code viewColumn}</b>，两块的坐标逐字相同 ⇒ 保存请求相同、编译器按坐标重算别名、
 * 产出 SQL 都对。<br>
 * ✅ 真实危害是<b>原子组语义失效</b>：两块 {@code isCore} 不同（#1 {@code false} / #2 {@code true}），
 * 而 {@code isCore} 驱动前端 {@code killsGroup} / {@code priceCol} 判定 ⇒ 从 #1 拖出的单价列
 * 删它不整组删除、原子组认不到它。
 * ⇒ 🚫 本类<b>不写</b>「拖 #1 会导致报价单取不到值」这类断言（那是错的，且会得到一个恒绿的假用例）；
 * ② 那条（{@code isCore=true}）才是有效判据。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-30 —— QUOTE 材质元素的价格策略组不得重复（保留 isCore=true 的那块）")
class PriceGroupDuplicationAcTest extends Batch2Base {

    /** 判据线来自 api.md §1.3「QUOTE 材质元素挂 2 组」——期望形态，非现网快照。 */
    private static final int QUOTE_EXPECTED_GROUPS = 2;
    /** 阴性对照：核价两套本就各 1 组（task-260819 B-50 按 NARROW 边剔除桥之后）。 */
    private static final int COSTING_EXPECTED_GROUPS = 1;

    private static final String PRICE_NODE = "FUNC_ELEMENT_PRICE";
    private static final String EXPECTED_CORE_VIEW_COLUMN = "元素单价";

    @Test
    @DisplayName("AC-30①②③：QUOTE 恰好 2 组且 PRICE 块是 isCore=true/viewColumn='元素单价' 的那个；"
            + "核价两套仍各 1 组")
    void ac30_priceGroupNotDuplicatedOnQuote() {
        Map<String, List<Group>> byDialect = new LinkedHashMap<>();
        for (String dialect : DIALECTS) {
            byDialect.put(dialect, groupsOf("材质元素", "", dialect, "AC-30(" + dialect + ")"));
        }
        // 先把三方言的实际形态打出来存档 —— 红的时候这几行就是归因材料。
        for (Map.Entry<String, List<Group>> e : byDialect.entrySet()) {
            System.out.println("[AC-30] " + e.getKey() + " 材质元素 组数=" + e.getValue().size() + " "
                    + e.getValue().stream().map(g -> g.groupKey() + "[" + g.groupKind() + "]×"
                            + g.sourceColumns().size()).toList());
        }

        // ── ① QUOTE 恰好 2 组，且组构成正确 ──
        List<Group> quote = byDialect.get("QUOTE");
        List<Group> quotePrice = priceGroups(quote);
        List<String> dupPriceKeys = new ArrayList<>();
        for (Group g : quotePrice) {
            if (quotePrice.stream().filter(x -> x.groupKey().equals(g.groupKey())).count() > 1
                    && !dupPriceKeys.contains(g.groupKey())) {
                dupPriceKeys.add(g.groupKey());
            }
        }
        assertTrue(dupPriceKeys.isEmpty(), "AC-30①：QUOTE/材质元素 的 PRICE 组里出现了重复 groupKey="
                + dupPriceKeys + " —— " + PRICE_NODE + " 被加了两次（一次来自 semantic_tab_view_node 的 AUX 挂载、"
                + "一次来自锚点 PRICE 边）。🚨 该缺陷不是 task-260904 引入的（master 与本 worktree 逐字相同），"
                + "由 B-23 在本期修复。实际组=" + quote.stream()
                        .map(g -> g.groupKey() + "[" + g.groupKind() + "]").toList());
        assertEquals(QUOTE_EXPECTED_GROUPS, quote.size(),
                "AC-30①：QUOTE/材质元素 应恰好 " + QUOTE_EXPECTED_GROUPS + " 组 [ELEMENT_BOM/MAIN, "
                        + PRICE_NODE + "/PRICE]（判据线来自 api.md §1.3 的期望形态，非现网快照），实际="
                        + quote.stream().map(g -> g.groupKey() + "[" + g.groupKind() + "]").toList());
        assertEquals(1, quotePrice.size(), "AC-30①：QUOTE/材质元素 的 PRICE 组应恰好 1 个，实际="
                + quotePrice.stream().map(Group::groupKey).toList());
        assertEquals(1, mainGroups(quote).size(), "AC-30①：QUOTE/材质元素 的 MAIN 组应恰好 1 个，实际="
                + mainGroups(quote).stream().map(Group::groupKey).toList());

        // ── ② 保留的那块必须是 isCore=true / viewColumn='元素单价' ──
        List<Map<String, Object>> priceFields = priceGroupFields("QUOTE");
        assertFalse(priceFields.isEmpty(), "AC-30②：QUOTE/材质元素 的 PRICE 组一个字段都没有 ⇒ 断言会空跑。");

        Map<String, Object> unitPrice = priceFields.stream()
                .filter(f -> Boolean.TRUE.equals(f.get("isCore")))
                .findFirst().orElse(null);
        assertNotNull(unitPrice, "AC-30②：PRICE 组里没有任何 isCore=true 的字段 —— "
                + "被保留下来的是**错的那块**（#1 的 isCore 全为 false，它驱动不了前端价格策略原子组的 "
                + "killsGroup / priceCol 判定 ⇒ 单价列删它不整组删除、原子组认不到它）。实际字段="
                + priceFields.stream().map(f -> f.get("sourceColumn") + "(isCore=" + f.get("isCore")
                        + ",viewColumn=" + f.get("viewColumn") + ")").toList());
        assertEquals(EXPECTED_CORE_VIEW_COLUMN, String.valueOf(unitPrice.get("viewColumn")),
                "AC-30②：isCore=true 的那块其 viewColumn 应为「" + EXPECTED_CORE_VIEW_COLUMN
                        + "」（与 SemanticCompiler 的 AliasGenerator.bareColumn 同源），实际="
                        + unitPrice.get("viewColumn"));
        assertEquals(PRICE_NODE, String.valueOf(unitPrice.get("sourceNodeKey")),
                "AC-30②：该字段应来自 " + PRICE_NODE + "，实际=" + unitPrice.get("sourceNodeKey"));
        System.out.println("[AC-30②] 保留下来的 PRICE 块字段=" + priceFields.stream()
                .map(f -> f.get("sourceColumn") + "(isCore=" + f.get("isCore")
                        + ", viewColumn=" + f.get("viewColumn") + ", lookupLib=" + f.get("lookupLib") + ")").toList());

        // ── ③ 阴性对照：核价两套仍各 1 组，且都不含 PRICE 组 ──
        //    只有 QUOTE 在 semantic_tab_view_node 里挂了 FUNC_ELEMENT_PRICE/AUX，故只有 QUOTE 复现。
        //    这条锁住「修 QUOTE 时不许把另两套本来就对的形态顺带改坏」。
        for (String costing : List.of("COST_BASIC", "COST_DETAIL")) {
            List<Group> gs = byDialect.get(costing);
            assertEquals(COSTING_EXPECTED_GROUPS, gs.size(), "AC-30③ 阴性对照：" + costing
                    + "/材质元素 应仍为 " + COSTING_EXPECTED_GROUPS + " 组（本就不复现该缺陷），实际="
                    + gs.stream().map(g -> g.groupKey() + "[" + g.groupKind() + "]").toList()
                    + " ⇒ 若变了，说明 B-23 的修法误伤了另两套方言。");
            assertTrue(priceGroups(gs).isEmpty(), "AC-30③ 阴性对照：" + costing
                    + "/材质元素 本不应有 PRICE 组，实际=" + priceGroups(gs).stream().map(Group::groupKey).toList());
        }

        // ── 前置事实取证：QUOTE 确实挂了那行 AUX，且种子一行未动（🚫 修法禁区②：不改 V413 种子）──
        long quoteAuxMount = count("SELECT count(*) FROM semantic_tab_view v "
                + "JOIN semantic_tab_view_node tvn ON tvn.view_id = v.id "
                + "JOIN semantic_node n ON n.id = tvn.node_id "
                + "WHERE v.dialect = 'QUOTE' AND v.tab_type = '材质元素' AND n.node_key = '" + PRICE_NODE + "'");
        assertEquals(1L, quoteAuxMount, "AC-30 前置：QUOTE/材质元素 上 " + PRICE_NODE
                + " 的挂载行应仍为 1 行 —— 🚫 修法禁区②明写「不改 V413 种子去掉那行 AUX」"
                + "（挂载是有意的、Sec34PriceStrategyTest 靠它存在、且已应用到共享库，动它撞 §3.2 契约销毁红线）。"
                + "实际=" + quoteAuxMount + " ⇒ 为 0 说明修法走的是删种子这条禁区路径。");
        long costingAuxMount = count("SELECT count(*) FROM semantic_tab_view v "
                + "JOIN semantic_tab_view_node tvn ON tvn.view_id = v.id "
                + "JOIN semantic_node n ON n.id = tvn.node_id "
                + "WHERE v.dialect <> 'QUOTE' AND v.tab_type = '材质元素' AND n.node_key = '" + PRICE_NODE + "'");
        System.out.println("[AC-30 前置] " + PRICE_NODE + " 的 AUX 挂载：QUOTE=" + quoteAuxMount
                + " 行、核价两套合计=" + costingAuxMount + " 行（只有 QUOTE 挂 ⇒ 只有 QUOTE 复现）");
    }

    /** QUOTE 的 PRICE 组里的字段原始 map（要读 isCore / viewColumn / lookupLib，Group 记录里没带）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> priceGroupFields(String dialect) {
        Response r = fieldTreeOk("材质元素", "", dialect, "AC-30②");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) r.jsonPath().get("groups");
        assertNotNull(groups, "AC-30②：响应无 groups。body=" + r.asString());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (!"PRICE".equals(String.valueOf(g.get("groupKind")))) continue;
            List<Map<String, Object>> fs = (List<Map<String, Object>>) g.get("fields");
            if (fs != null) out.addAll(fs);
        }
        return out;
    }
}
