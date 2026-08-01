package com.cpq.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class NumberFormatUtilTest {

    @Test
    void computedFallbackSix() {
        // task-0801：计算列未配兜底位数由 4 → 6（PrecisionPolicy.DISPLAY_SCALE）。
        // 2026-06-21 曾定为 4 位（本用例原名 computedFallbackFour），已被 task-0801 推翻作废，
        // 见 docs/RECORD.md 2026-08-01 记录 + 类注释。
        // 0.04326 / 0.03414 均只有 5 位小数，6 位兜底下不再被压缩，原样保留（4 位兜底时曾被四舍五入）。
        assertEquals("0.04326", NumberFormatUtil.format(new BigDecimal("0.04326"), null, true));
        assertEquals("0.03414", NumberFormatUtil.format(new BigDecimal("0.03414"), null, true));
        assertEquals("0.0774", NumberFormatUtil.format(new BigDecimal("0.07740"), null, true)); // 列小计真值，去尾零
        // 0.00005 只有 5 位小数，6 位兜底下不再被压缩（4 位兜底时曾 HALF_UP 成 0.0001）
        assertEquals("0.00005", NumberFormatUtil.format(new BigDecimal("0.00005"), null, true));
        // G-6：第 7 位小数才触发 HALF_UP 向上进位（6 位边界）
        assertEquals("0.000001", NumberFormatUtil.format(new BigDecimal("0.0000005"), null, true));
        // G-5：规整到 6 位后为 0
        assertEquals("0", NumberFormatUtil.format(new BigDecimal("0.0000004"), null, true));
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
    }

    @Test
    void zeroAndNull() {
        assertEquals("0", NumberFormatUtil.format(new BigDecimal("0.00"), null, true));
        assertEquals("", NumberFormatUtil.format(null, 2, true));
    }
}
