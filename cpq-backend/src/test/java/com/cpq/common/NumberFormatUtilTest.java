package com.cpq.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class NumberFormatUtilTest {

    @Test
    void computedFallbackFour() {
        // 精度优先：计算列未配兜底 4 位（原 2 位会把 0.04326 压成 0.04 致小计错乱）
        assertEquals("0.0433", NumberFormatUtil.format(new BigDecimal("0.04326"), null, true));
        assertEquals("0.0341", NumberFormatUtil.format(new BigDecimal("0.03414"), null, true));
        assertEquals("0.0774", NumberFormatUtil.format(new BigDecimal("0.07740"), null, true)); // 列小计真值
        assertEquals("0.0001", NumberFormatUtil.format(new BigDecimal("0.00005"), null, true)); // HALF_UP
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
