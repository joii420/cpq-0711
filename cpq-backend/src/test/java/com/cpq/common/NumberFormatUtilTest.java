package com.cpq.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class NumberFormatUtilTest {

    @Test
    void computedFallbackTwo() {
        assertEquals("0.14", NumberFormatUtil.format(new BigDecimal("0.144"), null, true));
        assertEquals("0.15", NumberFormatUtil.format(new BigDecimal("0.145"), null, true));
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
