package com.cpq.quotation.service.tabjoin;

import org.apache.commons.jexl3.JexlArithmetic;

/**
 * 页签连表公式专属算术：
 * - null 操作数按 0（加减乘）— 通过 strict=false + 父类对 null 的处理基础上，divide 显式兜底。
 * - 除数为 0 或 null → 按除数=1（spec：除数取不到默认 1，避免 DIV_ZERO 中断）。
 */
public class SafeArithmetic extends JexlArithmetic {

    public SafeArithmetic() {
        super(false); // 非严格：null 当 0 参与算术
    }

    @Override
    public Object divide(Object left, Object right) {
        if (right == null || isZero(right)) {
            return super.divide(left == null ? 0 : left, 1);
        }
        return super.divide(left == null ? 0 : left, right);
    }

    private boolean isZero(Object v) {
        try {
            return toBigDecimal(v).signum() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
