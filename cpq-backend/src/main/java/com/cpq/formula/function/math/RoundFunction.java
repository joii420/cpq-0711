package com.cpq.formula.function.math;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * ROUND(v, decimals) — 四舍五入到指定小数位。
 *
 * <p>v5.1 §3.2 数学函数。参数必须为 Number（不自动转换）。
 */
public class RoundFunction implements FormulaFunction {

    @Override
    public String name() {
        return "ROUND";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 2) {
            return FormulaError.invalidArgs("ROUND", "需要 2 个参数：ROUND(v, decimals)");
        }
        BigDecimal value = toDecimalStrict("ROUND", args.get(0));
        if (value == null) return FormulaError.typeMismatch("Number", typeName(args.get(0)));

        BigDecimal decArgs = toDecimalStrict("ROUND", args.get(1));
        if (decArgs == null) return FormulaError.typeMismatch("Number", typeName(args.get(1)));

        int scale = decArgs.intValue();
        if (scale < 0 || scale > 20) {
            return FormulaError.invalidArgs("ROUND", "小数位数必须在 [0, 20] 范围内，实际：" + scale);
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    static BigDecimal toDecimalStrict(String fnName, Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return null;
    }

    static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
