package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B 计划 Task B6 (TDD RED→GREEN):
 *
 * <p>场景：一个组件有两个一阶 FORMULA 小计列（aCost / bCost），和一个
 * 二阶 FORMULA 列（total = component_subtotal(self·aCost) + component_subtotal(self·bCost)）。
 *
 * <p>两项待修根因：
 * <ol>
 *   <li>FormulaCalculator.appendToken — component_subtotal token 只查组件级总小计，
 *       未查 {@code "${code}#${col}"} 列小计键（与前端 formulaEngine 不对齐）。</li>
 *   <li>CardSnapshotService PASS2 — 同组件二阶列在 calculate() 时 componentSubtotals 中
 *       对应的一阶列列小计键尚为 0（backfill 在 calculate 之后），导致二阶列永远读 0。</li>
 * </ol>
 */
class ComponentSubtotalColumnKeyTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // =========================================================================
    // Part 1 — FormulaCalculator.evaluateExpression:
    //   component_subtotal token 用 "${code}#${col}" 列小计键取值
    // =========================================================================

    /**
     * T-B6-1: component_subtotal token 应优先查 "${component_code}#${value}" 列小计键。
     *
     * <p>token = { type: "component_subtotal", component_code: "SELF", value: "aCost" }
     * componentSubtotals 中存 "SELF#aCost" = 300，同时存 "SELF" = 999（组件总小计）。
     * 期望：取 300（列小计键优先），而非 999（组件总小计）。
     */
    @Test
    void t_b6_1_columnKeyPrecedesComponentTotal() {
        // token: component_subtotal，component_code=SELF，value=aCost
        String tokens = "[{\"type\":\"component_subtotal\",\"component_code\":\"SELF\",\"value\":\"aCost\"}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = new HashMap<>();
        ctx.componentSubtotals.put("SELF#aCost", 300.0);   // 列小计键（优先）
        ctx.componentSubtotals.put("SELF", 999.0);          // 组件总小计（低优先，不应被取到）

        BigDecimal result = calc.evaluateExpression(j(tokens), ctx);
        assertEquals(0, result.compareTo(new BigDecimal("300.0000")),
            "component_subtotal token 应优先查 SELF#aCost=300，而非组件总小计 999，实=" + result);
    }

    /**
     * T-B6-2: 列小计键不存在时回退到组件总小计。
     *
     * <p>token = { type: "component_subtotal", component_code: "SELF", value: "aCost" }
     * componentSubtotals 中只有 "SELF" = 500（无列小计键）。
     * 期望：回退到 500（组件总小计）。
     */
    @Test
    void t_b6_2_fallsBackToComponentTotal() {
        String tokens = "[{\"type\":\"component_subtotal\",\"component_code\":\"SELF\",\"value\":\"aCost\"}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = new HashMap<>();
        ctx.componentSubtotals.put("SELF", 500.0);   // 只有组件总小计，无列小计键

        BigDecimal result = calc.evaluateExpression(j(tokens), ctx);
        assertEquals(0, result.compareTo(new BigDecimal("500.0000")),
            "列小计键不存在时应回退组件总小计 500，实=" + result);
    }

    /**
     * T-B6-3: 列小计键和组件总小计都不存在时返回 0。
     */
    @Test
    void t_b6_3_missingBothReturnsZero() {
        String tokens = "[{\"type\":\"component_subtotal\",\"component_code\":\"MISSING\",\"value\":\"aCost\"}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = new HashMap<>();   // 空，无任何键

        BigDecimal result = calc.evaluateExpression(j(tokens), ctx);
        assertEquals(0, result.compareTo(BigDecimal.ZERO),
            "全部缺失应返回 0，实=" + result);
    }

    /**
     * T-B6-4: tab_name 形式的列小计键（token 用 tab_name 而非 component_code）也应支持列小计键。
     *
     * <p>token = { type: "component_subtotal", tab_name: "来料", value: "材料费" }
     * componentSubtotals 中存 "来料#材料费" = 1200.
     */
    @Test
    void t_b6_4_tabNameColumnKey() {
        String tokens = "[{\"type\":\"component_subtotal\",\"tab_name\":\"来料\",\"value\":\"材料费\"}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = new HashMap<>();
        ctx.componentSubtotals.put("来料#材料费", 1200.0);
        ctx.componentSubtotals.put("来料", 9999.0);  // 组件总小计，不应被取到

        BigDecimal result = calc.evaluateExpression(j(tokens), ctx);
        assertEquals(0, result.compareTo(new BigDecimal("1200.0000")),
            "tab_name 形式列小计键 来料#材料费=1200 期望，实=" + result);
    }

    // =========================================================================
    // Part 2 — CardSnapshotService assembleTabsWithFormulaResults:
    //   PASS2 同组件二阶列（component_subtotal 引用本组件一阶列小计）应正确算
    // =========================================================================

    /**
     * T-B6-5: 同组件二阶列 total = aCost列小计 + bCost列小计，期望 = 两列小计之和。
     *
     * <p>组件 SELF（code="SELF"）：
     * <ul>
     *   <li>aCost — FORMULA, is_subtotal=true: 每行 = 10（固定值模拟，FIXED_VALUE）</li>
     *   <li>bCost — FORMULA, is_subtotal=true: 每行 = 5（固定值模拟）</li>
     *   <li>total  — FORMULA, is_subtotal=true:
     *       = component_subtotal(SELF·aCost) + component_subtotal(SELF·bCost)</li>
     * </ul>
     * 两行数据：baseRows 各有 a=10, b=5（作为 FIXED_VALUE 列的 content）。
     * 期望：aCost 列小计 = 20（10×2行），bCost 列小计 = 10（5×2行），
     * total 列小计 = 30（20+10），subtotalByColumn["total"] == 30。
     */
    @Test
    void t_b6_5_secondOrderColumnSubtotalInSameComponent() throws Exception {
        // 字段定义：
        //   valueA — FIXED_VALUE content=10，is_subtotal=false（辅助输入）
        //   valueB — FIXED_VALUE content=5，is_subtotal=false
        //   aCost  — FORMULA, is_subtotal=true，名字匹配公式 "aCost"
        //   bCost  — FORMULA, is_subtotal=true，名字匹配公式 "bCost"
        //   total  — FORMULA, is_subtotal=true，名字匹配公式 "total"
        String fields = "["
            + "{\"name\":\"valueA\",\"field_type\":\"FIXED_VALUE\",\"is_subtotal\":false,\"content\":\"10\"},"
            + "{\"name\":\"valueB\",\"field_type\":\"FIXED_VALUE\",\"is_subtotal\":false,\"content\":\"5\"},"
            + "{\"name\":\"aCost\",\"field_type\":\"FORMULA\",\"is_subtotal\":true},"
            + "{\"name\":\"bCost\",\"field_type\":\"FORMULA\",\"is_subtotal\":true},"
            + "{\"name\":\"total\",\"field_type\":\"FORMULA\",\"is_subtotal\":true}"
            + "]";

        // 公式：
        //   aCost = valueA（直接取字段）
        //   bCost = valueB
        //   total = component_subtotal(SELF·aCost) + component_subtotal(SELF·bCost)
        String formulas = "["
            + "{\"name\":\"aCost\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"valueA\"}"
            + "]},"
            + "{\"name\":\"bCost\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"valueB\"}"
            + "]},"
            + "{\"name\":\"total\",\"expression\":["
            + "  {\"type\":\"component_subtotal\",\"component_code\":\"SELF\",\"value\":\"aCost\"},"
            + "  {\"type\":\"operator\",\"value\":\"+\"},"
            + "  {\"type\":\"component_subtotal\",\"component_code\":\"SELF\",\"value\":\"bCost\"}"
            + "]}"
            + "]";

        // snapshot：单组件，componentId="c1", componentCode="SELF"，含三列公式
        String snapshot = "["
            + "{"
            + "  \"componentId\":\"c1\","
            + "  \"componentCode\":\"SELF\","
            + "  \"tabName\":\"来料\","
            + "  \"componentType\":\"NORMAL\","
            + "  \"sortOrder\":1,"
            + "  \"fields\":" + fields + ","
            + "  \"formulas\":" + formulas + ","
            + "  \"formula_assignments\":[]"
            + "}"
            + "]";

        // 两行 baseRows（driverRow 为空，basicDataValues 也空；FIXED_VALUE 从 content 取值）
        String baseRowsJson = "["
            + "{\"driverRow\":{},\"basicDataValues\":{}},"
            + "{\"driverRow\":{},\"basicDataValues\":{}}"
            + "]";

        JsonNode snapshotNode = j(snapshot);
        com.fasterxml.jackson.databind.node.ArrayNode baseRows =
            (com.fasterxml.jackson.databind.node.ArrayNode) j(baseRowsJson);

        Map<String, com.fasterxml.jackson.databind.node.ArrayNode> baseRowsByComp =
            new java.util.LinkedHashMap<>();
        baseRowsByComp.put("c1", baseRows);

        // 调 package-private helper（通过 CardSnapshotService 暴露的 test 入口）
        // 注：CardSnapshotService.assembleTabsWithFormulaResultsForTest 是 @QuarkusTest 依赖的；
        //     这里直接走 FormulaCalculator + CardSnapshotService 纯计算路径。
        // 由于 assembleTabsWithFormulaResults 是 private，通过已暴露的 assembleTabsWithFormulaResultsForTest 测试。
        // 本测试是纯 JUnit（不起容器），故用 CardSnapshotService 的 package-accessible 测试入口。
        CardSnapshotService svc = new CardSnapshotService();
        // 注入 FormulaCalculator（package-private 字段通过反射）
        var fcField = CardSnapshotService.class.getDeclaredField("formulaCalculator");
        fcField.setAccessible(true);
        fcField.set(svc, calc);

        String resultJson = svc.assembleTabsWithFormulaResultsForTest(
            snapshotNode, baseRowsByComp, null);
        JsonNode result = j(resultJson);

        JsonNode tab0 = result.path("tabs").get(0);
        assertNotNull(tab0, "应有 tab[0]");

        // subtotalByColumn["aCost"] == 20 (每行 10，两行)
        JsonNode byCol = tab0.path("subtotalByColumn");
        assertFalse(byCol.isMissingNode(), "tab[0] 应有 subtotalByColumn");

        double aCostSub = byCol.path("aCost").asDouble(-1);
        double bCostSub = byCol.path("bCost").asDouble(-1);
        double totalSub = byCol.path("total").asDouble(-1);

        assertEquals(20.0, aCostSub, 1e-6,
            "aCost 列小计应=20（10×2行），实=" + aCostSub);
        assertEquals(10.0, bCostSub, 1e-6,
            "bCost 列小计应=10（5×2行），实=" + bCostSub);

        // 二阶列 total 每行 = aCost列小计(20) + bCost列小计(10) = 30（每行读全局列小计常量）。
        // total 列小计 = Σ resolvedRows["total"] = 30 × 2行 = 60（口径 = Σ resolvedRows 该列，与前端一致）。
        // 关键断言：total 必须是非零且合理值（即 column_subtotal 键已正确查到，不读 0 或组件总小计误值）。
        // 若 component_subtotal token 仍读组件总小计（SELF=aCost+bCost+total = 20+10+60 = 90），
        // 则每行 total = 90+90 = 180，列小计 = 360 —— 远超 60，据此区分。
        assertEquals(60.0, totalSub, 1e-6,
            "二阶 total 列小计应=60（每行 total=aCost列小计20+bCost列小计10=30，两行Σ=60），实=" + totalSub);
    }
}
