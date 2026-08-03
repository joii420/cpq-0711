package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * BL-0098：公式绑定改绑稳定 ID 的解析口径测试。
 *
 * <p>纯 JUnit（{@code new FormulaCalculator()}），不用 {@code @QuarkusTest} ——
 * BL-0095 导致测试库所有 Quarkus 测试当前起不来。
 */
class FormulaBindByIdTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 三条公式，都带 id。 */
    private JsonNode formulas() {
        return json("""
            [
              {"id":"id-A","name":"公式A","expression":[{"type":"number","value":"1"}]},
              {"id":"id-B","name":"公式B","expression":[{"type":"number","value":"2"}]},
              {"id":"id-C","name":"公式C","expression":[{"type":"number","value":"3"}]}
            ]
            """);
    }

    @Test
    @DisplayName("T1: resolveFormulaIdForField —— 显式 formula_name 绑定时返回该公式的 id")
    void explicitNameBinding_returnsItsId() {
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_name":"公式B"}]
            """);
        assertEquals("id-B",
            calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0));
    }

    @Test
    @DisplayName("T2: resolveFormulaIdForField —— 位置回退命中时返回该位置公式的 id")
    void positionalFallback_returnsItsId() {
        // 第 0 个 FORMULA 字段 → formulas[0] = 公式A
        JsonNode fields = json("""
            [{"name":"未绑定列","field_type":"FORMULA"}]
            """);
        assertEquals("id-A",
            calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0));
    }

    @Test
    @DisplayName("T3: resolveFormulaIdForField —— 公式没有 id 时返回 null（不编造）")
    void formulaWithoutId_returnsNull() {
        JsonNode noId = json("""
            [{"name":"公式A","expression":[{"type":"number","value":"1"}]}]
            """);
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_name":"公式A"}]
            """);
        assertNull(calc.resolveFormulaIdForField(fields.get(0), fields, noId, null, 0));
    }

    @Test
    @DisplayName("T4: resolveFormulaIdForField —— 解析不到任何公式时返回 null")
    void unresolvable_returnsNull() {
        JsonNode empty = json("[]");
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA"}]
            """);
        assertNull(calc.resolveFormulaIdForField(fields.get(0), fields, empty, null, 0));
    }
}
