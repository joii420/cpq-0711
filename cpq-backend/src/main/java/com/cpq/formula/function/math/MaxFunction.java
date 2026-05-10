package com.cpq.formula.function.math;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * MAX(a, b, ...) — 返回参数列表中的最大值。
 *
 * <p>v5.1 §3.2 数学函数（多值 MAX_OF）。
 * 至少 1 个参数，全部必须为 Number。
 */
public class MaxFunction implements FormulaFunction {

    @Override
    public String name() {
        return "MAX";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("MAX", "至少需要 1 个参数");
        }
        BigDecimal max = null;
        for (int i = 0; i < args.size(); i++) {
            BigDecimal v = RoundFunction.toDecimalStrict("MAX", args.get(i));
            if (v == null) return FormulaError.typeMismatch("Number", RoundFunction.typeName(args.get(i)));
            if (max == null || v.compareTo(max) > 0) max = v;
        }
        return max;
    }
}
