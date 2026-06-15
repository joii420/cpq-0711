package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorPredicateTest {
    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper M = new ObjectMapper();

    private FormulaCalculator.RowContext ctxWith(List<Map<String,Object>> aRows, Map<String,Object> hostRow) {
        var ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows.put("compA", aRows);
        ctx.currentRowRaw = new java.util.HashMap<>(hostRow);
        return ctx;
    }

    @Test void sumif_filters_by_literal_predicate() throws Exception {
        // SUMIF([compA.类型]='管理费', [compA.金额])  → 只加管理费行
        var token = M.readTree("{"
            + "\"type\":\"cross_tab_ref\",\"source\":\"compA\",\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"target\":\"金额\","
            + "\"predicate\":{\"op\":\"=\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"类型\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"管理费\"}}}");
        var ctx = ctxWith(List.of(
            Map.of("类型","管理费","金额","10"),
            Map.of("类型","运费","金额","5"),
            Map.of("类型","管理费","金额","7")), Map.of());
        Object v = calc.evalCrossTab(token, ctx);
        assertEquals(0, new java.math.BigDecimal("17").compareTo((java.math.BigDecimal) v));
    }

    @Test void predicate_absent_keeps_legacy_behavior() throws Exception {
        // 无 predicate → 现状：match=[] 全量求和
        var token = M.readTree("{"
            + "\"type\":\"cross_tab_ref\",\"source\":\"compA\",\"agg\":\"SUM\",\"match\":[],\"target\":\"金额\"}");
        var ctx = ctxWith(List.of(Map.of("金额","10"), Map.of("金额","5")), Map.of());
        Object v = calc.evalCrossTab(token, ctx);
        assertEquals(0, new java.math.BigDecimal("15").compareTo((java.math.BigDecimal) v));
    }
}
