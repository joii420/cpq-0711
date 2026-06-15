package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 护栏测试 — 同页签公式列 field 引用逐行求值（E 计划 Task E4）。
 *
 * <p>验证：{@code 材料成本 = field(colA) + field(colB)} 中
 * {@code field} token 引用的是"本组件另一公式列"，后端 {@code addExprFieldDeps}
 * 把它收入拓扑依赖，逐行 {@code ctx.fieldValues.put(name, val)} 回填，
 * 下游公式读到的是<b>同行</b>各列值（不是整列总计标量）。
 *
 * <pre>
 *  数据：两行
 *   行1：colA = field(inputX) = 5,  colB = field(inputY) = 2  → 材料成本 = 7
 *   行2：colA = field(inputX) = 0,  colB = field(inputY) = 3  → 材料成本 = 3
 *   小计（Σ 行）= 10
 * </pre>
 *
 * <p>token 均为 {@code field} 型（非 {@code component_subtotal} 整列总计）。
 * 纯 JUnit，不依赖 Quarkus 容器。
 */
@DisplayName("FormulaCalculatorSamePageFieldRefTest — 同页签 field 引用逐行正确")
class FormulaCalculatorSamePageFieldRefTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * 字段定义：
     *  - inputX / inputY：BASIC_DATA 输入来源
     *  - colA：FORMULA，公式 = field(inputX)
     *  - colB：FORMULA，公式 = field(inputY)
     *  - 材料成本：FORMULA + isSubtotal，公式 = field(colA) + field(colB)  ← 核心
     */
    private static final String FIELDS = "["
        + "{\"name\":\"inputX\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.x\"},"
        + "{\"name\":\"inputY\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.y\"},"
        + "{\"name\":\"colA\",\"fieldType\":\"FORMULA\"},"
        + "{\"name\":\"colB\",\"fieldType\":\"FORMULA\"},"
        + "{\"name\":\"材料成本\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";

    /** colA = field(inputX)；colB = field(inputY)；材料成本 = field(colA) + field(colB) */
    private static final String FORMULAS = "["
        + "{\"name\":\"colA\",\"expression\":["
        +   "{\"type\":\"field\",\"value\":\"inputX\"}"
        + "]},"
        + "{\"name\":\"colB\",\"expression\":["
        +   "{\"type\":\"field\",\"value\":\"inputY\"}"
        + "]},"
        + "{\"name\":\"材料成本\",\"expression\":["
        +   "{\"type\":\"field\",\"value\":\"colA\"},"
        +   "{\"type\":\"operator\",\"value\":\"+\"},"
        +   "{\"type\":\"field\",\"value\":\"colB\"}"
        + "]}"
        + "]";

    /** rowKey 按行序：用 __seq_no__ 哨兵 → 下标对齐。 */
    private static final String RKF = "[\"__seq_no__\"]";

    /**
     * 两行 baseRows：
     *  行1：basicDataValues → {v.x}=5, {v.y}=2
     *  行2：basicDataValues → {v.x}=0, {v.y}=3
     */
    private static final String BASEROWS = "["
        + "{\"driverRow\":{},\"basicDataValues\":{\"{v.x}\":5,\"{v.y}\":2}},"
        + "{\"driverRow\":{},\"basicDataValues\":{\"{v.x}\":0,\"{v.y}\":3}}"
        + "]";

    // ======================================================================
    // T1：calculate() 逐行返回正确的 材料成本 值
    // ======================================================================

    @Test
    @DisplayName("T1: 逐行 field 引用 — 行1=7, 行2=3（不是整列总计 10）")
    void t1_perRowFieldRef_materialCostPerRow() {
        JsonNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS), j("[]"),
            Map.of(), Map.of(), Map.of()
        );

        assertEquals(2, result.size(), "应有 2 行结果");

        // 行1：colA=5, colB=2 → 材料成本=7
        JsonNode row0Values = result.get(0).path("values");
        assertEquals(5.0, row0Values.path("colA").asDouble(), 1e-9, "行1 colA 应=5");
        assertEquals(2.0, row0Values.path("colB").asDouble(), 1e-9, "行1 colB 应=2");
        assertEquals(7.0, row0Values.path("材料成本").asDouble(), 1e-9,
            "行1 材料成本 应=field(colA)+field(colB)=5+2=7，不是整列总计");

        // 行2：colA=0, colB=3 → 材料成本=3
        JsonNode row1Values = result.get(1).path("values");
        assertEquals(0.0, row1Values.path("colA").asDouble(), 1e-9, "行2 colA 应=0");
        assertEquals(3.0, row1Values.path("colB").asDouble(), 1e-9, "行2 colB 应=3");
        assertEquals(3.0, row1Values.path("材料成本").asDouble(), 1e-9,
            "行2 材料成本 应=field(colA)+field(colB)=0+3=3，不是整列总计");
    }

    // ======================================================================
    // T2：列小计 = Σ 行 = 10
    // ======================================================================

    @Test
    @DisplayName("T2: 列小计 = Σ行材料成本 = 7+3 = 10")
    void t2_subtotalColumnSum_is10() {
        Map<String, BigDecimal> byCol = calc.computeTabSubtotalsByColumn(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS), j("[]"), Map.of()
        );

        assertTrue(byCol.containsKey("材料成本"), "应包含 材料成本 列小计");
        assertEquals(0, byCol.get("材料成本").compareTo(new BigDecimal("10")),
            "列小计应=10(7+3)，实=" + byCol.get("材料成本"));
    }

    // ======================================================================
    // T3：各行 colA/colB 也被正确拓扑依赖（验证依赖链不只一跳）
    // ======================================================================

    @Test
    @DisplayName("T3: 拓扑两跳 — inputX→colA→材料成本 均在同行正确传递")
    void t3_topoTwoHops_colADerivedFromInputX() {
        JsonNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS), j("[]"),
            Map.of(), Map.of(), Map.of()
        );

        // 行1：inputX=5 → colA=5（第一跳）→ 材料成本含 colA=5（第二跳）
        JsonNode row0 = result.get(0).path("values");
        assertEquals(5.0, row0.path("colA").asDouble(), 1e-9,
            "行1 第一跳：colA=field(inputX)=5");
        assertEquals(7.0, row0.path("材料成本").asDouble(), 1e-9,
            "行1 第二跳：材料成本=colA+colB=5+2=7");

        // 行2：inputX=0 → colA=0（第一跳）→ 材料成本含 colA=0（第二跳）
        JsonNode row1 = result.get(1).path("values");
        assertEquals(0.0, row1.path("colA").asDouble(), 1e-9,
            "行2 第一跳：colA=field(inputX)=0");
        assertEquals(3.0, row1.path("材料成本").asDouble(), 1e-9,
            "行2 第二跳：材料成本=colA+colB=0+3=3");
    }
}
