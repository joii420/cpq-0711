package com.cpq.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0801 公式计算精度优化 — B1 {@link PrecisionPolicy} 单元测试。
 * 对应 backtask.md Task B1 验收清单 + T5（常量锁定）。
 */
@DisplayName("PrecisionPolicyTest")
class PrecisionPolicyTest {

    // ======================================================================
    // T5：常量锁定（api.md §5.1 前后端精度契约锚点）
    // ======================================================================

    @Test
    @DisplayName("T5: DISPLAY_SCALE == 6")
    void displayScaleIsSix() {
        assertEquals(6, PrecisionPolicy.DISPLAY_SCALE);
    }

    @Test
    @DisplayName("T5: DIVISION_SCALE == 12")
    void divisionScaleIsTwelve() {
        assertEquals(12, PrecisionPolicy.DIVISION_SCALE);
    }

    @Test
    @DisplayName("ROUNDING == HALF_UP，MC == DECIMAL128")
    void roundingAndMathContext() {
        assertEquals(RoundingMode.HALF_UP, PrecisionPolicy.ROUNDING);
        assertEquals(MathContext.DECIMAL128, PrecisionPolicy.MC);
    }

    // ======================================================================
    // of(double) —— 唯一入口必须走 valueOf 语义（最短十进制还原）
    // ======================================================================

    @Test
    @DisplayName("of(0.1) 精确等于 0.1（非 new BigDecimal(0.1) 的二进制展开误差）")
    void of_double_zeroPointOne() {
        BigDecimal v = PrecisionPolicy.of(0.1);
        assertEquals(0, v.compareTo(new BigDecimal("0.1")));
        // 关键：of(0.1) 绝不能等于 new BigDecimal(0.1) 的真实二进制展开值
        assertNotEquals(new BigDecimal(0.1), v);
    }

    @Test
    @DisplayName("of(NaN)/of(Infinity) 返回 ZERO（不抛异常）")
    void of_double_nanAndInfinite() {
        assertEquals(0, PrecisionPolicy.of(Double.NaN).compareTo(BigDecimal.ZERO));
        assertEquals(0, PrecisionPolicy.of(Double.POSITIVE_INFINITY).compareTo(BigDecimal.ZERO));
        assertEquals(0, PrecisionPolicy.of(Double.NEGATIVE_INFINITY).compareTo(BigDecimal.ZERO));
    }

    // ======================================================================
    // of(Object) —— Number / String / null 统一转换
    // ======================================================================

    @Test
    @DisplayName("of(Object) 处理 null / BigDecimal / Double / Long / String / 非法字符串")
    void of_object_variants() {
        assertEquals(0, PrecisionPolicy.of((Object) null).compareTo(BigDecimal.ZERO));
        assertEquals(0, PrecisionPolicy.of((Object) new BigDecimal("0.1")).compareTo(new BigDecimal("0.1")));
        assertEquals(0, PrecisionPolicy.of((Object) 0.1).compareTo(new BigDecimal("0.1")));
        assertEquals(0, PrecisionPolicy.of((Object) 100L).compareTo(new BigDecimal("100")));
        assertEquals(0, PrecisionPolicy.of((Object) "0.1").compareTo(new BigDecimal("0.1")));
        assertEquals(0, PrecisionPolicy.of((Object) "  0.5  ").compareTo(new BigDecimal("0.5")));
        assertEquals(0, PrecisionPolicy.of((Object) "not-a-number").compareTo(BigDecimal.ZERO));
        assertEquals(0, PrecisionPolicy.of((Object) "").compareTo(BigDecimal.ZERO));
    }

    // ======================================================================
    // round —— 呈现边界规整（G-2/G-5/G-6）
    // ======================================================================

    @Test
    @DisplayName("round(1.0000005) = 1.000001（HALF_UP 向上进位，B1 验收用例）")
    void round_halfUpBoundary() {
        BigDecimal r = PrecisionPolicy.round(new BigDecimal("1.0000005"));
        assertEquals(0, r.compareTo(new BigDecimal("1.000001")), "实际=" + r);
    }

