package com.cpq.formula.function.type;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.util.List;

/**
 * STR(v) — 将值转换为字符串。
 *
 * <p>v5.1 §3.2 类型转换函数。null → FormulaError。
 */
public class StrFunction implements FormulaFunction {

    @Override
    public String name() {
        return "STR";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 1) {
            return FormulaError.invalidArgs("STR", "需要 1 个参数");
        }
        Object v = args.get(0);
        if (v == null) {
            return FormulaError.typeMismatch("any non-null", "null");
        }
        return v.toString();
    }
}
