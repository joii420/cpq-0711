package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * task-0805 · 测试用例.md §5.3 —— {@link FormulaIdBinder#listUnboundFormulaFields} 纯单测（U-FIB-01/02）。
 *
 * <p>§7 Q1 已裁决：判定口径是「固化后视角」——调用前先跑一遍 {@code bindFormulaIdsToFields}，
 * 之后 {@code listUnboundFormulaFields} 报告的才是真正的 {@code UNRESOLVABLE}（能靠名称/位置
 * 解析的字段已经在固化这一步拿到了 {@code formula_id}，不会被误报进清单）。
 *
 * <p>不改动既有 {@code FormulaIdBinderTest.java}（18 个用例），本文件只加新方法的用例，
 * 复用其同款「不带 CDI，纯 JUnit」风格。
 */
class FormulaIdBinderListUnboundTest {

    private Map<String, Object> formula(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        m.put("name", name);
        m.put("expression", List.of(Map.of("type", "number", "value", "1")));
        return m;
    }

    private Map<String, Object> formulaField(String name, String boundId, String boundName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("field_type", "FORMULA");
        if (boundId != null) m.put("formula_id", boundId);
        if (boundName != null) m.put("formula_name", boundName);
        return m;
    }

    private Map<String, Object> conditionalField(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("field_type", "FORMULA");
        m.put("conditional_formula", Map.of(
            "rules", List.of(Map.of("when", Map.of(), "formula", "任意公式")),
            "default", "任意默认"));
        return m;
    }

    @Test
    @DisplayName("U-FIB-01: 固化后视角 —— 字段A(已绑)/B(未绑可按名解析)/C(未绑不可解析)/D(条件公式豁免) → 恰好返回 [\"字段C\"]")
    void listUnboundFormulaFields_afterBinding_returnsOnlyTrulyUnresolvable() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"),
            formula("id-B", "字段B")   // 与「字段B」同名，供按字段名解析
        ));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("字段A", "id-A", null),   // 已绑
            formulaField("字段B", null, null),      // 未绑，但字段名==公式名，可解析
            formulaField("字段C", null, null),      // 未绑，彻底无法解析
            conditionalField("字段D")                 // 条件公式，豁免
        ));

        // 固化后视角：先跑一遍 bindFormulaIdsToFields（与 commit()/consolidate 的既有调用顺序一致）
        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        List<String> unbound = FormulaIdBinder.listUnboundFormulaFields(fields);

        assertEquals(List.of("字段C"), unbound,
            "固化后仅「字段C」真正未绑定；字段B 应已被 bindFormulaIdsToFields 按名固化成 formula_id");
    }

    @Test
    @DisplayName("U-FIB-02: listUnboundFormulaFields 与 validateExplicitBinding 复用同一份清单（同一口径）")
    void listUnboundFormulaFields_sameSourceAs_validateExplicitBinding() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(formula("id-A", "公式A")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("字段A", "id-A", null),
            formulaField("字段C", null, null),
            conditionalField("字段D")));

        // 不先固化，直接对拍两个方法在同一输入下点名的字段集合是否一致
        List<String> listed = FormulaIdBinder.listUnboundFormulaFields(fields);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FormulaIdBinder.validateExplicitBinding(fields));

        for (String name : listed) {
            assertTrue(ex.getMessage().contains(name),
                "validateExplicitBinding 报错文案必须点名 listUnboundFormulaFields 返回的每个字段: " + name);
        }
        assertEquals(1, listed.size());
        assertEquals("字段C", listed.get(0));
        assertFalseContainsBoundOrConditional(ex.getMessage());
    }

    private void assertFalseContainsBoundOrConditional(String message) {
        assertTrue(!message.contains("字段A") && !message.contains("字段D"),
            "已绑定字段与条件公式豁免字段不应出现在报错文案里: " + message);
    }
}
