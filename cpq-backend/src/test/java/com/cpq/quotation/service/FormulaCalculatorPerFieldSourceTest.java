package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * per-field source 取值 — 多源 SUM 中 field token 携带 source 时按 source 分桶取值，
 * 防止两个 source 同名列串值。
 *
 * <p>场景：sources=[A, B]，A.费用=10，B.费用=3，targetExpr=[field(费用, source=B)]，agg=SUM，
 * 命中各 1 行。期望结果 = 3（B 的值）。
 *
 * <p>修复前：B.费用=3 放入 sub.fieldValues["费用"]，随后驱动行 arow（属于 sources[0]=A）
 * 覆盖同名 → sub.fieldValues["费用"]=10；field token 按名取到 10（串值）。
 * 修复后：field token 带 source=B → 优先 bySource["B"]["费用"]=3 → 结果=3。
 */
class FormulaCalculatorPerFieldSourceTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * RED 核心场景：A.费用=10（驱动/primary source），B.费用=3（coarse source）。
     * targetExpr = [{type:field, value:费用, source:B}], agg=SUM。
     * 期望 = 3（B 的费用）；修复前因驱动行覆盖会取到 10（A 的费用）。
     */
    @Test
    void perFieldSource_sameColumnName_takesCorrectSourceValue() {
        // sources[0]=A(驱动), sources[1]=B(coarse)
        // A 行: 料件=X, 费用=10
        // B 行: 料件=X, 费用=3
        // match 均按 料件=料件 联接
        String tokJson = "[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"A\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            + "  {\"type\":\"field\",\"value\":\"费用\",\"source\":\"B\"}"
            + "],"
            + "\"sources\":["
            + "  {\"source\":\"A\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            + "  {\"source\":\"B\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]"
            + "}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.currentRowRaw.put("料件", "X");
        ctx.crossTabRows.put("A", List.of(Map.of("料件", "X", "费用", 10.0)));
        ctx.crossTabRows.put("B", List.of(Map.of("料件", "X", "费用", 3.0)));

        // 修复前因驱动行(A)覆盖 sub.fieldValues["费用"]=10，field取到10，而非B的3
        // 修复后 field token 带 source=B → bySource["B"]["费用"]=3 → result=3
        java.math.BigDecimal result = calc.evaluateExpression(json(tokJson), ctx);
        assertEquals(3.0, result.doubleValue(), 1e-9,
            "field token 带 source=B，应取 B.费用=3，而非被驱动行 A.费用=10 覆盖");
    }

    /**
     * 无 source 的 field token — 存量兼容性回归：行为不变（按名 fieldValues 取合并值）。
     * A.费用=10（驱动行覆盖 coarse B.费用=3），无 source token 取到合并值=10（按名最终值）。
     */
    @Test
    void perFieldSource_noSource_fallsBackToMergedFieldValues() {
        // 同场景，但 targetExpr field 不带 source → 走现有按名逻辑
        String tokJson = "[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"A\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            + "  {\"type\":\"field\",\"value\":\"费用\"}"
            + "],"
            + "\"sources\":["
            + "  {\"source\":\"A\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            + "  {\"source\":\"B\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]"
            + "}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.currentRowRaw.put("料件", "X");
        ctx.crossTabRows.put("A", List.of(Map.of("料件", "X", "费用", 10.0)));
        ctx.crossTabRows.put("B", List.of(Map.of("料件", "X", "费用", 3.0)));

        // 无 source：驱动行 A 覆盖 → fieldValues["费用"]=10（现有行为保持）
        java.math.BigDecimal result = calc.evaluateExpression(json(tokJson), ctx);
        assertEquals(10.0, result.doubleValue(), 1e-9,
            "无 source 的 field token，应按名取合并后最终值（驱动行 A.费用=10 覆盖 B.费用=3）");
    }

    /**
     * per-field source 取 A（驱动行自身），A.费用=10，B.费用=3，field(source=A) → 10。
     */
    @Test
    void perFieldSource_sourceIsDriver_takesDriverValue() {
        String tokJson = "[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"A\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            + "  {\"type\":\"field\",\"value\":\"费用\",\"source\":\"A\"}"
            + "],"
            + "\"sources\":["
            + "  {\"source\":\"A\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            + "  {\"source\":\"B\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]"
            + "}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.currentRowRaw.put("料件", "X");
        ctx.crossTabRows.put("A", List.of(Map.of("料件", "X", "费用", 10.0)));
        ctx.crossTabRows.put("B", List.of(Map.of("料件", "X", "费用", 3.0)));

        java.math.BigDecimal result = calc.evaluateExpression(json(tokJson), ctx);
        assertEquals(10.0, result.doubleValue(), 1e-9,
            "field token 带 source=A（驱动行），应取 A.费用=10");
    }

    /**
     * 多源 SUM：A 有 2 行匹配，targetExpr 混用 source=B 和 source=A 列，
     * 逐行时每行的 per-source 桶独立，B 广播到 A 每行。
     * A 行0: 单价=2, 行1: 单价=5; B: 折扣=0.8（单行广播）
     * targetExpr=[field(单价,A) * field(折扣,B)] → 2*0.8 + 5*0.8 = 5.6
     */
    @Test
    void perFieldSource_multiRowSum_perSourceColumnsIsolated() {
        String tokJson = "[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"A\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            + "  {\"type\":\"field\",\"value\":\"单价\",\"source\":\"A\"},"
            + "  {\"type\":\"operator\",\"value\":\"*\"},"
            + "  {\"type\":\"field\",\"value\":\"折扣\",\"source\":\"B\"}"
            + "],"
            + "\"sources\":["
            + "  {\"source\":\"A\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            + "  {\"source\":\"B\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]"
            + "}]";

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.currentRowRaw.put("料件", "X");
        ctx.crossTabRows.put("A", List.of(
            Map.of("料件", "X", "单价", 2.0),
            Map.of("料件", "X", "单价", 5.0)
        ));
        ctx.crossTabRows.put("B", List.of(Map.of("料件", "X", "折扣", 0.8)));

        // 2*0.8 + 5*0.8 = 1.6 + 4.0 = 5.6
        java.math.BigDecimal result = calc.evaluateExpression(json(tokJson), ctx);
        assertEquals(5.6, result.doubleValue(), 1e-4,
            "A 行0:单价2*折扣0.8=1.6, 行1:单价5*折扣0.8=4.0, SUM=5.6");
    }
}
