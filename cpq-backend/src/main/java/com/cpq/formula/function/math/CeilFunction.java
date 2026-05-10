package com.cpq.formula.function.math;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * CEIL(v) — 向上取整（天花板函数）。
 *
 * <p>v5.1 §3.2 数学函数。参数必须为 Number。
 */
public class CeilFunction implements FormulaFunction {

    @Override
    public String name() {
        return "CEIL";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 1) {
            return FormulaError.invalidArgs("CEIL", "需要 1 个参数");
        }
        BigDecimal value = RoundFunction.toDecimalStrict("CEIL", args.get(0));
        if (value == null) return FormulaError.typeMismatch("Number", RoundFunction.typeName(args.get(0)));
        return value.setScale(0, RoundingMode.CEILING);
    }
}
