package com.cpq.formula.function.math;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * ABS(v) — 返回绝对值。
 *
 * <p>v5.1 §3.2 数学函数。参数必须为 Number。
 */
public class AbsFunction implements FormulaFunction {

    @Override
    public String name() {
        return "ABS";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 1) {
            return FormulaError.invalidArgs("ABS", "需要 1 个参数");
        }
        BigDecimal value = RoundFunction.toDecimalStrict("ABS", args.get(0));
        if (value == null) return FormulaError.typeMismatch("Number", RoundFunction.typeName(args.get(0)));
        return value.abs();
    }
}
