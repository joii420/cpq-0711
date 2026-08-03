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

    @Test
    @DisplayName("T5: formula_id 优先级高于 formula_name —— 两者冲突时以 id 为准")
    void idBeatsName() {
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_id":"id-C","formula_name":"公式A"}]
            """);
        assertEquals("id-C",
            calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0));
    }

    @Test
    @DisplayName("T6: 驼峰 formulaId 同样识别（冻结结构/API 用驼峰）")
    void camelCaseFormulaIdRecognized() {
        JsonNode fields = json("""
            [{"name":"成本","fieldType":"FORMULA","formulaId":"id-B"}]
            """);
        assertEquals("id-B",
            calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0));
    }

    @Test
    @DisplayName("T7: 绑了 id 但公式已被删 → 返 null，绝不 fallback 到别的公式")
    void danglingId_returnsNull_noFallback() {
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_id":"id-已删除"}]
            """);
        assertNull(calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0));
    }

    @Test
    @DisplayName("T8: 改公式名不断链 —— 绑 id 的字段在公式改名后仍解析到同一条")
    void renameFormula_bindingSurvives() {
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_id":"id-B"}]
            """);
        JsonNode renamed = json("""
            [
              {"id":"id-A","name":"公式A","expression":[{"type":"number","value":"1"}]},
              {"id":"id-B","name":"改过名的公式B","expression":[{"type":"number","value":"2"}]},
              {"id":"id-C","name":"公式C","expression":[{"type":"number","value":"3"}]}
            ]
            """);
        assertEquals("id-B",
            calc.resolveFormulaIdForField(fields.get(0), fields, renamed, null, 0),
            "BL-0098 问题 B 根治判据：公式改名后绑定必须仍然有效");
    }

    @Test
    @DisplayName("T9: 调整公式顺序不改算法 —— 绑 id 的字段不受数组顺序影响")
    void reorderFormulas_bindingStable() {
        JsonNode fields = json("""
            [{"name":"成本","field_type":"FORMULA","formula_id":"id-A"}]
            """);
        JsonNode reordered = json("""
            [
              {"id":"id-C","name":"公式C","expression":[{"type":"number","value":"3"}]},
              {"id":"id-B","name":"公式B","expression":[{"type":"number","value":"2"}]},
              {"id":"id-A","name":"公式A","expression":[{"type":"number","value":"1"}]}
            ]
            """);
        assertEquals("id-A",
            calc.resolveFormulaIdForField(fields.get(0), fields, reordered, null, 0),
            "BL-0098 问题 A 根治判据：调序后绑定必须仍指向同一条公式");
    }

    @Test
    @DisplayName("T10: 无 formula_id 的老配置照走原 4 级回退（13 张老冻结单的兜底不能断）")
    void legacyWithoutId_stillFallsBack() {
        JsonNode fields = json("""
            [{"name":"未绑定列","fieldType":"FORMULA"}]
            """);
        assertEquals("id-A",
            calc.resolveFormulaIdForField(fields.get(0), fields, formulas(), null, 0),
            "D3：位置回退永久保留，不迁移的老单靠它兜底");
    }
}
