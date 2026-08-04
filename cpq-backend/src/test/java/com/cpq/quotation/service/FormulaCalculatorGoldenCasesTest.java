package com.cpq.quotation.service;

import com.cpq.common.NumberFormatUtil;
import com.cpq.common.PrecisionPolicy;
import com.cpq.quotation.service.FormulaCalculator.RowContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0801 公式计算精度优化 — B8 T1（api.md §5.2 黄金用例 G-1~G-14）+ T2（求值点 #7 —
 * {@code FormulaCalculator.ArithParser}）+ T3/T4（链路二基线：6 层嵌套 / 亿级金额精度）。
 *
 * <p>纯 JUnit（{@code new FormulaCalculator()}，无 Quarkus 容器），与既有
 * {@code FormulaCalculatorTest} 同风格。
 */
@DisplayName("FormulaCalculatorGoldenCasesTest — task-0801 黄金用例 + 链路基线")
class FormulaCalculatorGoldenCasesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private BigDecimal eval(String tokensJson, RowContext ctx) {
        return calc.evaluateExpression(j(tokensJson), ctx);
    }

    private static String numberToken(String v) {
        return "{\"type\":\"number\",\"value\":\"" + v + "\"}";
    }

    private static String opToken(String v) {
        return "{\"type\":\"operator\",\"value\":\"" + v + "\"}";
    }

    // ======================================================================
    // T2（求值点 #7 — ArithParser）+ G-1：0.1+0.2 精确 = 0.3
    // ======================================================================

    @Test
    @DisplayName("G-1 / T2 求值点#7: 0.1+0.2 = 0.3（精确，非 0.30000000000000004）")
    void g1_pointOnePlusPointTwo() {
        String tokens = "[" + numberToken("0.1") + "," + opToken("+") + "," + numberToken("0.2") + "]";
        BigDecimal result = eval(tokens, new RowContext());
        assertEquals(0, result.compareTo(new BigDecimal("0.3")), "实际=" + result);
    }

    // ======================================================================
    // G-4：10/3*3 = 10（中间不截断；12 位除法精度 + 显示边界规整到 6 位后精确复原为 10）
    // ======================================================================

    @Test
    @DisplayName("G-4: 10/3*3 = 10（显示）——中间若截到 6 位会得 9.999999，12 位中间精度才能复原")
    void g4_noIntermediateTruncation() {
        String tokens = "[" + numberToken("10") + "," + opToken("/") + "," + numberToken("3")
            + "," + opToken("*") + "," + numberToken("3") + "]";
        BigDecimal raw = eval(tokens, new RowContext());
        // 内部 12 位除法精度：10/3=3.333333333333，×3=9.999999999999（尚非精确 10，符合设计）
        assertEquals(0, raw.compareTo(new BigDecimal("9.999999999999")),
            "内部值应为 12 位精度的 9.999999999999，实际=" + raw);
        // 呈现边界规整到 6 位：HALF_UP 级联进位 → 10.000000
        BigDecimal displayed = PrecisionPolicy.round(raw);
        assertEquals(0, displayed.compareTo(new BigDecimal("10")), "显示值应为 10，实际=" + displayed);
    }

    // ======================================================================
    // G-3：1/3 = 0.333333（12 位中间精度算，显示规整 6 位）
    // ======================================================================

    @Test
    @DisplayName("G-3: 1/3 显示为 0.333333（12 位中间精度，显示规整 6 位）")
    void g3_oneThird() {
        BigDecimal raw = PrecisionPolicy.divide(BigDecimal.ONE, new BigDecimal("3"));
        assertEquals(0, raw.compareTo(new BigDecimal("0.333333333333")), "实际=" + raw);
        assertEquals("0.333333", NumberFormatUtil.format(raw, null, true));
    }

    // ======================================================================
    // G-5 / G-6：规整边界（HALF_UP，6 位以下归零 / 向上进位）
    // ======================================================================

    @Test
    @DisplayName("G-5: 0.0000004 规整到 6 位 = 0")
    void g5_roundsToZero() {
        BigDecimal rounded = PrecisionPolicy.round(new BigDecimal("0.0000004"));
        assertEquals(0, rounded.compareTo(BigDecimal.ZERO), "实际=" + rounded);
    }

    @Test
    @DisplayName("G-6: 0.0000005 规整到 6 位 = 0.000001（HALF_UP 向上进位）")
    void g6_halfUpRoundsUp() {
        BigDecimal rounded = PrecisionPolicy.round(new BigDecimal("0.0000005"));
        assertEquals(0, rounded.compareTo(new BigDecimal("0.000001")), "实际=" + rounded);
    }

    // ======================================================================
    // G-7：2.5×0.4=1（去尾零，不显示 1.000000）
    // ======================================================================

    @Test
    @DisplayName("G-7: 2.5×0.4=1（NumberFormatUtil 去尾零显示 \"1\" 非 \"1.000000\"）")
    void g7_stripTrailingZeros() {
        String tokens = "[" + numberToken("2.5") + "," + opToken("×") + "," + numberToken("0.4") + "]";
        BigDecimal raw = eval(tokens, new RowContext());
        assertEquals(0, raw.compareTo(BigDecimal.ONE), "实际=" + raw);
        assertEquals("1", NumberFormatUtil.format(raw, null, true));
    }

    // ======================================================================
    // G-8：空值 / null 参与运算按 0，结果非 null
    // ======================================================================

    @Test
    @DisplayName("G-8: 缺失 field 按 0 参与运算，结果非 null")
    void g8_missingFieldDefaultsToZero() {
        RowContext ctx = new RowContext();
        // "missing" 未写入 ctx.fieldValues → appendToken 回退 0
        String tokens = "[{\"type\":\"field\",\"value\":\"missing\"}," + opToken("+") + "," + numberToken("5") + "]";
        BigDecimal result = eval(tokens, ctx);
        assertNotNull(result);
        assertEquals(0, result.compareTo(new BigDecimal("5")), "实际=" + result);
    }

    // ======================================================================
    // G-9：除以 0 → 返回 0（不抛异常）
    // ======================================================================

    @Test
    @DisplayName("G-9: 除以 0 返回 0，不抛异常")
    void g9_divisionByZeroReturnsZeroNoThrow() {
        String tokens = "[" + numberToken("5") + "," + opToken("/") + "," + numberToken("0") + "]";
        BigDecimal result = assertDoesNotThrow(() -> eval(tokens, new RowContext()));
        assertEquals(0, result.compareTo(BigDecimal.ZERO), "实际=" + result);

        // PrecisionPolicy.divide 本身也不抛异常
        BigDecimal direct = assertDoesNotThrow(() -> PrecisionPolicy.divide(new BigDecimal("5"), BigDecimal.ZERO));
        assertEquals(0, direct.compareTo(BigDecimal.ZERO));
    }

    // ======================================================================
    // G-12：一元负号 -(2+3)*2 = -10
    // ======================================================================

    @Test
    @DisplayName("G-12: 一元负号 -(2+3)*2 = -10")
    void g12_unaryMinus() {
        String tokens = "[" + opToken("-") + ",{\"type\":\"bracket_open\"}," + numberToken("2") + ","
            + opToken("+") + "," + numberToken("3") + ",{\"type\":\"bracket_close\"}," + opToken("*")
            + "," + numberToken("2") + "]";
        BigDecimal result = eval(tokens, new RowContext());
        assertEquals(0, result.compareTo(new BigDecimal("-10")), "实际=" + result);
    }

    // ======================================================================
    // G-13：运算符优先级 2+3*4=14
    // ======================================================================

    @Test
    @DisplayName("G-13: 运算符优先级 2+3*4=14")
    void g13_operatorPrecedence() {
        String tokens = "[" + numberToken("2") + "," + opToken("+") + "," + numberToken("3")
            + "," + opToken("*") + "," + numberToken("4") + "]";
        BigDecimal result = eval(tokens, new RowContext());
        assertEquals(0, result.compareTo(new BigDecimal("14")), "实际=" + result);
    }

    // ======================================================================
    // G-14：全角运算符 2×3÷4=1.5
    // ======================================================================

    @Test
    @DisplayName("G-14: 全角运算符 2×3÷4=1.5（× → * ，÷ → / 转换不丢失）")
    void g14_fullWidthOperators() {
        String tokens = "[" + numberToken("2") + "," + opToken("×") + "," + numberToken("3")
            + "," + opToken("÷") + "," + numberToken("4") + "]";
        BigDecimal result = eval(tokens, new RowContext());
        assertEquals(0, result.compareTo(new BigDecimal("1.5")), "实际=" + result);
    }

    // ======================================================================
    // G-11：单价 123.456789 × 年用量 800000 = 98765431.2（亿级金额精度）
    // 直接走真实生产方法 CostingSubtotalUtil.lineCostingAmount（链路二起点，已去 setScale(4)）。
    // ======================================================================

    @Test
    @DisplayName("G-11 / T4: 单价 123.456789 × 800000 = 98765431.2（亿级金额精度）")
    void g11_billionScaleAmount() {
        String costingCardValuesJson =
            "{\"tabs\":[{\"componentType\":\"SUBTOTAL\",\"subtotal\":123.456789}]}";
        BigDecimal result = CostingSubtotalUtil.lineCostingAmount(costingCardValuesJson, 800000);
        assertEquals(0, result.compareTo(new BigDecimal("98765431.2")), "实际=" + result);
    }

    // ======================================================================
    // T3 / G-10：6 层嵌套 —— 元素行 → 列小计 → 页签合计 → 产品小计 → 行合计(×500000) →
    // 整单总额(20 行)，断言 = 一次性十进制精确计算结果。
    // ======================================================================

    @Test
    @DisplayName("T3 / G-10: 6 层嵌套链路 = 一次性十进制精确计算结果")
    void t3_g10_sixLayerChainMatchesOneShotComputation() {
        // ── 层 1+2+3：元素行 → 列小计 → 页签合计（走真实 FormulaCalculator，3 行 × 0.1 = 0.3，
        //    刻意选易触发 double 累加误差的 0.1，验证 BigDecimal 精确累加）──────────────────────
        String fields = "["
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
            + "{\"name\":\"数量\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.qty\"},"
            + "{\"name\":\"材料费\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
            + "]";
        String formulas = "["
            + "{\"name\":\"材料费\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}]}"
            + "]";
        String rkf = "[\"material_no\"]";
        String baseRows = "["
            + "{\"driverRow\":{\"material_no\":\"M1\"},\"basicDataValues\":{\"{v.up}\":0.1,\"{v.qty}\":1}},"
            + "{\"driverRow\":{\"material_no\":\"M2\"},\"basicDataValues\":{\"{v.up}\":0.1,\"{v.qty}\":1}},"
            + "{\"driverRow\":{\"material_no\":\"M3\"},\"basicDataValues\":{\"{v.up}\":0.1,\"{v.qty}\":1}}"
            + "]";
        // 页签合计（Tab1）：3 行 0.1 相加 = 0.3（精确，非 0.30000000000000004）
        BigDecimal tab1Subtotal = calc.computeTabSubtotal(
            j(fields), j(formulas), null, j(rkf), j(baseRows), j("[]"), Map.of());
        assertEquals(0, tab1Subtotal.compareTo(new BigDecimal("0.3")), "Tab1合计=" + tab1Subtotal);

        // 第二个页签同构，另得 0.3（模拟"来料+加工"两页签）
        BigDecimal tab2Subtotal = tab1Subtotal; // 同构页签，复用同一批算好的精确值

        // ── 层 4：产品小计 = Σ 页签合计 ─────────────────────────────────────────────────────
        BigDecimal productSubtotal = PrecisionPolicy.sum(java.util.List.of(tab1Subtotal, tab2Subtotal));
        assertEquals(0, productSubtotal.compareTo(new BigDecimal("0.6")), "产品小计=" + productSubtotal);

        // ── 层 5：行合计 = 产品小计 × 年用量 500000（真实生产方法 CostingSubtotalUtil.lineCostingAmount）──
        String costingCardValuesJson = "{\"tabs\":[{\"componentType\":\"SUBTOTAL\",\"subtotal\":"
            + productSubtotal.toPlainString() + "}]}";
        BigDecimal lineTotal = CostingSubtotalUtil.lineCostingAmount(costingCardValuesJson, 500000);
        assertEquals(0, lineTotal.compareTo(new BigDecimal("300000")), "行合计=" + lineTotal);

        // ── 层 6：整单总额 = Σ 20 行（镜像 CostingFreezeService#computeCostingTotalAmount 累加方式）──
        BigDecimal quotationTotal = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) {
            quotationTotal = quotationTotal.add(
                CostingSubtotalUtil.lineCostingAmount(costingCardValuesJson, 500000));
        }
        quotationTotal = PrecisionPolicy.round(quotationTotal);

        // ── 一次性十进制精确计算（独立算法路径，验证与分层链路结果逐位相同）───────────────────
        BigDecimal oneShot = PrecisionPolicy.round(
            productSubtotal.multiply(BigDecimal.valueOf(500000)).multiply(BigDecimal.valueOf(20)));

        assertEquals(0, quotationTotal.compareTo(oneShot),
            "分层链路=" + quotationTotal + " 一次性计算=" + oneShot);
        assertEquals(0, quotationTotal.compareTo(new BigDecimal("6000000")), "实际=" + quotationTotal);
    }
}
