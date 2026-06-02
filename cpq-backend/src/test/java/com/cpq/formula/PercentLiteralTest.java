package com.cpq.formula;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PercentLiteralTest {
    @Test void rewrites_simple_percent() { assertEquals("(12/100.0)", PercentLiteral.rewrite("12%")); }
    @Test void rewrites_decimal_percent() { assertEquals("(2.5/100.0)", PercentLiteral.rewrite("2.5%")); }
    @Test void rewrites_inside_expression() { assertEquals("[A] * (12/100.0) + 1", PercentLiteral.rewrite("[A] * 12% + 1")); }
    @Test void leaves_plain_numbers_untouched() { assertEquals("12 + 3.5", PercentLiteral.rewrite("12 + 3.5")); }
    @Test void null_safe() { assertEquals(null, PercentLiteral.rewrite(null)); }
}
