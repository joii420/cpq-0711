package com.cpq.quotation.service.tabjoin;

import org.apache.commons.jexl3.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class SafeArithmeticTest {

    private Object eval(String expr) {
        JexlEngine jexl = new JexlBuilder().arithmetic(new SafeArithmetic()).strict(false).silent(true).create();
        return jexl.createExpression(expr).evaluate(new MapContext());
    }

    @Test
    void null_operand_treated_as_zero_in_add() {
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(eval("5 + a").toString())));
    }

    @Test
    void divide_by_zero_uses_one_as_divisor() {
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(eval("10 / 0").toString())));
    }

    @Test
    void divide_by_null_uses_one_as_divisor() {
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(eval("10 / b").toString())));
    }

    @Test
    void normal_division_unchanged() {
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(eval("10 / 2").toString())));
    }
}
