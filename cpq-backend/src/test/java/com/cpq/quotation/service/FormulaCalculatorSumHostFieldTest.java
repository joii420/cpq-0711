package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803：`SUM(...)` 的 targetExpr 内引用<b>宿主页签字段</b>的取值与算序。
 *
 * <p><b>背景</b>：{@code b_field} 读 {@code ctx.currentRowRaw}，而 FORMULA 字段的计算结果
 * 只回填 {@code ctx.fieldValues}（{@code computeRows:884}），且
 * {@code fillInputDefaultSourceByFieldName:1861} 只补 INPUT_* 类型 —— 故 targetExpr 内
 * 引用本页签<b>公式列</b>恒取 0（静默少算），引用<b>输入列</b>却正常。修复 = 取值链补回落。
 *
 * <p><b>语义</b>：targetExpr 内的宿主字段是<b>逐行广播</b>——每个匹配行各取一次。
 * 这不是新语义：{@code b_field} 指向 INPUT 字段时本就如此（见 {@link #hostInputField_broadcastsPerRow}），
 * FORMULA 取 0 才是异类。
 *
 * <p>对应用例 T-01 ~ T-08（`test.md`）。
 */
class FormulaCalculatorSumHostFieldTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    // ── token 构造 ──────────────────────────────────────────────
    private static Map<String, Object> t(String type, String v) { return Map.of("type", type, "value", v); }
    private static Map<String, Object> op(String v) { return t("operator", v); }
    private static Map<String, Object> num(String v) { return t("number", v); }
    private static Map<String, Object> fld(String v) { return t("field", v); }
    private static Map<String, Object> bfld(String v) { return t("b_field", v); }

    /** 外层 SUM：遍历关联页签 MC 中「料件」与宿主相同的每一行 */
    private static Map<String, Object> sumMc(List<Map<String, Object>> targetExpr) {
        return Map.of("type", "cross_tab_ref", "agg", "SUM", "source", "MC",
            "match", List.of(Map.of("a", "料件", "b", "料件")), "targetExpr", targetExpr);
    }

    private static Map<String, Object> formulaField(String name, String formulaName) {
        return Map.of("name", name, "field_type", "FORMULA", "formula_name", formulaName);
    }

    private static Map<String, Object> formula(String name, List<Map<String, Object>> expr) {
        return Map.of("name", name, "expression", expr);
    }

    /** 关联页签 MC：同一料件 AgC3 两行，每行基数 = 5.0（组成含量 50 / 100 × 元素单价 10） */
    private static Map<String, List<Map<String, Object>>> mcTwoRows() {
        return Map.of("MC", List.of(
            Map.of("料件", "AgC3", "组成含量", 50, "元素单价", 10),
            Map.of("料件", "AgC3", "组成含量", 50, "元素单价", 10)));
    }

    /** 每行基数：组成含量/100 × 元素单价 = 5.0 */
    private static List<Map<String, Object>> baseExpr() {
        return new ArrayList<>(List.of(fld("组成含量"), op("/"), num("100"), op("*"), fld("元素单价")));
    }

    private ArrayNode run(List<Map<String, Object>> fields, List<Map<String, Object>> formulas,
                          Map<String, Object> driverRow) {
        ArrayNode baseRows = M.createArrayNode();
        baseRows.add(M.valueToTree(Map.of("driverRow", driverRow, "basicDataValues", Map.of())));
        return calc.calculate(M.valueToTree(fields), M.valueToTree(formulas), M.createObjectNode(),
            null, baseRows, M.createArrayNode(),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), mcTwoRows());
    }

    private static double val(ArrayNode out, String field) {
        return out.get(0).path("values").path(field).asDouble();
    }

    // ── T-01：核心修复 ──────────────────────────────────────────

    /** T-01：SUM 内引用宿主 FORMULA 字段 → 每行各取一次（2 行 → 5+5 + 5×2 = 20），修复前为 10。 */
    @Test void hostFormulaField_isResolvedAndBroadcastPerRow() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            List.of(formula("加工费取值", List.of(num("5"))),
                    formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3"));

        assertEquals(5.0, val(out, "加工费"), 1e-9, "宿主公式字段自身应算出 5");
        assertEquals(20.0, val(out, "结果"), 1e-9, "2 行各计入一次加工费：(5+5)+(5+5)=20");
    }

    /** T-02（回归锁）：宿主 INPUT 字段本就逐行广播，修复不得改变其结果。 */
    @Test void hostInputField_broadcastsPerRow() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    Map.of("name", "加工费", "field_type", "INPUT_NUMBER"),
                    formulaField("结果", "结果公式")),
            List.of(formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3", "加工费", 5));

        assertEquals(20.0, val(out, "结果"), 1e-9, "INPUT 字段的广播语义必须与改前逐位一致");
    }

    /** T-03：INPUT 字段被显式清空（""）→ 尊重置空取 0，不回落 fieldValues。 */
    @Test void explicitlyClearedInput_doesNotFallBack() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    Map.of("name", "加工费", "field_type", "INPUT_NUMBER"),
                    formulaField("结果", "结果公式")),
            List.of(formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3", "加工费", ""));

        assertEquals(10.0, val(out, "结果"), 1e-9, "显式置空 → 该项 0，结果仅为基数之和");
    }

    /** T-04：字段名在 currentRowRaw 与 fieldValues 中都不存在 → 取 0，不抛异常。 */
    @Test void unknownHostField_isZeroAndDoesNotThrow() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("查无此字段"));

        var out = assertDoesNotThrow(() -> run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("结果", "结果公式")),
            List.of(formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3")));

        assertEquals(10.0, val(out, "结果"), 1e-9);
    }

    /** T-05：被引用的宿主公式字段自身还依赖另一公式字段 → 取到的必须是最终值，不是中间态。 */
    @Test void transitiveFormulaDependency_resolvesToFinalValue() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("基础费", "基础费取值"),
                    formulaField("加工费", "加工费取值"),      // = 基础费 × 2
                    formulaField("结果", "结果公式")),
            List.of(formula("基础费取值", List.of(num("3"))),
                    formula("加工费取值", List.of(fld("基础费"), op("*"), num("2"))),
                    formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3"));

        assertEquals(6.0, val(out, "加工费"), 1e-9, "加工费 = 基础费 3 × 2");
        assertEquals(22.0, val(out, "结果"), 1e-9, "(5+6)+(5+6)=22，取的是加工费最终值而非 0/中间态");
    }

    /** T-06：被引用字段定义在引用方<b>之后</b> → 依赖排序必须生效，结果与调换定义顺序时一致。 */
    @Test void dependencyOrderIsIndependentOfFieldDeclarationOrder() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        List<Map<String, Object>> formulas = List.of(
            formula("加工费取值", List.of(num("5"))),
            formula("结果公式", List.of(sumMc(te))));

        // 「结果」声明在「加工费」之前
        var resultFirst = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("结果", "结果公式"),
                    formulaField("加工费", "加工费取值")),
            formulas, Map.of("料件", "AgC3"));

        // 「加工费」声明在「结果」之前
        var feeFirst = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            formulas, Map.of("料件", "AgC3"));

        assertEquals(20.0, val(resultFirst, "结果"), 1e-9, "声明顺序不得影响结果（拓扑序生效）");
        assertEquals(val(feeFirst, "结果"), val(resultFirst, "结果"), 1e-9);
    }

    // ── T-07 / T-08：依赖收集的边界 ────────────────────────────

    /** T-08：同一 targetExpr 内两处引用同一宿主字段 → 依赖边去重，Kahn 入度可归零，不得误报环。 */
    @Test void duplicateHostRefInSameTargetExpr_doesNotFalselyReportCycle() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));
        te.add(op("+")); te.add(bfld("加工费"));   // 同一字段第二次引用

        List<Map<String, Object>> fields = List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                             formulaField("加工费", "加工费取值"),
                             formulaField("结果", "结果公式"));
        var formulas = List.of(formula("加工费取值", List.of(num("5"))),
                               formula("结果公式", List.of(sumMc(te))));

        assertTrue(calc.cyclicFormulaNodes(M.valueToTree(fields), M.valueToTree(formulas)).isEmpty(),
            "重复引用同一字段不得被判成环（依赖边必须去重）");

        var out = run(fields, formulas, Map.of("料件", "AgC3"));
        assertEquals(30.0, val(out, "结果"), 1e-9, "(5+5+5)+(5+5+5)=30");
    }

    /** T-07：递归遇 KSUM 子 token（projectToHostKey=true）即止 —— 其 inner 的 field 不计入宿主依赖。 */
    @Test void recursionStopsAtKsumSubToken() {
        Map<String, Object> ksum = Map.of("type", "cross_tab_ref", "agg", "SUM", "source", "MC",
            "projectToHostKey", true,
            "match", List.of(Map.of("a", "料件", "b", "料件")),
            // inner 只含被聚合页签自己的列（白名单允许）；名字故意与宿主公式字段「加工费」同名
            "targetExpr", List.of(fld("加工费")));

        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(ksum);

        List<Map<String, Object>> fields = List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                             formulaField("加工费", "加工费取值"),
                             formulaField("结果", "结果公式"));
        var formulas = List.of(formula("加工费取值", List.of(num("5"))),
                               formula("结果公式", List.of(sumMc(te))));

        assertTrue(calc.cyclicFormulaNodes(M.valueToTree(fields), M.valueToTree(formulas)).isEmpty(),
            "KSUM inner 的同名 field 不应被当成对宿主字段的依赖");
        assertDoesNotThrow(() -> run(fields, formulas, Map.of("料件", "AgC3")));
    }

    /** 补充：环仍须被检出 —— A 的 targetExpr 引用 B，B 的公式引用 A。 */
    @Test void mutualRefThroughTargetExpr_isDetectedAsCycle() {
        List<Map<String, Object>> teA = baseExpr();
        teA.add(op("+")); teA.add(bfld("B"));

        List<Map<String, Object>> fields = List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                             formulaField("A", "A公式"), formulaField("B", "B公式"));
        var formulas = List.of(formula("A公式", List.of(sumMc(teA))),
                               formula("B公式", List.of(fld("A"), op("+"), num("1"))));

        var cyc = calc.cyclicFormulaNodes(M.valueToTree(fields), M.valueToTree(formulas));
        assertFalse(cyc.isEmpty(), "经 targetExpr 形成的环必须被检出");
        assertTrue(cyc.containsAll(List.of("A", "B")), "环成员应含 A、B：" + cyc);

        var desc = calc.describeFormulaCycles(M.valueToTree(fields), M.valueToTree(formulas));
        assertFalse(desc.isEmpty(), "须给出可定位的环路径描述");
        assertTrue(desc.get(0).contains("A") && desc.get(0).contains("B"), desc.get(0));
    }
}
