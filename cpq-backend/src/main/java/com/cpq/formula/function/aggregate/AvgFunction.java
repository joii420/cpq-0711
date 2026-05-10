package com.cpq.formula.function.aggregate;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * AVG(table, conditions, field) — 平均值，空集返回 NULL。
 *
 * <p>v5.1 §3.2 聚合函数。
 */
public class AvgFunction implements FormulaFunction {

    @Override
    public String name() {
        return "AVG";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("AVG", "至少需要 1 个参数");
        }
        Object input = args.get(0);
        String fieldName = args.size() >= 2 ? SumFunction.toStringArg(args.get(1)) : null;

        Iterable<?> rows = SumFunction.resolveRows(input, ctx);
        if (rows == null) return null; // 空集返回 NULL

        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (Object row : rows) {
            BigDecimal v = SumFunction.extractNumeric(row, fieldName);
            if (v != null) {
                sum = sum.add(v);
                count++;
            }
        }
        if (count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }
}
