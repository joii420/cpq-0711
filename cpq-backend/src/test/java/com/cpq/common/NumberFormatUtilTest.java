package com.cpq.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class NumberFormatUtilTest {

    @Test
    void computedFallbackNine() {
        // task-0801/task-0810：计算列未配兜底位数最终升至 9（PrecisionPolicy.DISPLAY_SCALE）。
        // 2026-06-21 曾定为 4 位（本用例原名 computedFallbackFour），已被 task-0801 推翻作废，
        // 见 docs/RECORD.md 2026-08-01 记录 + 类注释。
        // 0.04326 / 0.03414 均只有 5 位小数，9 位兜底下不再被压缩，原样保留（4 位兜底时曾被四舍五入）。
        assertEquals("0.04326", NumberFormatUtil.format(new BigDecimal("0.04326"), null, true));
        assertEquals("0.03414", NumberFormatUtil.format(new BigDecimal("0.03414"), null, true));
        assertEquals("0.0774", NumberFormatUtil.format(new BigDecimal("0.07740"), null, true)); // 列小计真值，去尾零
        // 0.00005 只有 5 位小数，9 位兜底下不再被压缩（4 位兜底时曾 HALF_UP 成 0.0001）
        assertEquals("0.00005", NumberFormatUtil.format(new BigDecimal("0.00005"), null, true));
        assertEquals("1.234567891", NumberFormatUtil.format(new BigDecimal("1.2345678914"), null, true));
        assertEquals("1.234567892", NumberFormatUtil.format(new BigDecimal("1.2345678915"), null, true));
        assertEquals("0", NumberFormatUtil.format(new BigDecimal("0.0000000004"), null, true));
        assertEquals("0.000000001", NumberFormatUtil.format(new BigDecimal("0.0000000005"), null, true));
    }

    @Test
    void trimTrailingZeros() {
        assertEquals("0.1", NumberFormatUtil.format(new BigDecimal("0.10"), null, true));
        assertEquals("5", NumberFormatUtil.format(new BigDecimal("5.00"), null, true));
        assertEquals("600", NumberFormatUtil.format(new BigDecimal("600"), null, true)); // no scientific notation
    }

    @Test
    void rawKeepsPrecision() {
        assertEquals("6.9755", NumberFormatUtil.format(new BigDecimal("6.9755"), null, false));
    }

    @Test
    void explicitOverrides() {
        assertEquals("6.98", NumberFormatUtil.format(new BigDecimal("6.9755"), 2, false));
        assertEquals("1.234567891", NumberFormatUtil.format(new BigDecimal("1.234567891234"), 12, true));
    }

    @Test
    void computedRoleCapUsesMinimumOfExplicitGlobalAndRoleLimits() {
        BigDecimal value = new BigDecimal("1.2345678915");
        assertEquals("1.2346", NumberFormatUtil.format(value, null, true, 4));
        assertEquals("1.234567892", NumberFormatUtil.format(value, null, true, 9));
        assertEquals("1.23", NumberFormatUtil.format(value, 2, true, 4));
        assertEquals("1.2346", NumberFormatUtil.format(value, 6, true, 4));
        assertEquals("1.234568", NumberFormatUtil.format(value, 6, true, 9));
    }

    @Test
    void roleCapKeepsHalfUpTrailingZeroAndRawValueContracts() {
        assertEquals("12.5", NumberFormatUtil.format(new BigDecimal("12.500000000000"), null, true, 4));
        assertEquals("1.2344", NumberFormatUtil.format(new BigDecimal("1.23444"), null, true, 4));
        assertEquals("1.2345", NumberFormatUtil.format(new BigDecimal("1.23445"), null, true, 4));
        assertEquals("-1.2345", NumberFormatUtil.format(new BigDecimal("-1.23445"), null, true, 4));
        assertEquals("0", NumberFormatUtil.format(new BigDecimal("-0.00001"), null, true, 4));
        assertEquals("1.2345678915", NumberFormatUtil.format(
                new BigDecimal("1.2345678915"), null, false, 4));
    }

    @Test
    void zeroAndNull() {
        assertEquals("0", NumberFormatUtil.format(new BigDecimal("0.00"), null, true));
        assertEquals("", NumberFormatUtil.format(null, 2, true));
    }
}
