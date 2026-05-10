package com.cpq.formula.function.conditional;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * IF(cond, trueValue, falseValue) — 三元条件。
 *
 * <p>v5.1 §3.2 条件函数。
 * cond 必须为 Boolean（v5.1 不自动类型转换）。
 * 特殊：若 cond 为 Number，0 视为 false，其他为 true（兼容 JEXL 数值比较结果）。
 */
public class IfFunction implements FormulaFunction {

    @Override
    public String name() {
        return "IF";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 3) {
            return FormulaError.invalidArgs("IF", "需要 3 个参数：IF(cond, trueValue, falseValue)");
        }
        Object cond = args.get(0);
        boolean condBool = toBooleanStrict(cond);
        return condBool ? args.get(1) : args.get(2);
    }

    static boolean toBooleanStrict(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0;
        }
        // FormulaError 传入时视为 false（IFERROR 语义）
        return false;
    }
}
