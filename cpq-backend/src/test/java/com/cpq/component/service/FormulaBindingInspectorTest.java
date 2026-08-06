package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * task-0805 · 测试用例.md §5.1/§4.2/§5.9 —— {@link FormulaBindingInspector} 纯单测（U-B2-01~10）。
 *
 * <p>覆盖 §2.1 status 判定表全部四种取值（BOUND / RESOLVED_BY_NAME×2 / RESOLVED_BY_POSITION /
 * UNRESOLVABLE×2）、条件公式内部引用逐条出报告、SUBTOTAL 组件范围外边界、只读铁律、以及
 * 2026-08-05 追加的「conditional_formula 只有 default 无 rules」假阻断回归用例（U-B2-10）。
 *
 * <p>无 {@code @QuarkusTest}，不连库，与 {@code FormulaIdBinderTest} 同款纯 JUnit 风格。
 */
class FormulaBindingInspectorTest {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── U-B2-01：只读铁律 —— 深拷贝入参，原 fields/formulas 内容不变 ──────────────

    @Test
    @DisplayName("U-B2-01: inspect 只读 —— 调用前后 fields/formulas 的 JSON 文本逐字节不变")
    void inspect_isReadOnly_doesNotMutateInputNodes() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA"}]""");
        JsonNode formulas = json("""
            [{"name":"公式A","expression":[]}]""");   // 故意不带 id —— 若 inspect 原地补 id 就会改变这里

        String fieldsBefore = fields.toString();
        String formulasBefore = formulas.toString();

        FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        assertEquals(fieldsBefore, fields.toString(), "inspect 不得原地修改调用方 fields");
        assertEquals(formulasBefore, formulas.toString(),
            "inspect 不得原地修改调用方 formulas（尤其是 ensureFormulaIds 补 id 这一步必须只作用于深拷贝）");
    }

    // ── U-B2-02~07：status 判定表全覆盖 ────────────────────────────────────────

    @Test
    @DisplayName("U-B2-02: BOUND —— 显式 formula_id 指向存在的公式")
    void status_bound() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA","formula_id":"id-A"}]""");
        JsonNode formulas = json("""
            [{"id":"id-A","name":"公式A","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        assertEquals(1, r.items.size());
        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("BOUND", item.status);
        assertEquals("id-A", item.resolvedFormulaId);
        assertEquals("公式A", item.resolvedFormulaName);
        assertNull(item.message);
        assertEquals(0, r.unboundCount);
    }

    @Test
    @DisplayName("U-B2-03: UNRESOLVABLE —— 绑了 formula_id 但查不到（公式已被删）")
    void status_unresolvable_danglingId() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA","formula_id":"id-已删除"}]""");
        JsonNode formulas = json("""
            [{"id":"id-A","name":"公式A","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("UNRESOLVABLE", item.status);
        assertNull(item.resolvedFormulaId);
        assertEquals("绑定的公式已不存在（id=id-已删除）", item.message);
        assertEquals(1, r.unboundCount);
    }

    @Test
    @DisplayName("U-B2-04: RESOLVED_BY_NAME —— 显式 formula_name")
    void status_resolvedByName_explicit() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA","formula_name":"公式B"}]""");
        JsonNode formulas = json("""
            [{"name":"公式A","expression":[]},{"name":"公式B","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("RESOLVED_BY_NAME", item.status);
        assertEquals("公式B", item.resolvedFormulaName);
        assertNotNull(item.resolvedFormulaId, "ensureFormulaIds 应先补 id，命中后 id 非空");
    }

    @Test
    @DisplayName("U-B2-05: RESOLVED_BY_NAME —— 字段名==公式名，未显式绑定")
    void status_resolvedByName_fieldNameMatch() {
        JsonNode fields = json("""
            [{"name":"回收成本","field_type":"FORMULA"}]""");
        JsonNode formulas = json("""
            [{"name":"其它","expression":[]},{"name":"回收成本","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("RESOLVED_BY_NAME", item.status);
        assertEquals("回收成本", item.resolvedFormulaName);
    }

    @Test
    @DisplayName("U-B2-06: RESOLVED_BY_POSITION —— 无名字匹配，按位置命中")
    void status_resolvedByPosition() {
        JsonNode fields = json("""
            [{"name":"列0","field_type":"FORMULA"},{"name":"列1","field_type":"FORMULA"}]""");
        JsonNode formulas = json("""
            [{"name":"公式A","expression":[]},{"name":"公式B","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        assertEquals(2, r.items.size());
        assertEquals("RESOLVED_BY_POSITION", r.items.get(0).status);
        assertEquals("公式A", r.items.get(0).resolvedFormulaName);
        assertEquals("RESOLVED_BY_POSITION", r.items.get(1).status);
        assertEquals("公式B", r.items.get(1).resolvedFormulaName);
    }

    @Test
    @DisplayName("U-B2-07: UNRESOLVABLE —— 彻底解析不到（COMP-0049 历史形态：formulas=[]）")
    void status_unresolvable_fallthrough() {
        JsonNode fields = json("""
            [{"name":"公式测试","field_type":"FORMULA"}]""");
        JsonNode formulas = json("[]");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("UNRESOLVABLE", item.status);
        assertEquals("未绑定公式，且无法按名称/位置推导", item.message);
    }

    // ── U-B2-08：条件公式内部引用逐条出报告 ─────────────────────────────────────

    @Test
    @DisplayName("U-B2-08: 条件公式 rules/default 各出一条 item")
    void conditionalFormula_eachRuleAndDefault_producesOneItem() {
        JsonNode fields = json("""
            [{"name":"材料成本","field_type":"FORMULA","conditional_formula":{
                "rules":[{"when":{"kind":"group","logic":"and","children":[]},"formula":"非银点类材料成本公式"}],
                "default":"银点材料成本公式"}}]""");
        JsonNode formulas = json("""
            [{"name":"非银点类材料成本公式","expression":[]},{"name":"银点材料成本公式","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-0032", "物料", fields, formulas);

        assertEquals(2, r.items.size());
        FormulaBindingInspector.Item rule1 = r.items.get(0);
        assertEquals("材料成本 › 规则1", rule1.fieldName);
        assertEquals("RESOLVED_BY_NAME", rule1.status);
        assertEquals("非银点类材料成本公式", rule1.resolvedFormulaName);

        FormulaBindingInspector.Item def = r.items.get(1);
        assertEquals("材料成本 › 默认", def.fieldName);
        assertEquals("RESOLVED_BY_NAME", def.status);
        assertEquals("银点材料成本公式", def.resolvedFormulaName);
    }

    // ── U-B2-09：SUBTOTAL 组件（fields=[] 但 formulas 非空）—— 范围外边界 ─────────

    @Test
    @DisplayName("U-B2-09: SUBTOTAL 组件 fields=[] —— 报告为空，不报错（§7 Q5 已裁决为范围外）")
    void subtotalComponent_emptyFields_producesEmptyReport() {
        JsonNode fields = json("[]");
        JsonNode formulas = json("""
            [{"name":"公式1","expression":[{"type":"component_subtotal","component_code":"X","tab_name":"Y"}]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-0031", "报价", fields, formulas);

        assertTrue(r.items.isEmpty());
        assertEquals(0, r.unboundCount);
        assertEquals(0, r.totalFormulaRefs);
    }

    // ── U-B2-10（2026-08-05 追加）：条件公式只有 default、没有 rules —— 不得误判 UNRESOLVABLE ──

    @Test
    @DisplayName("U-B2-10a: conditional_formula 只有 default（无 rules 键）—— 只出默认分支一条，非 UNRESOLVABLE")
    void conditionalFormula_defaultOnly_noRulesKey_notUnresolvable() {
        JsonNode fields = json("""
            [{"name":"综合费率","field_type":"FORMULA","conditional_formula":{"default":"标准费率"}}]""");
        JsonNode formulas = json("""
            [{"name":"标准费率","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        assertEquals(1, r.items.size(), "rules 缺失时不应出任何「规则N」条目，只有默认分支这一条");
        FormulaBindingInspector.Item item = r.items.get(0);
        assertEquals("综合费率 › 默认", item.fieldName);
        assertEquals("RESOLVED_BY_NAME", item.status, "不得因 rules 缺失被误判 UNRESOLVABLE（假阻断回归）");
        assertEquals("标准费率", item.resolvedFormulaName);
        assertEquals(0, r.unboundCount);
    }

    @Test
    @DisplayName("U-B2-10b: conditional_formula 的 rules=[]（空数组）—— 同上，非 UNRESOLVABLE")
    void conditionalFormula_defaultOnly_emptyRulesArray_notUnresolvable() {
        JsonNode fields = json("""
            [{"name":"综合费率","field_type":"FORMULA","conditional_formula":{"rules":[],"default":"标准费率"}}]""");
        JsonNode formulas = json("""
            [{"name":"标准费率","expression":[]}]""");

        FormulaBindingInspector.Report r = FormulaBindingInspector.inspect("COMP-T", "测试组件", fields, formulas);

        assertEquals(1, r.items.size());
        assertEquals("RESOLVED_BY_NAME", r.items.get(0).status);
        assertEquals(0, r.unboundCount);
    }

    // ── merge()：跨组件汇总 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("merge: 汇总多组件报告，unboundCount/totalFormulaRefs 按合并后重算")
    void merge_aggregatesAcrossComponents() {
        JsonNode fieldsA = json("""
            [{"name":"甲","field_type":"FORMULA","formula_id":"id-A"}]""");
        JsonNode formulasA = json("""
            [{"id":"id-A","name":"公式A","expression":[]}]""");
        JsonNode fieldsB = json("""
            [{"name":"乙","field_type":"FORMULA"}]""");
        JsonNode formulasB = json("[]");

        FormulaBindingInspector.Report ra = FormulaBindingInspector.inspect("COMP-A", "A", fieldsA, formulasA);
        FormulaBindingInspector.Report rb = FormulaBindingInspector.inspect("COMP-B", "B", fieldsB, formulasB);
        FormulaBindingInspector.Report merged =
            FormulaBindingInspector.merge(java.util.List.of(ra, rb));

        assertEquals(2, merged.totalFormulaRefs);
        assertEquals(1, merged.unboundCount);
        assertFalse(merged.items.isEmpty());
    }
}
