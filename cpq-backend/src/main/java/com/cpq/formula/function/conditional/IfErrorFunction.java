package com.cpq.formula.function.conditional;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.util.List;

/**
 * IFERROR(value, fallback) — 若 value 是 FormulaError 则返回 fallback，否则返回 value。
 *
 * <p>v5.1 §3.2 条件函数（COALESCE 扩展版，专门处理 ERROR 单元格）。
 */
public class IfErrorFunction implements FormulaFunction {

    @Override
    public String name() {
        return "IFERROR";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 2) {
            return FormulaError.invalidArgs("IFERROR", "需要 2 个参数：IFERROR(value, fallback)");
        }
        Object value    = args.get(0);
        Object fallback = args.get(1);
        return (value instanceof FormulaError) ? fallback : value;
    }
}
