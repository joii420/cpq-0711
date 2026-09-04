package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求文档.md §9.4 <b>A 组 · 语义图重建</b> —— AC-101 / AC-102 / AC-104 / AC-105 / AC-106。
 * （AC-103「种子可重放」在 {@link V9SeedReplayTest}，因为它跑的是仓库脚本不是库。）
 *
 * <p>层级 = 数据层（直查库）。这一组全是<b>纯 SQL 可观测断言</b>，AC 原文本身就是 SQL 形态，
 * 因此断言与 AC 原文一一对应，没有解释空间。
 *
 * <h3>本类不改任何全局状态</h3>
 * 全部只读。无 {@code INSERT}/{@code UPDATE}/{@code DELETE}。
 *
 * <h3>🔴 一处 AC 内部矛盾，已报主线待裁决（见 {@link #ac104_nodeColumnsMatchInformationSchema}）</h3>
 * AC-104 写「<b>45 张</b>主表每张在 {@code semantic_node} 有且仅有 1 行」，
 * 而 §9.2 的「不进图」行明确排除了年降 3 张（N-18）+ {@code ds_quote_customer_part}（N-19）⇒ 应为 <b>41</b>。
 * 两者不能同时成立。本用例按<b>可调和读法</b>断言（41 进图 + 4 显式不进图），
 * 失败信息里把两种读法都打出来，由主线裁决改 AC 还是改种子。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9SemanticGraphSeedTest extends V9TestBase {

    // ═══════════════════════════════════════════════════════════════
    // AC-101（单点）三方言存在
    // AC 原文：dialect 的 distinct 值恰为 {QUOTE, COST_BASIC, COST_DETAIL}，
    //          且每个值下 node_kind='SHEET' 的行数 > 0
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(101)
    @DisplayName("AC-101: semantic_node.dialect distinct 恰为三方言，且每方言 SHEET 节点 > 0")
    void ac101_threeDialectsExist() {
        List<String> dialects = strList(
                "SELECT DISTINCT dialect FROM semantic_node WHERE status='ACTIVE' ORDER BY 1");
        System.out.println("[AC-101] 实际 dialect distinct = " + dialects);

        assertFalse(dialects.isEmpty(),
                notReady("AC-101", "semantic_node 一行 ACTIVE 都没有（种子未落地）", "cpq-backend #2 / B-42"));

        assertEquals(new TreeSet<>(List.of(QUOTE, COST_BASIC, COST_DETAIL)), new TreeSet<>(dialects),
                "AC-101: dialect 的 distinct 值应『恰为』三方言（多一个少一个都不算通过）。实际=" + dialects
                        + "\n  ⚠️ 若这里还只有 QUOTE，说明 B-42 种子重建未执行 —— 本条 AC 未验证。");

        for (String d : List.of(QUOTE, COST_BASIC, COST_DETAIL)) {
            long sheets = scalarLong(
                    "SELECT count(*) FROM semantic_node WHERE dialect=?1 AND node_kind='SHEET' AND status='ACTIVE'", d);
            System.out.println("[AC-101] " + d + " SHEET 节点数 = " + sheets);
            assertTrue(sheets > 0, "AC-101: 方言 " + d + " 下 node_kind='SHEET' 的行数必须 > 0，实际=" + sheets);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-102（单点 + 边界）V6 节点清零 + 悬挂边为零
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(102)
    @DisplayName("AC-102: 8 张 V6 表在 semantic_node 命中 0；semantic_edge 悬挂边 0")
    void ac102_v6NodesGoneAndNoDanglingEdges() {
        // ① 阳性对照：先证明这条查询「抓得到东西」—— 否则「命中 0」可能只是查询写错了（testing.md §4.4）
        long anyNode = scalarLong("SELECT count(*) FROM semantic_node");
        assertTrue(anyNode > 0, notReady("AC-102",
                "semantic_node 整表为空，『V6 命中 0』这个断言等于空跑", "cpq-backend #2 / B-42"));

        List<Object[]> hits = rowList(
                "SELECT physical_table, count(*) FROM semantic_node "
                        + "WHERE physical_table IN ('material_bom_item','element_bom_item','unit_price',"
                        + "'annual_discount','capacity','plating_scheme','material_customer_map','material_master') "
                        + "GROUP BY 1 ORDER BY 1");
        System.out.println("[AC-102] V6 表命中 = " + fmt(hits));
        assertTrue(hits.isEmpty(), "AC-102①: 8 张 V6 表在 semantic_node 必须命中 0，实际命中=" + fmt(hits)
                + "\n  （AC 点名的 8 张：" + V6_TABLES + "）");

        // ② 悬挂边：from/to 指向不存在的节点
        List<Object[]> dangling = rowList(
                "SELECT e.id::text, e.from_node_id::text, e.to_node_id::text FROM semantic_edge e "
                        + "WHERE NOT EXISTS (SELECT 1 FROM semantic_node n WHERE n.id=e.from_node_id) "
                        + "   OR NOT EXISTS (SELECT 1 FROM semantic_node n WHERE n.id=e.to_node_id)");
        System.out.println("[AC-102] 悬挂边 = " + dangling.size());
        assertTrue(dangling.isEmpty(),
                "AC-102②: semantic_edge 悬挂边（指向不存在节点）必须为 0，实际=" + fmt(dangling));

        // ③ 阳性对照：证明②那条查询确实在扫真实的边，而不是扫了个空表
        long anyEdge = scalarLong("SELECT count(*) FROM semantic_edge");
        assertTrue(anyEdge > 0, notReady("AC-102",
                "semantic_edge 整表为空，『悬挂边 0』等于空跑", "cpq-backend #2 / B-42"));
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-104（单点 + 边界）45 表各 1 节点；列集合与 information_schema 双向无差集
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(104)
    @DisplayName("AC-104: 每张进图主表恰 1 个节点；节点列集合与 information_schema 双向无差集")
    void ac104_nodeColumnsMatchInformationSchema() {
        List<String> all45 = allDsMainTables();
        System.out.println("[AC-104] 库里 ds_* 主表（非 _history）实测 = " + all45.size() + " 张");
        assertEquals(45, all45.size(),
                "AC-104 前置：§9.1.1 说 45 张主表（16+10+19），实测=" + all45.size()
                        + "。若这里对不上，说明 task-260902 的表结构变了，先停下来核对，不要继续往下断言。");

        // 期望进图 = 45 - §9.2「不进图」的 4 张
        Set<String> expectedInGraph = new LinkedHashSet<>(all45);
        expectedInGraph.removeAll(NOT_IN_GRAPH);

        // 实际进图（physical_table 可能指向 v_<主表>_all 视图，S-31/D-84 —— 归一化回主表）
        List<String> physical = strList(
                "SELECT physical_table FROM semantic_node "
                        + "WHERE status='ACTIVE' AND physical_table IS NOT NULL AND node_kind='SHEET'");
        assertFalse(physical.isEmpty(), notReady("AC-104",
                "semantic_node 里没有任何带 physical_table 的 SHEET 节点", "cpq-backend #2 / B-42"));

        Map<String, Integer> countByMain = new LinkedHashMap<>();
        for (String p : physical) {
            String main = normalizeToMainTable(p);
            countByMain.merge(main, 1, Integer::sum);
        }
        System.out.println("[AC-104] 实际进图主表数（归一化 v_*_all 后）= " + countByMain.size());

        // ① 每张恰 1 行
        List<String> dup = new ArrayList<>();
        countByMain.forEach((t, c) -> {
            if (c != 1) {
                dup.add(t + "×" + c);
            }
        });
        assertTrue(dup.isEmpty(), "AC-104①: 每张主表在 semantic_node 必须『有且仅有 1 行』，重复的=" + dup);

        // ② 进图集合 == 45 - 不进图 4
        Set<String> missing = new TreeSet<>(expectedInGraph);
        missing.removeAll(countByMain.keySet());
        Set<String> extra = new TreeSet<>(countByMain.keySet());
        extra.removeAll(expectedInGraph);
        assertTrue(missing.isEmpty() && extra.isEmpty(),
                "AC-104②: 进图主表集合与期望不符。\n  缺=" + missing + "\n  多=" + extra
                        + "\n  🔴 提醒主线：AC-104 原文写『45 张主表每张…有且仅有 1 行』，"
                        + "而 §9.2 的『不进图』行排除了年降 3 张（N-18）+ ds_quote_customer_part（N-19）⇒ 应为 41 张。"
                        + "\n     两种读法：[45 全进图] vs [41 进图 + 4 显式不进图]。本用例按后者断言（与 §9.2 + N-18/N-19 一致）。"
                        + "\n     若裁决改为前者，请改 AC 或改 §9.2，两处必须一致。");

        // ③ 显式断言那 4 张确实不进图（不是漏配，是明确排除）
        for (String t : NOT_IN_GRAPH) {
            assertEquals(0L, scalarLong(
                            "SELECT count(*) FROM semantic_node WHERE physical_table=?1 OR physical_table=?2", t, "v_" + t + "_all"),
                    "AC-104③: §9.2 明确『不进图』的 " + t + " 不应出现在 semantic_node（N-18 / N-19）");
        }

        // ④ 逐节点列集合双向无差集
        List<Object[]> nodes = rowList(
                "SELECT n.node_key, n.physical_table FROM semantic_node n "
                        + "WHERE n.status='ACTIVE' AND n.node_kind='SHEET' AND n.physical_table IS NOT NULL ORDER BY 1");
        StringBuilder bad = new StringBuilder();
        int checked = 0;
        for (Object[] n : nodes) {
            String nodeKey = String.valueOf(n[0]);
            String rel = String.valueOf(n[1]);
            Set<String> declared = new TreeSet<>(strList(
                    "SELECT c.db_column FROM semantic_node_column c JOIN semantic_node nn ON nn.id=c.node_id "
                            + "WHERE nn.node_key=?1 AND c.status='ACTIVE'", nodeKey));
            Set<String> actual = new TreeSet<>(columnsOf(rel));
            assertFalse(actual.isEmpty(), "AC-104④: 节点 " + nodeKey + " 的 physical_table=" + rel
                    + " 在 information_schema 里查不到任何列 —— 表/视图不存在，或名字写错");
            assertFalse(declared.isEmpty(), "AC-104④: 节点 " + nodeKey + " 一条列声明都没有 —— "
                    + "『双向无差集』会因为两边都空而假通过，这里先挡掉");
            Set<String> onlyDeclared = new TreeSet<>(declared);
            onlyDeclared.removeAll(actual);
            Set<String> onlyActual = new TreeSet<>(actual);
            onlyActual.removeAll(declared);
            if (!onlyDeclared.isEmpty() || !onlyActual.isEmpty()) {
                bad.append("\n  ").append(nodeKey).append('(').append(rel).append(')')
                        .append(" 声明多出=").append(onlyDeclared).append(" 库里多出=").append(onlyActual);
            }
            checked++;
        }
        System.out.println("[AC-104] 逐节点列比对完成，节点数=" + checked);
        assertTrue(checked > 0, notReady("AC-104", "没有任何 SHEET 节点可比对，④等于空跑", "cpq-backend #2 / B-42"));
        assertEquals("", bad.toString(), "AC-104④: 节点列集合与 information_schema 必须双向无差集，差异=" + bad);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-105（边界）_history 不进图
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(105)
    @DisplayName("AC-105: physical_table LIKE '%_history' 的节点数 = 0")
    void ac105_historyTablesNotInGraph() {
        // 阳性对照：库里确实存在 _history 表，所以「0」是真的没进图，不是根本没这类表
        long historyTables = scalarLong(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                        + "AND table_type='BASE TABLE' AND table_name LIKE 'ds\\_%\\_history'");
        System.out.println("[AC-105] 库里 ds_*_history 表 = " + historyTables + " 张");
        assertTrue(historyTables > 0, notReady("AC-105",
                "库里一张 _history 表都没有，本断言等于空跑", "task-260902 的 V405~V408"));

        long inGraph = scalarLong(
                "SELECT count(*) FROM semantic_node WHERE physical_table LIKE '%\\_history'");
        List<String> which = strList(
                "SELECT node_key || '->' || physical_table FROM semantic_node WHERE physical_table LIKE '%\\_history'");
        assertEquals(0L, inGraph, "AC-105: _history 表不得进语义图（N-17 / D-80），实际命中=" + which);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-106（单点）页签视图三套同构，费用类变体 8 / 7 / 15
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(106)
    @DisplayName("AC-106: 三方言 (tab_type, variant_key) 组合数与 §9.2 逐格相等（费用类 8/7/15）")
    void ac106_tabViewsIsomorphicAcrossDialects() {
        long anyView = scalarLong("SELECT count(*) FROM semantic_tab_view WHERE status='ACTIVE'");
        assertTrue(anyView > 0, notReady("AC-106", "semantic_tab_view 无 ACTIVE 行", "cpq-backend #2 / B-42"));

        // §9.2 映射表：5 个非费用类页签在三套里都有；费用类变体数 = 报价 8 / 基础核价 7 / 明细核价 15
        List<String> nonFeeTabs = List.of("主件", "材质元素", "零件", "外购件", "BOM 树");
        Map<String, Integer> feeVariants = new LinkedHashMap<>();
        feeVariants.put(QUOTE, 8);
        feeVariants.put(COST_BASIC, 7);
        feeVariants.put(COST_DETAIL, 15);

        StringBuilder err = new StringBuilder();
        for (Map.Entry<String, Integer> e : feeVariants.entrySet()) {
            String d = e.getKey();

            List<String> tabTypes = strList(
                    "SELECT DISTINCT tab_type FROM semantic_tab_view WHERE dialect=?1 AND status='ACTIVE' ORDER BY 1", d);
            System.out.println("[AC-106] " + d + " tab_type = " + tabTypes);

            for (String t : nonFeeTabs) {
                long n = scalarLong("SELECT count(*) FROM semantic_tab_view "
                        + "WHERE dialect=?1 AND tab_type=?2 AND status='ACTIVE'", d, t);
                if (n != 1) {
                    err.append("\n  ").append(d).append(" 的『").append(t).append("』页签视图应恰 1 行，实际=").append(n);
                }
            }

            long fee = scalarLong("SELECT count(DISTINCT variant_key) FROM semantic_tab_view "
                    + "WHERE dialect=?1 AND tab_type='费用类' AND status='ACTIVE'", d);
            List<String> feeKeys = strList("SELECT variant_key FROM semantic_tab_view "
                    + "WHERE dialect=?1 AND tab_type='费用类' AND status='ACTIVE' ORDER BY 1", d);
            System.out.println("[AC-106] " + d + " 费用类变体 " + fee + " 个 = " + feeKeys);
            if (fee != e.getValue()) {
                err.append("\n  ").append(d).append(" 费用类变体数应=").append(e.getValue())
                        .append("（§9.2），实际=").append(fee).append(" ").append(feeKeys);
            }

            long total = scalarLong("SELECT count(*) FROM (SELECT DISTINCT tab_type, variant_key "
                    + "FROM semantic_tab_view WHERE dialect=?1 AND status='ACTIVE') t", d);
            long expectTotal = nonFeeTabs.size() + e.getValue();
            if (total != expectTotal) {
                err.append("\n  ").append(d).append(" (tab_type, variant_key) 组合总数应=").append(expectTotal)
                        .append("（5 个非费用类 + ").append(e.getValue()).append(" 个费用类变体），实际=").append(total);
            }
        }
        assertEquals("", err.toString(),
                "AC-106: 与 §9.2 映射表不逐格相等：" + err
                        + "\n  📌 8/7/15 的推导（供核对）：报价 16 主表 − 年降3 − 客户料号 − 物料 − 元素BOM − 物料BOM − 电镀方案 = 8；"
                        + "\n     基础核价 10 − 物料 − 元素BOM − 物料BOM = 7（无电镀方案表）；"
                        + "\n     明细核价 19 − 物料 − 元素BOM − 物料BOM − 电镀方案 = 15。"
                        + "\n     ⇒ 三套一致地把 *_plating_scheme 排除在『费用类变体』之外，这是 §9.2 的 8/7/15 唯一自洽解。"
                        + "\n     若种子把电镀方案也算成费用类变体，请主线裁决是改 §9.2 还是改种子。");
    }

    private static String fmt(List<Object[]> rows) {
        List<String> l = new ArrayList<>();
        for (Object[] r : rows) {
            l.add(java.util.Arrays.toString(r));
        }
        return l.toString();
    }
}
