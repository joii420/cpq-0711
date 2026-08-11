package com.cpq.common;

import org.apache.commons.jexl3.JexlArithmetic;

import java.math.BigDecimal;

/** JEXL arithmetic that keeps division on the CPQ BigDecimal working-value contract. */
public final class DecimalJexlArithmetic extends JexlArithmetic {

    public DecimalJexlArithmetic() {
        super(false, PrecisionPolicy.MC, PrecisionPolicy.CALCULATION_SCALE);
    }

    @Override
    public Object divide(Object left, Object right) {
        return PrecisionPolicy.divide(decimal(left), decimal(right));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return PrecisionPolicy.of(value);
    }
}
