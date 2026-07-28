package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公式环检测：假阳性回归 + 环路径可定位性。
 *
 * <p>背景（2026-07-28，COMP-0112「物料成本」）：条件公式的多个分支引用同一个 FORMULA 字段时，
 * {@code buildFormulaDeps} 把并集依赖累加进 List 却不去重，而 Kahn 消解按 {@code contains()}
 * 每个前驱只减 1 → 入度永远归不了零 → 误报「循环引用: 物料成本」。
 */
class FormulaCycleDetectionTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private static Map<String, Object> fieldTok(String v) {
        return Map.of("type", "field", "value", v);
    }

    private static Map<String, Object> formulaField(String name, String formulaName) {
        return Map.of("name", name, "field_type", "FORMULA", "formula_name", formulaName);
    }

    private static Map<String, Object> condField(String name, List<Map<String, Object>> rules, String def) {
        return Map.of("name", name, "field_type", "FORMULA",
            "conditional_formula", Map.of("rules", rules, "default", def));
    }

    private static Map<String, Object> ruleOn(String col, String literal, String formula) {
        return Map.of(
            "when", Map.of("kind", "group", "logic", "and", "children", List.of(
                Map.of("kind", "leaf", "op", "eq", "left", col,
                       "rhs", Map.of("type", "literal", "value", literal)))),
            "formula", formula);
    }

    private static Map<String, Object> formula(String name, List<Map<String, Object>> expr) {
        return Map.of("name", name, "expression", expr);
    }

    private List<String> cyclic(List<Map<String, Object>> fields, List<Map<String, Object>> formulas) {
        return calc.cyclicFormulaNodes(M.valueToTree(fields), M.valueToTree(formulas));
    }

    private List<String> describe(List<Map<String, Object>> fields, List<Map<String, Object>> formulas) {
        return calc.describeFormulaCycles(M.valueToTree(fields), M.valueToTree(formulas));
    }

    /** COMP-0112 真实结构：条件两分支都引用 [来料包装费]，非银点分支另引用 [来料管理费]。 */
    private static List<Map<String, Object>> comp0112Fields() {
        return List.of(
            formulaField("来料管理费", "来料管理费取值公式"),
            formulaField("来料包装费", "来料包装费取值公式"),
            condField("物料成本",
                List.of(ruleOn("产出类型", "非银点类", "非银点类材料成本公式")),
                "银点材料成本公式"));
    }

    private static List<Map<String, Object>> comp0112Formulas() {
        return List.of(
            formula("来料管理费取值公式", List.of()),
            formula("来料包装费取值公式", List.of()),
            formula("银点材料成本公式", List.of(fieldTok("来料包装费"), fieldTok("组成数量"))),
            formula("非银点类材料成本公式",
                List.of(fieldTok("材料毛重"), fieldTok("来料管理费"), fieldTok("来料包装费"))));
    }

    // ---------------------------------------------------------------- 假阳性回归

    /** 用户实配场景：依赖图是 DAG，绝不能报环。修复前这里会得到 [物料成本]。 */
    @Test
    void condFormulaMultiBranchSharedDep_noFalseCycle() {
        assertEquals(List.of(), cyclic(comp0112Fields(), comp0112Formulas()), "DAG 被误判成环");
        assertEquals(List.of(), describe(comp0112Fields(), comp0112Formulas()));
    }

    /** 同一表达式内重复引用同一字段（A 出现三次）也不能成环。 */
    @Test
    void duplicateRefInSameExpr_noFalseCycle() {
        var fields = List.of(formulaField("甲", "f甲"), formulaField("乙", "f乙"));
        var formulas = List.of(
            formula("f甲", List.of()),
            formula("f乙", List.of(fieldTok("甲"), fieldTok("甲"), fieldTok("甲"))));
        assertEquals(List.of(), cyclic(fields, formulas));
    }

    /** rule 与 default 绑同一条公式，且该公式引用了别的公式字段。 */
    @Test
    void ruleAndDefaultSameFormula_noFalseCycle() {
        var fields = List.of(
            formulaField("基数", "f基数"),
            condField("合计", List.of(ruleOn("类型", "X", "f同")), "f同"));
        var formulas = List.of(formula("f基数", List.of()), formula("f同", List.of(fieldTok("基数"))));
        assertEquals(List.of(), cyclic(fields, formulas));
    }

    // ---------------------------------------------------------------- 真环仍须检出

    @Test
    void realSelfReference_stillDetected() {
        var fields = List.of(formulaField("甲", "f甲"));
        var formulas = List.of(formula("f甲", List.of(fieldTok("甲"))));
        assertEquals(List.of("甲"), cyclic(fields, formulas));
        assertEquals(1, describe(fields, formulas).size());
    }

    @Test
    void realMutualReference_stillDetected() {
        var fields = List.of(formulaField("甲", "f甲"), formulaField("乙", "f乙"));
        var formulas = List.of(
            formula("f甲", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))));
        assertTrue(cyclic(fields, formulas).containsAll(List.of("甲", "乙")));
    }

    /** 真环 + 无辜重复依赖并存：既不能漏报真环，也不能被重复依赖带偏。 */
    @Test
    void realCycleAlongsideDuplicateDeps_stillDetected() {
        var fields = List.of(
            formulaField("来料包装费", "来料包装费取值公式"),
            condField("物料成本", List.of(ruleOn("产出类型", "非银点类", "f规则")), "f默认"));
        var formulas = List.of(
            // 环：来料包装费 → 物料成本 → 来料包装费
            formula("来料包装费取值公式", List.of(fieldTok("物料成本"))),
            formula("f规则", List.of(fieldTok("来料包装费"))),
            formula("f默认", List.of(fieldTok("来料包装费"))));
        assertEquals(1, describe(fields, formulas).size());
    }

    // ---------------------------------------------------------------- 定位信息

    @Test
    void cycleDescription_locatesPlainFormula() {
        var fields = List.of(formulaField("甲", "f甲"), formulaField("乙", "f乙"));
        var formulas = List.of(
            formula("f甲", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))));
        var descs = describe(fields, formulas);
        assertEquals(1, descs.size(), "应恰好报 1 个环：" + descs);
        String d = descs.get(0);
        assertTrue(d.contains("甲") && d.contains("乙"), d);
        assertTrue(d.contains("f甲") && d.contains("f乙"), "缺公式名定位：" + d);
    }

    /** 条件公式成环须定位到具体规则分支（含序号），而不仅是字段名。 */
    @Test
    void cycleDescription_locatesConditionalRule() {
        var fields = List.of(
            formulaField("乙", "f乙"),
            condField("甲", List.of(ruleOn("类型", "X", "f甲规则")), "f甲默认"));
        var formulas = List.of(
            formula("f甲默认", List.of()),
            formula("f甲规则", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))));
        var descs = describe(fields, formulas);
        assertEquals(1, descs.size(), "应恰好报 1 个环：" + descs);
        String d = descs.get(0);
        assertTrue(d.contains("f甲规则"), "缺规则分支公式名：" + d);
        assertTrue(d.contains("条件规则1"), "缺规则序号定位：" + d);
        assertTrue(d.contains("f乙"), "缺对侧公式名：" + d);
    }

    /** 规则序号须为原始下标：前面的规则绑了不存在的公式被跳过时不能错位。 */
    @Test
    void cycleDescription_ruleIndexIsOriginalPosition() {
        var fields = List.of(
            formulaField("乙", "f乙"),
            condField("甲", List.of(
                ruleOn("类型", "A", "不存在的公式"),     // 解析不到 → 跳过，但占用序号 1
                ruleOn("类型", "B", "f甲规则2")), "f甲默认"));
        var formulas = List.of(
            formula("f甲默认", List.of()),
            formula("f甲规则2", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))));
        String d = describe(fields, formulas).get(0);
        assertTrue(d.contains("条件规则2"), "规则序号错位（应为原始下标 2）：" + d);
    }

    /** when 判断条件引用列成环时，须指出是判断条件而非公式表达式。 */
    @Test
    void cycleDescription_locatesWhenCondition() {
        var fields = List.of(
            formulaField("乙", "f乙"),
            condField("甲", List.of(ruleOn("乙", "X", "f甲规则")), "f甲规则"));
        var formulas = List.of(formula("f甲规则", List.of()), formula("f乙", List.of(fieldTok("甲"))));
        var descs = describe(fields, formulas);
        assertEquals(1, descs.size(), "应恰好报 1 个环：" + descs);
        assertTrue(descs.get(0).contains("判断条件"), "缺判断条件定位：" + descs.get(0));
    }

    @Test
    void multipleDisjointCycles_reportedSeparately() {
        var fields = List.of(
            formulaField("甲", "f甲"), formulaField("乙", "f乙"),
            formulaField("丙", "f丙"), formulaField("丁", "f丁"));
        var formulas = List.of(
            formula("f甲", List.of(fieldTok("乙"))), formula("f乙", List.of(fieldTok("甲"))),
            formula("f丙", List.of(fieldTok("丁"))), formula("f丁", List.of(fieldTok("丙"))));
        assertEquals(2, describe(fields, formulas).size(), "两个独立环应各报一条");
    }

    /** 只是依赖了环的下游节点不在环上，不应被点名。 */
    @Test
    void innocentDownstream_notReportedAsCycleMember() {
        var fields = List.of(
            formulaField("甲", "f甲"), formulaField("乙", "f乙"), formulaField("下游", "f下游"));
        var formulas = List.of(
            formula("f甲", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))),
            formula("f下游", List.of(fieldTok("甲"))));
        var descs = describe(fields, formulas);
        assertEquals(1, descs.size(), descs.toString());
        assertFalse(descs.get(0).contains("下游"), "无辜下游节点被点名：" + descs.get(0));
    }

    /** 人工核对用：打印一条贴近真实配置的报错文案。 */
    @Test
    void printRealisticMessage() {
        var fields = List.of(
            formulaField("来料管理费", "来料管理费取值公式"),
            condField("物料成本",
                List.of(ruleOn("产出类型", "非银点类", "非银点类材料成本公式")),
                "银点材料成本公式"));
        var formulas = List.of(
            formula("来料管理费取值公式", List.of(fieldTok("物料成本"))),
            formula("银点材料成本公式", List.of()),
            formula("非银点类材料成本公式", List.of(fieldTok("来料管理费"))));
        System.out.println("---- 循环引用报错文案预览 ----\n  1. " + describe(fields, formulas).get(0));
    }
}
