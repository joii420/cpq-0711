package com.cpq.quotation.service.tabjoin;

import com.cpq.common.PrecisionPolicy;
import org.apache.commons.jexl3.JexlArithmetic;

/**
 * 页签连表公式专属算术：
 * - null 操作数按 0（加减乘）— 通过 strict=false + 父类对 null 的处理基础上，divide 显式兜底。
 * - 除数为 0 或 null → 按除数=1（spec：除数取不到默认 1，避免 DIV_ZERO 中断）。
 *
 * <p>task-0801 B3（求值点 #5）：3 参构造器配 {@link PrecisionPolicy#MC} + {@link PrecisionPolicy#DIVISION_SCALE}，
 * 与 {@link com.cpq.common.DecimalJexl#newEngine()} 的通用配置等价，但保留本类特有的除零兜底行为
 * （不用 DecimalJexl.newEngine() 整体替换，避免丢失 divide-by-zero-safe 语义）。
 */
public class SafeArithmetic extends JexlArithmetic {

    public SafeArithmetic() {
        super(false, PrecisionPolicy.MC, PrecisionPolicy.DIVISION_SCALE); // 非严格：null 当 0 参与算术
    }

    @Override
    public Object divide(Object left, Object right) {
        java.math.BigDecimal dividend = exact(left);
        if (right == null || isZero(right)) {
            return PrecisionPolicy.roundForCalculation(dividend);
        }
        return PrecisionPolicy.divide(dividend, exact(right));
    }

    /**
     * repair-260916 B-8 (D-10): a formula literal without the JEXL "B" suffix (e.g. {@code / 1.13})
     * arrives as Double/Float. {@link PrecisionPolicy#of} rejects floating point (the Excel column
     * then silently became empty), so convert via its shortest decimal text first —
     * {@code Double.toString(1.13)} is {@code "1.13"}, i.e. the literal the user typed.
     * Non-finite values (NaN/Infinity) cannot be represented and count as 0.
     */
    static java.math.BigDecimal exact(Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return java.math.BigDecimal.ZERO;
            return new java.math.BigDecimal(v.toString());
        }
        return PrecisionPolicy.of(v);
    }

    private boolean isZero(Object v) {
        try {
            return toBigDecimal(v).signum() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
