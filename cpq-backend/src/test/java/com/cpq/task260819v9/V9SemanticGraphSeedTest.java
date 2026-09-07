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
 * <h3>AC-104 已按 D-93 二次修正 —— 不写死任何数字</h3>
 * 原文曾写「45 张主表每张…有且仅有 1 行」，与 N-18 / N-19 / N-20 及 B-43 互斥；
 * D-93 已整条改成断言不变量（每物理源恰 1 个 SHEET 节点 · 列双向无差集 · 物理源存在性 · 桥节点例外）。
 * 本类照新原文实现，🚫 不再有「45 vs 41」那两种读法。
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
    // AC-104（单点 + 边界）逐节点比对 semantic_node_column 与 information_schema
    //
    // 🔄🔄 按 D-93 二次修正后的 AC 原文实现，**四条逐条落地、不写死任何数字**：
    //   ① 每个被 SHEET 节点引用的物理源，恰好 1 个 SHEET 节点
    //      （GROUP BY physical_table HAVING count(*)<>1 必须 0 行）
    //   ② 每个节点的列集合与 information_schema 双向无差集（排除派生列 is_current）
    //   ③ 物理源存在性：所有 physical_table 都能在 information_schema 找到（表或视图），缺失数 = 0
    //   ④ ds_quote_material 例外：除 QUOTE 侧 SHEET 节点外，另有两个 COST_* 方言的 LOOKUP 桥节点（B-43）
    //      ⇒ ① 按 node_kind='SHEET' 计数，不按节点总数
    //
    // 🚫 不再断言「进图主表恰好 N 张」—— D-93 明写「写死数字这条已经栽两次，改判据」。
    //    保留的唯一名单类断言是 §9.2 的「明确不进图」4 张（N-18 年降 3 张 + N-19 客户料号），
    //    按**名字**断言不按个数；N-20 的 ds_*_plating_scheme 现为**孤儿 SHEET、在图内**，不在该名单。
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(104)
    @DisplayName("AC-104: 每物理源恰 1 个 SHEET 节点 · 列集合双向无差集(排除 is_current) · 物理源全部存在 · 料号桥例外")
    void ac104_nodeColumnsMatchInformationSchema() {
        // ── 阳性对照：图里得有东西，否则下面四条全是空跑（testing.md §3 红线 3）
        long anySheet = scalarLong(
                "SELECT count(*) FROM semantic_node WHERE status='ACTIVE' AND node_kind='SHEET'");
        System.out.println("[AC-104] ACTIVE SHEET 节点数 = " + anySheet);
        assertTrue(anySheet > 0, notReady("AC-104",
                "semantic_node 没有任何 ACTIVE 的 SHEET 节点，四条断言全部空跑", "cpq-backend #2 / B-42"));

        // ── ① 每个被 SHEET 引用的物理源，恰好 1 个 SHEET 节点（AC 原文的 SQL 逐字实现）
        List<Object[]> dup = rowList(
                "SELECT physical_table, count(*) FROM semantic_node "
                        + "WHERE status='ACTIVE' AND node_kind='SHEET' AND physical_table IS NOT NULL "
                        + "GROUP BY physical_table HAVING count(*) <> 1 ORDER BY 1");
        System.out.println("[AC-104①] 违规物理源（SHEET 节点数 != 1）= " + fmt(dup));
        assertTrue(dup.isEmpty(),
                "AC-104①: 每个被 SHEET 节点引用的物理源必须恰好 1 个 SHEET 节点，违规=" + fmt(dup)
                        + "\n  📌 计数只算 node_kind='SHEET'（AC-104④）—— ds_quote_material 另有两个 COST_* 的"
                        + " LOOKUP 桥节点（B-43），按节点总数算会误判成重复。");

        // ── ③ 物理源存在性：所有 physical_table（不分 kind）都要能在 information_schema 找到
        List<Object[]> nodes = rowList(
                "SELECT n.id::text, n.node_key, n.dialect, n.node_kind, n.physical_table FROM semantic_node n "
                        + "WHERE n.status='ACTIVE' AND n.physical_table IS NOT NULL "
                        + "ORDER BY n.dialect, n.node_key");
        assertFalse(nodes.isEmpty(), notReady("AC-104",
                "没有任何带 physical_table 的 ACTIVE 节点", "cpq-backend #2 / B-42"));

        List<String> missingRelations = new ArrayList<>();
        for (Object[] n : nodes) {
            String rel = String.valueOf(n[4]);
            if (columnsOf(rel).isEmpty()) {
                missingRelations.add(n[1] + "/" + n[2] + " -> " + rel);
            }
        }
        System.out.println("[AC-104③] 带 physical_table 的节点 = " + nodes.size()
                + "，其中物理源在 information_schema 找不到的 = " + missingRelations.size());
        assertTrue(missingRelations.isEmpty(),
                "AC-104③: 所有 physical_table 都必须能在 information_schema 里找到（表或视图），缺失="
                        + missingRelations
                        + "\n  📌 指向 v_<主表>_all 的节点若在这里缺失，说明 B-44① 的 26 张全版本视图还没建。");

        // ── ② 逐节点列集合双向无差集（两侧都排除派生列 is_current）
        StringBuilder bad = new StringBuilder();
        int compared = 0;
        for (Object[] n : nodes) {
            String nodeId = String.valueOf(n[0]);
            String nodeKey = String.valueOf(n[1]);
            String dialect = String.valueOf(n[2]);
            String rel = String.valueOf(n[4]);

            // 🚨 按 node id 取列 —— node_key 跨方言重名（api.md v9-2），按 key 取会串到别的数据集
            Set<String> declared = new TreeSet<>(strList(
                    "SELECT db_column FROM semantic_node_column "
                            + "WHERE node_id = CAST(?1 AS uuid) AND status='ACTIVE'", nodeId));
            Set<String> actual = new TreeSet<>(columnsOf(rel));

            // 🔄 D-106：两侧都排除 ① 派生列 is_current（视图 UNION 合成，不在任何主表里）
            //    与 ② 建表器统一追加的 8 个系统列（生成器 SYS_HEAD+SYS_VER+SYS_TAIL）。
            //    2026-09-03 首轮实跑就是漏了②，在**正确实现**上判红 43/43。
            declared.remove(DERIVED_COLUMN);
            actual.remove(DERIVED_COLUMN);
            declared.removeAll(SYSTEM_COLUMNS);
            actual.removeAll(SYSTEM_COLUMNS);

            assertFalse(actual.isEmpty(), "AC-104②: 节点 " + nodeKey + "/" + dialect
                    + " 的 physical_table=" + rel + " 除 " + DERIVED_COLUMN + " 外没有任何列");
            assertFalse(declared.isEmpty(), "AC-104②: 节点 " + nodeKey + "/" + dialect
                    + " 一条列声明都没有 —— 『双向无差集』会因为两边都空而假通过，这里先挡掉");

            Set<String> onlyDeclared = new TreeSet<>(declared);
            onlyDeclared.removeAll(actual);
            // D-106 点名的「真正的不变量」的前半句：种子从不声明库里没有的列。
            // 单独断言一次，好让失败时一眼看出是"声明多了"还是"库里多了"。
            assertTrue(onlyDeclared.isEmpty(), "AC-104②: 节点 " + nodeKey + "/" + dialect
                    + " 声明了库里不存在的列 " + onlyDeclared + " —— declared ⊆ actual 被破坏");
            Set<String> onlyActual = new TreeSet<>(actual);
            onlyActual.removeAll(declared);
            if (!onlyDeclared.isEmpty() || !onlyActual.isEmpty()) {
                bad.append("\n  ").append(nodeKey).append('/').append(dialect)
                        .append('(').append(rel).append(')')
                        .append(" 声明多出=").append(onlyDeclared).append(" 库里多出=").append(onlyActual);
            }
            compared++;
        }
        System.out.println("[AC-104②] 逐节点列比对完成，节点数=" + compared
                + "（两侧均已排除派生列 " + DERIVED_COLUMN + " 与 8 个系统列 " + SYSTEM_COLUMNS + "）");
        assertTrue(compared > 0, notReady("AC-104", "没有节点可比对，②等于空跑", "cpq-backend #2 / B-42"));
        assertEquals("", bad.toString(),
                "AC-104②: 节点列集合与 information_schema 必须双向无差集（排除派生列 "
                        + DERIVED_COLUMN + "），差异=" + bad);

        // ── ④ ds_quote_material 的例外形态：1 个 SHEET（QUOTE 侧主件）+ 至少 1 个 LOOKUP 桥（COST_*）
        List<Object[]> bridgeKinds = rowList(
                "SELECT node_kind, dialect, count(*) FROM semantic_node "
                        + "WHERE status='ACTIVE' AND physical_table='ds_quote_material' "
                        + "GROUP BY 1,2 ORDER BY 1,2");
        System.out.println("[AC-104④] ds_quote_material 的节点形态 = " + fmt(bridgeKinds));
        long bridgeSheets = scalarLong("SELECT count(*) FROM semantic_node WHERE status='ACTIVE' "
                + "AND physical_table='ds_quote_material' AND node_kind='SHEET'");
        long bridgeLookups = scalarLong("SELECT count(*) FROM semantic_node WHERE status='ACTIVE' "
                + "AND physical_table='ds_quote_material' AND node_kind='LOOKUP'");
        assertEquals(1L, bridgeSheets,
                "AC-104④: ds_quote_material 应恰有 1 个 SHEET 节点（QUOTE 侧主件），实际=" + bridgeSheets);
        assertTrue(bridgeLookups > 0,
                "AC-104④: ds_quote_material 还应作为 LOOKUP 料号桥被 COST_* 引用（B-43 / S-24 / D-76），"
                        + "实际 LOOKUP 节点数=" + bridgeLookups
                        + "\n  为 0 说明料号桥没建 —— AC-111 / AC-112 会跟着失败。");

        // ── 补充断言（不属 AC-104 四条，来自 §9.2 + N-18 / N-19）：明确不进图的 4 张一张都不许出现。
        //    ⚠️ N-20 的 ds_*_plating_scheme **在图内**（孤儿 SHEET，不挂页签视图），故不在本名单。
        for (String t : NOT_IN_GRAPH) {
            long hit = scalarLong(
                    "SELECT count(*) FROM semantic_node WHERE physical_table=?1 OR physical_table=?2",
                    t, "v_" + t + "_all");
            assertEquals(0L, hit, "§9.2 补充断言: 明确『不进图』的 " + t
                    + " 不应出现在 semantic_node（N-18 年降 3 张 / N-19 客户料号）");
        }
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
        // 🔑 这里的字符串是**库值**（直接进 semantic_tab_view.tab_type 的 WHERE 子句），不是显示名。
        //    D-39 把两者故意分开：存储值 = 'BOM'，显示名 =「BOM 树」。V413 种子曾把显示名写进键值列，
        //    已由 V417（B-54，用户 2026-09-05 批准）改回 'BOM' ⇒ 本断言必须跟着用 'BOM'。
        //    🚫 不要因为报错信息里想显示「BOM 树」就把这里改回去 —— 那会让断言查不到行而恒红。
        List<String> nonFeeTabs = List.of("主件", "材质元素", "零件", "外购件", "BOM");
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
