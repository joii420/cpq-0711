package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260916 B-8 (D-10 / AC-14g): dividing by a decimal literal written without the JEXL "B"
 * suffix (JEXL parses it as Double) must not throw — it is converted via its decimal text.
 */
class SafeArithmeticDecimalLiteralTest {

    private final SafeArithmetic arith = new SafeArithmetic();

    @Test
    void divide_doubleDivisor_usesDecimalText() {
        Object r = arith.divide(new BigDecimal("1.978941064"), 1.13d);
        assertEquals(0, new BigDecimal("1.751275277876").compareTo((BigDecimal) r), "got " + r);
    }

    @Test
    void divide_doubleDividend_andFloat() {
        Object r1 = arith.divide(1.13d, new BigDecimal("2"));
        assertEquals(0, new BigDecimal("0.565").compareTo((BigDecimal) r1), "got " + r1);
        Object r2 = arith.divide(new BigDecimal("2.26"), 1.13f);
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) r2), "got " + r2);
    }

    @Test
    void divide_zeroOrNullDivisor_keepsExistingFallback() {
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) arith.divide(new BigDecimal("5"), 0.0d)));
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) arith.divide(new BigDecimal("5"), null)));
    }

    @Test
    void exact_nonFinite_isZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(SafeArithmetic.exact(Double.NaN)));
        assertEquals(0, BigDecimal.ZERO.compareTo(SafeArithmetic.exact(Double.POSITIVE_INFINITY)));
    }

    @Test
    void evaluator_expressionWithDecimalLiteralDivisor_isNotSilentlyEmpty() {
        // end-to-end through the JEXL engine used by TabJoinPlanEvaluator (was: IllegalArgumentException)
        TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();
        BigDecimal v = ev.evalExpression("[T.a] / 1.13", List.of(Map.of("T.a", "2.26")), Map.of());
        assertEquals(0, new BigDecimal("2").compareTo(v), "got " + v);
    }
}
