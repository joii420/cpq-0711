package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BL-0098：公式 id 补齐 / 绑定固化 / 显式绑定校验的纯 JUnit 测试。 */
class FormulaIdBinderTest {

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

    @Test
    @DisplayName("T1: ensureFormulaIds —— 缺 id 的补上 UUID，已有 id 的原样不动")
    void ensureFormulaIds_fillsMissingKeepsExisting() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-keep", "公式A"),
            formula(null, "公式B")
        ));
        FormulaIdBinder.ensureFormulaIds(formulas);

        assertEquals("id-keep", formulas.get(0).get("id"), "已有 id 必须原样保留");
        Object generated = formulas.get(1).get("id");
        assertNotNull(generated);
        assertFalse(String.valueOf(generated).isBlank());
        assertEquals(36, String.valueOf(generated).length(), "生成的应是标准 UUID 字符串");
    }

    @Test
    @DisplayName("T2: ensureFormulaIds —— 空串 id 视为缺失，会被补上")
    void ensureFormulaIds_blankTreatedAsMissing() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(formula("  ", "公式A")));
        FormulaIdBinder.ensureFormulaIds(formulas);
        assertFalse(String.valueOf(formulas.get(0).get("id")).isBlank());
    }

    @Test
    @DisplayName("T3: bindFormulaIdsToFields —— 显式 formula_name 的字段固化成对应 id")
    void bind_explicitNameConsolidatedToId() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("成本", null, "公式B")));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertEquals("id-B", fields.get(0).get("formula_id"));
        assertEquals("公式B", fields.get(0).get("formula_name"),
            "formula_name 保留作展示冗余，不删");
    }

    @Test
    @DisplayName("T4: bindFormulaIdsToFields —— 隐式（按位置）绑定也固化，且固化的就是当前实际生效的那条")
    void bind_positionalConsolidated() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B"), formula("id-C", "公式C")));
        // 三个 FORMULA 字段都没绑 → 按位置分别命中 formulas[0]/[1]/[2]
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("列0", null, null),
            formulaField("列1", null, null),
            formulaField("列2", null, null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertEquals("id-A", fields.get(0).get("formula_id"));
        assertEquals("id-B", fields.get(1).get("formula_id"));
        assertEquals("id-C", fields.get(2).get("formula_id"));
    }

    @Test
    @DisplayName("T5: bindFormulaIdsToFields —— 已有 formula_id 的字段不被覆盖")
    void bind_existingIdNotOverwritten() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("成本", "id-B", null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertEquals("id-B", fields.get(0).get("formula_id"));
    }

    @Test
    @DisplayName("T6: bindFormulaIdsToFields —— 解析不到任何公式时不写 formula_id（COMP-0049 场景）")
    void bind_unresolvableLeavesUnbound() {
        List<Map<String, Object>> formulas = new ArrayList<>();   // 组件一条公式都没有
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("公式测试", null, null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertNull(fields.get(0).get("formula_id"), "解析不到就不写，绝不编造");
    }

    @Test
    @DisplayName("T7: bindFormulaIdsToFields —— 非 FORMULA 字段一概不碰")
    void bind_nonFormulaFieldUntouched() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(formula("id-A", "公式A")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "料号");
        input.put("field_type", "INPUT_TEXT");
        List<Map<String, Object>> fields = new ArrayList<>(List.of(input));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertFalse(fields.get(0).containsKey("formula_id"));
    }

    @Test
    @DisplayName("T8: validateExplicitBinding —— 固化后仍无绑定的 FORMULA 字段报错并点名")
    void validate_unboundFieldRejected() {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("公式测试", null, null)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FormulaIdBinder.validateExplicitBinding(fields));

        assertTrue(ex.getMessage().contains("公式测试"), "错误信息必须点名是哪个字段");
    }

    @Test
    @DisplayName("T9: validateExplicitBinding —— 全部绑定则放行")
    void validate_allBoundPasses() {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            formulaField("成本", "id-A", null)));
        FormulaIdBinder.validateExplicitBinding(fields);   // 不抛异常即通过
    }

    @Test
    @DisplayName("T10: validateExplicitBinding —— 条件公式字段豁免（它不走 formula_name/id 绑定）")
    void validate_conditionalFormulaExempt() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", "条件成本");
        f.put("field_type", "FORMULA");
        f.put("conditional_formula", Map.of(
            "rules", List.of(Map.of("when", Map.of(), "formula", "公式A")),
            "default", "公式B"));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(f));

        FormulaIdBinder.validateExplicitBinding(fields);   // 不抛异常即通过
    }

    // ─── 条件公式的引用也固化成 id（BL-0098 补充范围，2026-08-03 用户裁决）───────

    private Map<String, Object> condField(String name, String ruleFormula, String ruleId,
                                          String defFormula, String defId) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("when", Map.of("kind", "group", "logic", "and", "children", List.of()));
        rule.put("formula", ruleFormula);
        if (ruleId != null) rule.put("formula_id", ruleId);

        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("rules", new ArrayList<>(List.of(rule)));
        cf.put("default", defFormula);
        if (defId != null) cf.put("default_formula_id", defId);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("field_type", "FORMULA");
        f.put("conditional_formula", cf);
        return f;
    }

    @SuppressWarnings("unchecked")
    private String ruleIdOf(Map<String, Object> field) {
        Map<String, Object> cf = (Map<String, Object>) field.get("conditional_formula");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) cf.get("rules");
        Object v = rules.get(0).get("formula_id");
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private String defaultIdOf(Map<String, Object> field) {
        Map<String, Object> cf = (Map<String, Object>) field.get("conditional_formula");
        Object v = cf.get("default_formula_id");
        return v == null ? null : String.valueOf(v);
    }

    @Test
    @DisplayName("T11: 条件公式的规则与默认分支都按名字固化成 id")
    void bind_conditionalRefsConsolidated() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B"), formula("id-C", "公式C")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            condField("材料成本", "公式B", null, "公式C", null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertEquals("id-B", ruleIdOf(fields.get(0)), "规则分支应固化成 id-B");
        assertEquals("id-C", defaultIdOf(fields.get(0)), "默认分支应固化成 id-C");
    }

    @Test
    @DisplayName("T12: 条件公式已有的 id 不被覆盖")
    void bind_conditionalExistingIdKept() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B"), formula("id-C", "公式C")));
        // 名字写"公式B"但 id 已绑 id-A → id 是权威，不能被名字覆盖回 id-B
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            condField("材料成本", "公式B", "id-A", "公式C", "id-A")));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertEquals("id-A", ruleIdOf(fields.get(0)));
        assertEquals("id-A", defaultIdOf(fields.get(0)));
    }

    @Test
    @DisplayName("T13: 条件公式引用的名字解析不到 → 不写 id（不编造）")
    void bind_conditionalUnresolvableLeavesUnbound() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(formula("id-A", "公式A")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            condField("材料成本", "并不存在的公式", null, "也不存在", null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertNull(ruleIdOf(fields.get(0)));
        assertNull(defaultIdOf(fields.get(0)));
    }

    @Test
    @DisplayName("T14: 条件公式字段不会被误写字段级 formula_id（那是普通字段才有的）")
    void bind_conditionalFieldGetsNoFieldLevelId() {
        List<Map<String, Object>> formulas = new ArrayList<>(List.of(
            formula("id-A", "公式A"), formula("id-B", "公式B"), formula("id-C", "公式C")));
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
            condField("材料成本", "公式B", null, "公式C", null)));

        FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

        assertNull(fields.get(0).get("formula_id"),
            "条件公式字段走 rules/default，不该有字段级 formula_id");
    }
}
