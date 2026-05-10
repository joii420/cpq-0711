package com.cpq.formula.function.aggregate;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.util.Collection;
import java.util.List;

/**
 * COUNT(table, conditions) — 行数，含 NULL。
 *
 * <p>v5.1 §3.2 聚合函数。空集返回 0。
 */
public class CountFunction implements FormulaFunction {

    @Override
    public String name() {
        return "COUNT";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("COUNT", "至少需要 1 个参数");
        }
        Object input = args.get(0);
        Iterable<?> rows = SumFunction.resolveRows(input, ctx);
        if (rows == null) return 0L;

        long count = 0L;
        for (Object ignored : rows) count++;
        return count;
    }
}
