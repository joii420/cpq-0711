package com.cpq.common;

import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0801 公式计算精度优化 — B3 {@link DecimalJexl} 工厂单测。
 *
 * <p>本文件是 backtask.md Task B3「要点与坑 #2」要求的字面断言：
 * <blockquote>加完必须验证：写一条断言 {@code evaluate("0.1B+0.2B")} 返回
 * {@code BigDecimal("0.3")}；若只改了 MathContext 没加后缀，这条会得 0.30000000000000004，
 * 即为 R-3 未修复</blockquote>
 */
@DisplayName("DecimalJexlTest")
class DecimalJexlTest {

    @Test
    @DisplayName("R-3 断言: evaluate(\"0.1B+0.2B\") == BigDecimal(\"0.3\")（B 后缀 + MathContext 同时生效）")
    void bSuffixLiteralsAreExactlyDecimal() {
        JexlEngine jexl = DecimalJexl.newEngine();
        JexlContext ctx = new MapContext();
        Object result = jexl.createExpression("0.1B+0.2B").evaluate(ctx);
        assertInstanceOf(BigDecimal.class, result, "字面量未按 BigDecimal 解析，实际类型=" + result.getClass());
        assertEquals(0, ((BigDecimal) result).compareTo(new BigDecimal("0.3")), "实际=" + result);
    }

    @Test
    @DisplayName("反例: 不带 B 后缀的字面量仍按 Double 解析（证明后缀是必要条件，R-3 的对照组）")
    void withoutBSuffix_stillParsesAsDouble() {
        JexlEngine jexl = DecimalJexl.newEngine();
        JexlContext ctx = new MapContext();
        Object result = jexl.createExpression("0.1+0.2").evaluate(ctx);
        // 未加后缀 → JEXL 语法层仍解析成 Double，double 加法产生经典二进制误差。
        assertInstanceOf(Double.class, result, "预期仍是 Double（对照组），实际类型=" + result.getClass());
        assertNotEquals(0.3d, result, "若这里意外精确等于 0.3，说明测试假设已过时，需重新核对 JEXL 字面量语法");
    }

    /**
     * R-4：1/3 这类无限小数不得抛异常。
     *
     * <p>探针结果（javap 反编译 commons-jexl3 3.3 {@code JexlArithmetic.divide}/{@code
     * roundBigDecimal} 字节码验证）：两个 BigDecimal 操作数相除时，JEXL 走
     * {@code BigDecimal.divide(BigDecimal, MathContext)}（{@link PrecisionPolicy#MC}，
     * DECIMAL128=34 位有效数字），<b>不</b>额外按构造器第 3 参 {@code bigdScale} 收缩 scale——
     * 该参数只在"非 BigDecimal 操作数被强转成 BigDecimal"的{@code toBigDecimal()}强制转换路径里
     * 通过 {@code roundBigDecimal()} 生效（如字面量未加 B 后缀、与 BigDecimal 混算时的兜底转换）。
     * 因此本例 1B/3B 实际精度是 MathContext 的 34 位有效数字（比 DIVISION_SCALE=12 更宽松，
     * 非退化），只要求值不抛异常、值本身在 12 位精度内与预期一致即满足 R-4。
     */
    @Test
    @DisplayName("1/3 除法（BigDecimal 字面量）不抛异常，且精度不低于 DIVISION_SCALE")
    void divisionDoesNotThrow_andMeetsMinimumPrecision() {
        JexlEngine jexl = DecimalJexl.newEngine();
        JexlContext ctx = new MapContext();
        Object result = assertDoesNotThrow(() -> jexl.createExpression("1B/3B").evaluate(ctx));
        assertInstanceOf(BigDecimal.class, result);
        BigDecimal bd = (BigDecimal) result;
        assertTrue(bd.scale() >= PrecisionPolicy.DIVISION_SCALE,
            "JEXL 原生 BigDecimal 除法精度不应低于 DIVISION_SCALE=12，实际 scale=" + bd.scale());
        assertEquals(0, bd.setScale(PrecisionPolicy.DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
            .compareTo(new BigDecimal("0.333333333333")), "实际=" + bd);
    }
}
