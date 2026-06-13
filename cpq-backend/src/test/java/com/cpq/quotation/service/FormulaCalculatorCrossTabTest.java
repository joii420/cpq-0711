package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** cross_tab_ref token 求值（与前端 formulaEngine.ts 对齐）。 */
class FormulaCalculatorCrossTabTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    private FormulaCalculator.RowContext ctxWith(String bKey, Object bVal,
            List<Map<String, Object>> aRows) {
        FormulaCalculator.RowContext c = new FormulaCalculator.RowContext();
        c.currentRowRaw.put(bKey, bVal);
        c.crossTabRows.put("A", aRows);
        return c;
    }

    private JsonNode tok(String agg, String target) throws Exception {
        return om.readTree("[{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"target\":\""
            + target + "\",\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],\"agg\":\"" + agg + "\"}]");
    }

    @Test void none_oneMatch_returnsValue() throws Exception {
        var aRows = List.<Map<String, Object>>of(
            Map.of("子件", "P1", "单重", new java.math.BigDecimal("0.8")),
            Map.of("子件", "P2", "单重", new java.math.BigDecimal("0.3")));
        var ctx = ctxWith("子件", "P1", aRows);
        assertEquals(0, new java.math.BigDecimal("0.8000").compareTo(
            calc.evaluateExpression(tok("NONE", "单重"), ctx)));
    }

    @Test void none_zeroMatch_returnsZero() throws Exception {
        var ctx = ctxWith("子件", "PX", List.of(Map.of("子件", "P1", "单重", "0.8")));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
            calc.evaluateExpression(tok("NONE", "单重"), ctx)));
    }

    @Test void none_multiMatch_isError_treatedAsZero() throws Exception {
        var aRows = List.<Map<String, Object>>of(
            Map.of("子件", "P1", "单重", "0.8"), Map.of("子件", "P1", "单重", "0.5"));
        var ctx = ctxWith("子件", "P1", aRows);
        // 多匹配 → 错误哨兵 → evaluateExpression 整体兜 0（对齐既有异常→0 口径）
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
            calc.evaluateExpression(tok("NONE", "单重"), ctx)));
    }

    @Test void sum_multiMatch_sums() throws Exception {
        var aRows = List.<Map<String, Object>>of(
            Map.of("子件", "P1", "单重", "0.8"), Map.of("子件", "P1", "单重", "0.5"),
            Map.of("子件", "P2", "单重", "9"));
        var ctx = ctxWith("子件", "P1", aRows);
        assertEquals(0, new java.math.BigDecimal("1.3000").compareTo(
            calc.evaluateExpression(tok("SUM", "单重"), ctx)));
    }

    @Test void count_countsMatches() throws Exception {
        var aRows = List.<Map<String, Object>>of(
            Map.of("子件", "P1", "单重", "0.8"), Map.of("子件", "P1", "单重", "0.5"));
        var ctx = ctxWith("子件", "P1", aRows);
        assertEquals(0, new java.math.BigDecimal("2.0000").compareTo(
            calc.evaluateExpression(tok("COUNT", ""), ctx)));
    }

    @Test void nullKey_doesNotMatch() throws Exception {
        var aRows = List.<Map<String, Object>>of(new HashMap<>() {{ put("子件", null); put("单重", "5"); }});
        var ctx = ctxWith("子件", "", aRows);
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
            calc.evaluateExpression(tok("SUM", "单重"), ctx)));
    }

    private JsonNode json(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Task 1.3 端到端：currentRowRaw（本行原始 子件=P1，由 calculate 从 driverRow 合并自动注入）
     * + crossTabRows（兄弟组件 A 已算行）经 10 参 calculate 透传到逐行 RowContext，
     * cross_tab_ref(source=A,target=单重,match[子件=子件],NONE) 命中 P1 行 → FORMULA 结果 = 0.8。
     */
    @Test void calculate_threadsCurrentRowRawAndCrossTabRows() {
        // fields: 子件(INPUT_TEXT, 提供 currentRowRaw 文本键) + 重量(FORMULA → cross_tab_ref)
        JsonNode fields = json("["
            + "{\"name\":\"子件\",\"fieldType\":\"INPUT_TEXT\"},"
            + "{\"name\":\"重量\",\"fieldType\":\"FORMULA\"}"
            + "]");
        // FORMULA 字段名 == 公式名 → 走 resolveFormulaExpression 第 2 优先级（字段名==公式名）
        JsonNode formulas = json("[{\"name\":\"重量\",\"expression\":["
            + "{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"target\":\"单重\","
            + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],\"agg\":\"NONE\"}]}]");
        JsonNode rkf = json("[\"子件\"]");
        // baseRow.driverRow.子件 = P1 → calculate 合并进 currentRowRaw（原始文本，未数值化）
        JsonNode baseRows = json("[{\"driverRow\":{\"子件\":\"P1\"},\"basicDataValues\":{}}]");

        // 兄弟组件 A 已算行：P1→0.8, P2→0.3
        Map<String, List<Map<String, Object>>> crossTabRows = Map.of("A", List.of(
            Map.of("子件", "P1", "单重", new java.math.BigDecimal("0.8")),
            Map.of("子件", "P2", "单重", new java.math.BigDecimal("0.3"))));

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), crossTabRows);

        assertEquals(1, fr.size());
        assertEquals(0.8, fr.get(0).path("values").path("重量").asDouble(), 1e-9);
    }

    /** 9 参旧签名委派 → crossTabRows 为空 → cross_tab_ref 命中 0 行 → NONE 返 0（行为不变验证）。 */
    @Test void calculate_legacyNineArg_noCrossTab_returnsZero() {
        JsonNode fields = json("["
            + "{\"name\":\"子件\",\"fieldType\":\"INPUT_TEXT\"},"
            + "{\"name\":\"重量\",\"fieldType\":\"FORMULA\"}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"重量\",\"expression\":["
            + "{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"target\":\"单重\","
            + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],\"agg\":\"NONE\"}]}]");
        JsonNode rkf = json("[\"子件\"]");
        JsonNode baseRows = json("[{\"driverRow\":{\"子件\":\"P1\"},\"basicDataValues\":{}}]");

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());

        assertEquals(1, fr.size());
        assertEquals(0.0, fr.get(0).path("values").path("重量").asDouble(), 1e-9);
    }

    @Test void targetExpr_none_aTimesB() throws Exception {
        var aRows = List.<Map<String, Object>>of(Map.of("子件", "P1", "单价", "2"));
        FormulaCalculator.RowContext c = new FormulaCalculator.RowContext();
        c.currentRowRaw.put("子件", "P1");
        c.currentRowRaw.put("数量", 3);
        c.crossTabRows.put("A", aRows);
        var tok = om.readTree("[{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"agg\":\"NONE\","
            + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"b_field\",\"value\":\"数量\"}]}]");
        assertEquals(0, new java.math.BigDecimal("6.0000").compareTo(calc.evaluateExpression(tok, c)));
    }

    @Test void targetExpr_sum_perRowThenAggregate() throws Exception {
        var aRows = List.<Map<String, Object>>of(
            Map.of("子件", "P1", "单价", "2", "数量", "3"),
            Map.of("子件", "P1", "单价", "4", "数量", "1"));
        FormulaCalculator.RowContext c = new FormulaCalculator.RowContext();
        c.currentRowRaw.put("子件", "P1");
        c.crossTabRows.put("A", aRows);
        var tok = om.readTree("[{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"agg\":\"SUM\","
            + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}]}]");
        assertEquals(0, new java.math.BigDecimal("10.0000").compareTo(calc.evaluateExpression(tok, c)));
    }

    /**
     * 回归（spec 2026-06-13）：宿主字段 料件(INPUT_TEXT) 经 default_source.BASIC_DATA 绑定到驱动列
     * $ll_view._料件 —— driverRow 只有 _料件，没有 料件。calculate 必须把 default_source 解析进
     * currentRowRaw[料件]，cross_tab_ref match[料件=料件] 才能命中源行 → SUM 目标列。
     * 修复前：currentRowRaw 缺 料件 → 命中 0 行 → 结果 0（红）。
     */
    @Test void calculate_hostInputDefaultSource_resolvesMatchKey() {
        JsonNode fields = json("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + "  \"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$ll_view._料件\"}},"
            + "{\"name\":\"材料费\",\"fieldType\":\"FORMULA\"}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"材料费\",\"expression\":["
            + "{\"type\":\"cross_tab_ref\",\"source\":\"元素\",\"target\":\"单价\","
            + "\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}],\"agg\":\"SUM\"}]}]");
        JsonNode rkf = json("[\"料件\"]");
        JsonNode baseRows = json("[{\"driverRow\":{\"_料件\":\"料8\"},"
            + "\"basicDataValues\":{\"{$ll_view._料件}\":\"料8\"}}]");
        Map<String, List<Map<String, Object>>> crossTabRows = Map.of("元素", List.of(
            Map.of("料件", "料8", "单价", new java.math.BigDecimal("60")),
            Map.of("料件", "料8", "单价", new java.math.BigDecimal("34.5")),
            Map.of("料件", "料9", "单价", new java.math.BigDecimal("99"))));

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), crossTabRows);

        assertEquals(1, fr.size());
        assertEquals(94.5, fr.get(0).path("values").path("材料费").asDouble(), 1e-4);
    }

    @Test void targetExpr_takesPriorityOverTarget() throws Exception {
        var aRows = List.<Map<String, Object>>of(Map.of("子件", "P1", "单价", "2", "数量", "3"));
        FormulaCalculator.RowContext c = new FormulaCalculator.RowContext();
        c.currentRowRaw.put("子件", "P1");
        c.crossTabRows.put("A", aRows);
        var tok = om.readTree("[{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"agg\":\"NONE\",\"target\":\"单价\","
            + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}]}]");
        assertEquals(0, new java.math.BigDecimal("6.0000").compareTo(calc.evaluateExpression(tok, c)));
    }
}
