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
}
