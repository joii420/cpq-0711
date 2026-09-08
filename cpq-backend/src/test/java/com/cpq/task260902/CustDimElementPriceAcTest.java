package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907 · AC-8（元素价格策略闭环）。</b>
 *
 * <h3>🚩 先说清楚一件必须由主线/用户裁决的事：AC 原文与已落地实现<b>走的是两条路</b></h3>
 * {@code 需求文档.md:157} 的 AC-8 断言①②写的是：
 * <blockquote>① 语义图<b>新增节点</b> {@code FUNC_CUSTOMER_ELEMENT_PRICE}（node_kind=FUNCTION, dialect=QUOTE）；
 * ② 声明 3 列 unit_price / currency / price_unit。</blockquote>
 * 而实际落地的 <b>B-8 = V425</b>：不新增节点、不动任何边，改为给现有函数
 * {@code f_material_element_price} 的 {@code candidate_materials} <b>补读</b>
 * {@code ds_quote_material_bom} / {@code ds_quote_element_bom} 两支。
 *
 * <p>⇒ <b>AC-8 原文的①②在当前实现下永远不可能成立</b>（节点数恒为 0）。
 * 本类按<b>主线明确下达的口径</b>（新节点数 = 0、PRICE 边不变）落断言，
 * 并在 {@link #ac8_d_contractConflictOnRecord()} 里把这个冲突<b>如实登记成一条会打印的取证</b>，
 * 🚫 不替谁把 AC 原文重新解释一遍。**请主线回写 需求文档.md，否则下一个人照原文验会得到假红。**
 *
 * <h3>三条判据（主线下达）</h3>
 * <ol>
 *   <li><b>值中性（纯加法）</b>：改动前后逐客户对拍，「只在旧」必须全 0。
 *       ⚠️ 这条<b>需要同时连两个库</b>（改动前的函数体只在共享库 V422 上），单库的 {@code @QuarkusTest}
 *       做不到 ⇒ 由 psql 实验承担，证据落 {@code 证据/AC-8值中性对拍.out}，本类不重复。
 *       🚫 判据用 {@code EXCEPT ALL} 双向差集，<b>不用</b> {@code md5(string_agg(... ORDER BY 1,2))} ——
 *       聚合内的 {@code ORDER BY 1,2} 是<b>常量不是列序号</b>，行序随执行计划变，会对任何改计划的改动报假阳性。</li>
 *   <li><b>闭环</b>：{@code ds_} 独有料号中至少 1 个能取到<b>非空</b>单价 —— {@link #ac8_b_dsOnlyMaterialGetsPrice()}</li>
 *   <li><b>不回归</b>：PRICE 组恒 1 组、groupKey 仍是 FUNC_ELEMENT_PRICE、新节点 0 个、
 *       PRICE 边连接键 <b>2 条</b> —— {@link #ac8_c_priceEdgeAndGroupUnchanged()}</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260907 · AC-8 元素价格策略闭环（按 B-8 = V425 加法式口径）")
class CustDimElementPriceAcTest extends CustDimBase {

    private static final String TAB_TYPE = "材质元素";
    private static final String DIALECT = "QUOTE";
    private static final String PRICE_NODE = "FUNC_ELEMENT_PRICE";
    private static final String AC8_NEW_NODE = "FUNC_CUSTOMER_ELEMENT_PRICE";

    // ============================================================
    // 判据 ②：闭环 —— ds_ 独有料号能取到非空单价
    // ============================================================

    @Test
    @DisplayName("AC-8(闭环)：ds_quote_* 独有（V6 里没有）的料号中，至少 1 个能从 f_material_element_price 取到非空单价")
    void ac8_b_dsOnlyMaterialGetsPrice() {
        // ── 守卫在被守卫对象上游：先证明「ds_ 独有料号」这个集合非空 ──
        List<String> dsOnly = strCol(
                "WITH ds_only AS ("
                        + "  SELECT DISTINCT material_no FROM " + T_MATERIAL_BOM
                        + "  UNION SELECT DISTINCT material_no FROM ds_quote_element_bom"
                        + "  EXCEPT SELECT DISTINCT material_no FROM material_bom_item"
                        + "  EXCEPT SELECT DISTINCT material_no FROM element_bom_item)"
                        + " SELECT material_no FROM ds_only ORDER BY 1");
        assertFalse(dsOnly.isEmpty(),
                "AC-8(闭环) 前置：ds_quote_* 里没有任何「V6 没有」的料号 ⇒ "
                        + "「新料号也能取到价」在当前数据下恒真（空跑），验不出闭环。"
                        + "\n  🚧 这正是 AC-8 背景里写的那种情况：函数返 21 个料号、V6 里没有的 = 0。"
                        + "需要先造/导入 ds_ 独有料号才谈得上这条 AC。");
        System.out.println("[AC-8(闭环)] ds_ 独有料号 " + dsOnly.size() + " 个：" + dsOnly);

        // 🚫 不写死客户号：任务书早期写 CUST-0004，V427 回填改口径后主力是 CUST-0001，数字会漂。
        //    改为「从 ds_ 表里实际出现的客户号里逐个试」，并把命中的组合打印出来。
        List<String> customers = strCol(
                "SELECT DISTINCT customer_no FROM " + T_MATERIAL_BOM
                        + " UNION SELECT DISTINCT customer_no FROM ds_quote_element_bom ORDER BY 1");
        assertFalse(customers.isEmpty(), "AC-8(闭环) 前置：ds_quote_* 里一个客户号都没有");
        System.out.println("[AC-8(闭环)] ds_quote_* 里出现的客户号 = " + customers);

        List<String> hits = new ArrayList<>();
        for (String cust : customers) {
            List<Object[]> rs = rows(
                    "WITH ds_only AS ("
                            + "  SELECT DISTINCT material_no FROM " + T_MATERIAL_BOM
                            + "  UNION SELECT DISTINCT material_no FROM ds_quote_element_bom"
                            + "  EXCEPT SELECT DISTINCT material_no FROM material_bom_item"
                            + "  EXCEPT SELECT DISTINCT material_no FROM element_bom_item)"
                            + " SELECT f.material_no, f.element_code, f.unit_price, f.currency, f.price_unit"
                            + " FROM ds_only d"
                            + " JOIN LATERAL f_material_element_price('" + cust.replace("'", "''")
                            + "', CURRENT_DATE, NULL) f ON f.material_no = d.material_no"
                            + " WHERE f.unit_price IS NOT NULL ORDER BY 1,2");
            for (Object[] r : rs) {
                hits.add(cust + " / " + r[0] + " / " + r[1] + " = " + r[2] + " " + r[3] + "/" + r[4]);
            }
        }
        hits.forEach(h -> System.out.println("    [AC-8(闭环)] 命中：" + h));
        assertFalse(hits.isEmpty(),
                "AC-8(闭环)：ds_ 独有料号 " + dsOnly + " 在客户 " + customers
                        + " 下一条非空单价都取不到 ⇒ 配置器仍然配不出对新料号有效的元素单价列。"
                        + "\n  ⚠️ 排查顺序（该函数有三层数据依赖，缺任一层该元素【整行不出现】而不是返 0）："
                        + "\n    ① element 表里该元素是否 ACTIVE；"
                        + "\n    ② 该客户有没有 element_price_strategy；"
                        + "\n    ③ element_daily_price 有没有行情。"
                        + "\n  三层齐了还取不到，才是 V425 的 candidate_materials 没补对。");
    }

    // ============================================================
    // 判据 ③：不回归 —— 语义图一条边一个节点都没动
    // ============================================================

    @Test
    @DisplayName("AC-8(不回归)：新节点 0 个 · PRICE 边恒 1 条 · 连接键 2 条(element_code + material_no) · "
            + "QUOTE/材质元素 的 PRICE 组恒 1 组且 groupKey 仍是 FUNC_ELEMENT_PRICE")
    void ac8_c_priceEdgeAndGroupUnchanged() {
        // ① 没有新增节点（主线口径；与 AC-8 原文①相反，冲突登记见 ac8_d）
        long newNode = count("SELECT count(*) FROM semantic_node WHERE node_key = '" + AC8_NEW_NODE + "'");
        assertEquals(0L, newNode,
                "AC-8(不回归)：语义图里出现了 " + AC8_NEW_NODE + " 节点 " + newNode + " 个。"
                        + "主线口径是 B-8 走 V425 加法式、【不新增节点】。"
                        + "若确实改走 AC-8 原文的新节点方案，这条断言要连同 需求文档.md 一起重定。");

        // ② PRICE 边恒 1 条（两条 PRICE 边会重现「两个价格策略组」缺陷，FieldTreeBuilder 只取一条）
        List<Object[]> priceEdges = rows(
                "SELECT e.id::text, fn.node_key, tn.node_key, e.status FROM semantic_edge e "
                        + "JOIN semantic_node fn ON fn.id = e.from_node_id "
                        + "JOIN semantic_node tn ON tn.id = e.to_node_id "
                        + "WHERE e.edge_kind = 'PRICE' AND e.status = 'ACTIVE' ORDER BY 1");
        assertFalse(priceEdges.isEmpty(),
                "AC-8(不回归) 前置：一条 ACTIVE 的 PRICE 边都没有 ⇒ 「连接键 2 条」会以空跑的形态通过，"
                        + "而配置器的价格策略组会整个消失。");
        priceEdges.forEach(e -> System.out.println("    [AC-8(不回归)] PRICE 边 " + e[1] + " -> " + e[2]
                + "（" + e[3] + "）"));
        assertEquals(1, priceEdges.size(),
                "AC-8(不回归)：ACTIVE 的 PRICE 边有 " + priceEdges.size() + " 条。"
                        + "FieldTreeBuilder 的 priceEdge 专用块【只取一条】，多于一条行为未定义 —— "
                        + "会重现 task-260907-取数配置器补齐 AC-30「返回两个价格策略组、用户无从分辨该拖哪个」。");
        assertEquals(PRICE_NODE, String.valueOf(priceEdges.get(0)[2]),
                "AC-8(不回归)：PRICE 边的目标节点变了");

        // ③ 🚨 连接键必须是 2 条：element_code + material_no
        //    V424 曾把 material_no 键删掉、V425 还原 —— 漏了会变成单条件 JOIN，
        //    跨料号笛卡尔扇出【且不报错】，症状是价格行数暴涨而不是报错。
        List<String> keys = strCol("SELECT k.left_column || '=' || k.right_column "
                + "FROM semantic_edge_key k WHERE k.edge_id = '" + priceEdges.get(0)[0] + "' ORDER BY k.seq");
        System.out.println("[AC-8(不回归)] PRICE 边连接键 = " + keys);
        assertEquals(2, keys.size(),
                "AC-8(不回归)：PRICE 边的连接键有 " + keys.size() + " 条（应为 2：element_code + material_no）。"
                        + "实际=" + keys
                        + "\n  🚨 少一条 material_no 会让 JOIN 退化成单条件，跨料号笛卡尔扇出且不报错。");
        Set<String> leftCols = new LinkedHashSet<>(strCol("SELECT k.left_column FROM semantic_edge_key k "
                + "WHERE k.edge_id = '" + priceEdges.get(0)[0] + "'"));
        assertEquals(Set.of("element_code", "material_no"), leftCols,
                "AC-8(不回归)：PRICE 边的连接键列集合应为 {element_code, material_no}，实际=" + leftCols);

        // ④ 配置器面板：QUOTE/材质元素 下 PRICE 组恒 1 组，groupKey 仍是 FUNC_ELEMENT_PRICE
        Response r = RestAssured.given().cookie("CPQ_SESSION", session())
                .queryParam("tabType", TAB_TYPE).queryParam("variantKey", "").queryParam("dialect", DIALECT)
                .when().get("/api/cpq/config/semantic-graph/field-tree");
        assertStatus(r, 200, "AC-8(不回归) field-tree");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) r.jsonPath().get("groups");
        assertNotNull(groups, "AC-8(不回归)：field-tree 响应无 groups 字段。body=" + r.asString());
        assertFalse(groups.isEmpty(),
                "AC-8(不回归)：" + DIALECT + "/" + TAB_TYPE + " 的 groups 为空 ⇒ 「PRICE 组恒 1 组」空跑");
        List<String> priceGroups = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if ("PRICE".equals(String.valueOf(g.get("groupKind")))) {
                priceGroups.add(String.valueOf(g.get("groupKey")));
            }
        }
        System.out.println("[AC-8(不回归)] " + DIALECT + "/" + TAB_TYPE + " 的分组 = "
                + groups.stream().map(g -> g.get("groupKind") + ":" + g.get("groupKey")).toList());
        assertEquals(List.of(PRICE_NODE), priceGroups,
                "AC-8(不回归)：" + DIALECT + "/" + TAB_TYPE + " 的 PRICE 组应恰好 1 组且 groupKey="
                        + PRICE_NODE + "，实际=" + priceGroups);
    }

    // ============================================================
    // 冲突登记（打印，不作失败判据）
    // ============================================================

    @Test
    @DisplayName("AC-8(冲突登记)：AC 原文①②要求新增 FUNC_CUSTOMER_ELEMENT_PRICE 节点，实现走的是 V425 加法式 —— "
            + "如实取证，请主线回写文档")
    void ac8_d_contractConflictOnRecord() {
        long newNode = count("SELECT count(*) FROM semantic_node WHERE node_key = '" + AC8_NEW_NODE + "'");
        long funcExists = count("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'public' AND p.proname = 'f_customer_element_price'");
        System.out.println("========================================================================");
        System.out.println("[AC-8 冲突登记] 需求文档.md:157 AC-8① 要求语义图新增节点 " + AC8_NEW_NODE + "，");
        System.out.println("                 ② 要求它声明 3 列 unit_price / currency / price_unit。");
        System.out.println("                 实测：该节点数 = " + newNode + "（主线口径就是 0，B-8 走 V425 加法式）；");
        System.out.println("                 而 DB 函数 f_customer_element_price 本身存在 = " + (funcExists > 0));
        System.out.println("                 ⇒ AC-8①② 在当前实现下【永远不成立】。");
        System.out.println("                 🚦 请主线回写 需求文档.md，把 AC-8 改写成 V425 口径，");
        System.out.println("                    否则下一个人照原文验会得到【假红 —— 红的是文档过期，不是功能坏了】。");
        System.out.println("                 🚫 本用例不替谁裁决，只打印，不失败。");
        System.out.println("========================================================================");
        // 唯一的硬断言：本条取证本身必须是可执行的（能查到语义图表），否则登记就是空话
        assertTrue(count("SELECT count(*) FROM semantic_node") > 0,
                "AC-8(冲突登记)：semantic_node 表 0 行 ⇒ 本条取证的数字全都不可信");
    }
}
