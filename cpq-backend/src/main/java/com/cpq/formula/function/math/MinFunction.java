package com.cpq.formula.function.math;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * MIN(a, b, ...) — 返回参数列表中的最小值。
 *
 * <p>v5.1 §3.2 数学函数（多值 MIN_OF）。
 * 至少 1 个参数，全部必须为 Number。
 */
public class MinFunction implements FormulaFunction {

    @Override
    public String name() {
        return "MIN";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("MIN", "至少需要 1 个参数");
        }
        BigDecimal min = null;
        for (int i = 0; i < args.size(); i++) {
            BigDecimal v = RoundFunction.toDecimalStrict("MIN", args.get(i));
            if (v == null) return FormulaError.typeMismatch("Number", RoundFunction.typeName(args.get(i)));
            if (min == null || v.compareTo(min) < 0) min = v;
        }
        return min;
    }
}
