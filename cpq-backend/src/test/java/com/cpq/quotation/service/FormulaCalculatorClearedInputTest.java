package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 护栏测试 — INPUT 默认值"显式清空('')→ 按 0 算"，区别于"键缺失 → content 兜底"。
 *
 * <p>场景（用户 2026-06-22 反馈）：汇率(INPUT_NUMBER, content=6.9755)被用户清空后，
 * 引用它的小计列应按 0 算，而不是回落到默认值 6.9755。
 * 与前端 inputDefaultCompute.test.ts「显式清空("")→ 不再 content 兜底」对称。
 */
@DisplayName("FormulaCalculatorClearedInputTest — 清空 INPUT 默认值按 0 算")
class FormulaCalculatorClearedInputTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // 汇率: INPUT_NUMBER content=6.9755; 金额: FORMULA isSubtotal = field(汇率)
    private static final String FIELDS = "["
        + "{\"name\":\"汇率\",\"fieldType\":\"INPUT_NUMBER\",\"content\":\"6.9755\"},"
        + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"金额\",\"expression\":[{\"type\":\"field\",\"value\":\"汇率\"}]}"
        + "]";
    private static final String RKF = "[\"__seq_no__\"]";
    private static final String BASEROWS = "[{\"driverRow\":{},\"basicDataValues\":{}}]";

    @Test
    @DisplayName("T1: 显式清空 汇率='' → 金额小计 = 0（不回落 content=6.9755）")
    void t1_clearedInput_subtotalZero() {
        JsonNode editRows = j("[{\"rowKey\":\"0\",\"values\":{\"汇率\":\"\"}}]");
        Map<String, BigDecimal> byCol = calc.computeTabSubtotalsByColumn(
            j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), editRows, Map.of()
        );
        assertEquals(0, byCol.getOrDefault("金额", BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
            "清空后金额小计应=0，实=" + byCol.get("金额"));
    }

    @Test
    @DisplayName("T2: 键缺失（无 editRows）→ 金额小计 = content 兜底 6.9755")
    void t2_absentKey_usesContentDefault() {
        Map<String, BigDecimal> byCol = calc.computeTabSubtotalsByColumn(
            j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"), Map.of()
        );
        assertEquals(0, byCol.getOrDefault("金额", BigDecimal.ZERO).compareTo(new BigDecimal("6.9755")),
            "未填时金额小计应=content 6.9755，实=" + byCol.get("金额"));
    }

    // cross_tab：宿主匹配键(子件 INPUT_TEXT, driverRow=P1)被显式清空 → resolveRowByFieldName 不再回落
    // driverRow → 跨页签匹配落空 → 重量=0（区别于不清空时命中 A.单重=0.8）。锁 resolveRowByFieldName 改动。
    private static final String CT_FIELDS = "["
        + "{\"name\":\"子件\",\"fieldType\":\"INPUT_TEXT\"},"
        + "{\"name\":\"重量\",\"fieldType\":\"FORMULA\"}"
        + "]";
    private static final String CT_FORMULAS = "[{\"name\":\"重量\",\"expression\":["
        + "{\"type\":\"cross_tab_ref\",\"source\":\"A\",\"target\":\"单重\","
        + "\"match\":[{\"a\":\"子件\",\"b\":\"子件\"}],\"agg\":\"NONE\"}]}]";
    private static final String CT_RKF = "[\"子件\"]";
    private static final String CT_BASEROWS = "[{\"driverRow\":{\"子件\":\"P1\"},\"basicDataValues\":{}}]";

    @Test
    @DisplayName("T3: cross_tab 宿主匹配键清空 → 不回落 driverRow → 跨页签命中 0（重量=0）")
    void t3_clearedHostMatchKey_crossTabNoMatch() {
        Map<String, java.util.List<Map<String, Object>>> crossTabRows = Map.of("A", java.util.List.of(
            Map.of("子件", "P1", "单重", new BigDecimal("0.8")),
            Map.of("子件", "P2", "单重", new BigDecimal("0.3"))));
        JsonNode editClear = j("[{\"rowKey\":\"P1\",\"values\":{\"子件\":\"\"}}]");
        JsonNode fr = calc.calculate(j(CT_FIELDS), j(CT_FORMULAS), null, j(CT_RKF),
            j(CT_BASEROWS), editClear,
            new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>(), crossTabRows);
        assertEquals(0.0, fr.get(0).path("values").path("重量").asDouble(), 1e-9,
            "清空匹配键后跨页签应落空 → 重量=0");
    }

    @Test
    @DisplayName("T4: 不清空匹配键 → 跨页签命中 A.单重=0.8（对照 T3，证明仅清空才落空）")
    void t4_intactHostMatchKey_crossTabHits() {
        Map<String, java.util.List<Map<String, Object>>> crossTabRows = Map.of("A", java.util.List.of(
            Map.of("子件", "P1", "单重", new BigDecimal("0.8")),
            Map.of("子件", "P2", "单重", new BigDecimal("0.3"))));
        JsonNode fr = calc.calculate(j(CT_FIELDS), j(CT_FORMULAS), null, j(CT_RKF),
            j(CT_BASEROWS), j("[]"),
            new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>(), crossTabRows);
        assertEquals(0.8, fr.get(0).path("values").path("重量").asDouble(), 1e-9,
            "未清空时应命中 A.单重=0.8");
    }
}