    @Test
    @DisplayName("G-2: 1.005 规整到 2 位 = 1.01（HALF_UP 舍入边界，非 double 的 1.00）")
    void round_g2_1005ToTwoDecimals() {
        BigDecimal r = new BigDecimal("1.005").setScale(2, PrecisionPolicy.ROUNDING);
        assertEquals(0, r.compareTo(new BigDecimal("1.01")), "实际=" + r);
    }

    @Test
    @DisplayName("round(null) 返回 null（null 安全）")
    void round_nullSafe() {
        assertNull(PrecisionPolicy.round(null));
    }

    // ======================================================================
    // divide —— 精确除法（R-4：无限小数不抛异常）
    // ======================================================================

    @Test
    @DisplayName("B1 验收: divide(1,3) 不抛异常且 >=12 位")
    void divide_oneThird_atLeastTwelveDigits() {
        BigDecimal r = PrecisionPolicy.divide(BigDecimal.ONE, new BigDecimal("3"));
        assertEquals(12, r.scale(), "scale 应为 DIVISION_SCALE=12，实际 scale=" + r.scale());
        assertEquals(0, r.compareTo(new BigDecimal("0.333333333333")));
    }

    @Test
    @DisplayName("G-9: 除以 0 返回 ZERO，不抛异常")
    void divide_byZero_returnsZeroNoThrow() {
        BigDecimal r = assertDoesNotThrow(() -> PrecisionPolicy.divide(new BigDecimal("5"), BigDecimal.ZERO));
        assertEquals(0, r.compareTo(BigDecimal.ZERO));
        // 除数 null 同样兜底 0（不抛 NPE）
        BigDecimal r2 = assertDoesNotThrow(() -> PrecisionPolicy.divide(new BigDecimal("5"), null));
        assertEquals(0, r2.compareTo(BigDecimal.ZERO));
        // 被除数 null 按 0 参与
        BigDecimal r3 = assertDoesNotThrow(() -> PrecisionPolicy.divide(null, new BigDecimal("5")));
        assertEquals(0, r3.compareTo(BigDecimal.ZERO));
    }

    // ======================================================================
    // sum —— BigDecimal 精确累加
    // ======================================================================

    @Test
    @DisplayName("sum: 三个 0.1 精确累加 = 0.3（非 double 累加误差）")
    void sum_threeTenths() {
        BigDecimal r = PrecisionPolicy.sum(List.of(
            new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.1")));
        assertEquals(0, r.compareTo(new BigDecimal("0.3")), "实际=" + r);
    }

    @Test
    @DisplayName("sum: null 元素按 0 处理，null 集合返回 ZERO")
    void sum_nullSafe() {
        java.util.List<BigDecimal> withNull = new java.util.ArrayList<>();
        withNull.add(new BigDecimal("1"));
        withNull.add(null);
        withNull.add(new BigDecimal("2"));
        assertEquals(0, PrecisionPolicy.sum(withNull).compareTo(new BigDecimal("3")));
        assertEquals(0, PrecisionPolicy.sum(null).compareTo(BigDecimal.ZERO));
    }

    // ======================================================================
    // new BigDecimal(<double>) 禁用规约（B1 验收：全工程 grep 复核，此处补充单测层面的反例演示）
    // ======================================================================

    @Test
    @DisplayName("演示: new BigDecimal(double) 是被禁止的反模式（对比 of(double) 的正确行为）")
    void demonstrateWhyNewBigDecimalDoubleIsForbidden() {
        // 0.1 的 IEEE754 二进制展开：new BigDecimal(0.1) 会得到一长串非 0.1 的尾数
        BigDecimal wrong = new BigDecimal(0.1);
        assertNotEquals(0, wrong.compareTo(new BigDecimal("0.1")));
        // PrecisionPolicy.of(double) 走 valueOf，规避了这个陷阱
        BigDecimal correct = PrecisionPolicy.of(0.1);
        assertEquals(0, correct.compareTo(new BigDecimal("0.1")));
    }
}
