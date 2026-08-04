package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803：SUM 内引用宿主字段的边界与异常场景。对应 {@code test.md} T-34 / T-35 / T-36。
 *
 * <p>与 {@link FormulaCalculatorSumHostFieldTest}（T-01~T-08 核心取值/依赖）互补——
 * 复用同一套 token/夹具构造 helper 风格，但聚焦「关联页签行数为 0/1」与「宿主公式字段
 * 求值抛异常」两类边界。T-37（引用不存在字段名）已被
 * {@link FormulaCalculatorSumHostFieldTest#unknownHostField_isZeroAndDoesNotThrow} 覆盖，
 * 本文件不重复。
 */
class FormulaCalculatorSumHostFieldEdgeCasesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private static Map<String, Object> t(String type, String v) { return Map.of("type", type, "value", v); }
    private static Map<String, Object> op(String v) { return t("operator", v); }
    private static Map<String, Object> num(String v) { return t("number", v); }
    private static Map<String, Object> fld(String v) { return t("field", v); }
    private static Map<String, Object> bfld(String v) { return t("b_field", v); }

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

    private static List<Map<String, Object>> baseExpr() {
        return new ArrayList<>(List.of(fld("组成含量"), op("/"), num("100"), op("*"), fld("元素单价")));
    }

    private ArrayNode run(List<Map<String, Object>> fields, List<Map<String, Object>> formulas,
                          Map<String, Object> driverRow, Map<String, List<Map<String, Object>>> mcRows) {
        ArrayNode baseRows = M.createArrayNode();
        baseRows.add(M.valueToTree(Map.of("driverRow", driverRow, "basicDataValues", Map.of())));
        return calc.calculate(M.valueToTree(fields), M.valueToTree(formulas), M.createObjectNode(),
            null, baseRows, M.createArrayNode(),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), mcRows);
    }

    private static double val(ArrayNode out, String field) {
        return out.get(0).path("values").path(field).asDouble();
    }

    // ── T-34：关联页签匹配 0 行 ──────────────────────────────────

    /** T-34：MC 无任何行命中 → SUM 返 0；宿主 FORMULA 字段 D 不参与（不出现 0×D 之类误算）。 */
    @Test void zeroMatchingRows_sumIsZero_hostFieldNotBroadcast() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            List.of(formula("加工费取值", List.of(num("5"))),
                    formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3"),
            Map.of("MC", List.of()));   // 0 行：料件字段存在但值域为空表

        assertEquals(5.0, val(out, "加工费"), 1e-9, "宿主公式字段本身照常计算，不受 SUM 匹配行数影响");
        assertEquals(0.0, val(out, "结果"), 1e-9, "0 行命中 → SUM 恒 0，D 不被凭空广播出任何值");
    }

    /** T-34 变体：MC 有行但「料件」值不匹配（真正的 0 命中，非空表）。 */
    @Test void zeroMatchingRows_dueToKeyMismatch_sumIsZero() {
        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("加工费"));

        var out = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            List.of(formula("加工费取值", List.of(num("5"))),
                    formula("结果公式", List.of(sumMc(te)))),
            Map.of("料件", "AgC3"),
            Map.of("MC", List.of(Map.of("料件", "完全不同的料件", "组成含量", 50, "元素单价", 10))));

        assertEquals(0.0, val(out, "结果"), 1e-9, "key 不匹配等价 0 命中 → SUM 恒 0");
    }

    // ── T-35：关联页签匹配 1 行 ──────────────────────────────────

    /**
     * T-35：N=1 时，「D 写在 SUM 内」与「D 写在 SUM 外」结果应相等
     * （§5.6 D-6：SUM(a+D) = a+D = SUM(a)+D 当且仅当 N=1；N&gt;1 时才分叉，
     * 这正是本次修复要求「业务意图决定写法」的数学基础，T-01/T-08 已验证 N=2 时分叉）。
     */
    @Test void oneMatchingRow_insideVsOutsideSum_areEqual() {
        Map<String, List<Map<String, Object>>> mcOneRow =
            Map.of("MC", List.of(Map.of("料件", "AgC3", "组成含量", 50, "元素单价", 10)));

        // 写法 1：D 在 SUM 内
        List<Map<String, Object>> teInside = baseExpr();
        teInside.add(op("+")); teInside.add(bfld("加工费"));
        var inside = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            List.of(formula("加工费取值", List.of(num("5"))),
                    formula("结果公式", List.of(sumMc(teInside)))),
            Map.of("料件", "AgC3"), mcOneRow);

        // 写法 2：D 在 SUM 外
        List<Map<String, Object>> teOutside = baseExpr();
        List<Map<String, Object>> exprOutside = new ArrayList<>();
        exprOutside.add(sumMc(teOutside));
        exprOutside.add(op("+"));
        exprOutside.add(bfld("加工费"));
        var outside = run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("加工费", "加工费取值"),
                    formulaField("结果", "结果公式")),
            List.of(formula("加工费取值", List.of(num("5"))),
                    formula("结果公式", exprOutside)),
            Map.of("料件", "AgC3"), mcOneRow);

        assertEquals(10.0, val(inside, "结果"), 1e-9, "N=1：(5+5)=10");
        assertEquals(val(outside, "结果"), val(inside, "结果"), 1e-9,
            "N=1 时两种写法必须等价（写在 SUM 内/外结果相同）");
    }

    // ── T-36：被引用宿主字段求值抛异常 ──────────────────────────

    /**
     * T-36：宿主 FORMULA 字段 D 自身的表达式非法（此处用孤立 operator token 构造无法解析的
     * 算式），触发 {@link FormulaCalculator#evaluateExpression} 内部 {@code catch(Exception)}
     * → D 塌成 0，<b>不抛出</b>、不中止整卡计算；引用 D 的 SUM 公式照常算完（把 D 当 0 参与）。
     */
    @Test void hostFieldEvalThrows_collapsesToZero_doesNotAbortWholeCard() {
        // D 的公式："+"（孤立算符，无操作数）→ ArithParser 解析必抛异常。
        List<Map<String, Object>> brokenExpr = List.of(op("+"));

        List<Map<String, Object>> te = baseExpr();
        te.add(op("+")); te.add(bfld("D"));

        var out = assertDoesNotThrow(() -> run(
            List.of(Map.of("name", "料件", "field_type", "INPUT_TEXT"),
                    formulaField("D", "D取值"),
                    formulaField("结果", "结果公式"),
                    formulaField("无关字段", "无关字段取值")),
            List.of(formula("D取值", brokenExpr),
                    formula("结果公式", List.of(sumMc(te))),
                    formula("无关字段取值", List.of(num("42")))),
            Map.of("料件", "AgC3"),
            Map.of("MC", List.of(
                Map.of("料件", "AgC3", "组成含量", 50, "元素单价", 10),
                Map.of("料件", "AgC3", "组成含量", 50, "元素单价", 10)))));

        assertEquals(0.0, val(out, "D"), 1e-9, "D 自身求值异常应塌 0，而非抛出");
        assertEquals(10.0, val(out, "结果"), 1e-9,
            "SUM 应正常算完，把塌 0 的 D 当 0 参与：(5+0)+(5+0)=10");
        assertEquals(42.0, val(out, "无关字段"), 1e-9,
            "同卡片内无关字段不受 D 异常影响，证明「不传播致整卡失败」");
    }

    /** T-36 补充：直接对 evaluateExpression 单元验证，隔离出「异常→0」这一最小事实。 */
    @Test void evaluateExpression_malformedTokens_returnsZero_notException() {
        var ctx = new FormulaCalculator.RowContext();
        var tokens = M.valueToTree(List.of(op("+"), op("*")));   // 双孤立算符，必不可解析
        var result = assertDoesNotThrow(() -> calc.evaluateExpression(tokens, ctx));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result), "解析异常应返回 0（数值比较，不比 scale）");
    }
}
