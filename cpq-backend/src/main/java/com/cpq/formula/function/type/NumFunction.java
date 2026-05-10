package com.cpq.formula.function.type;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * NUM(v) — 将值转换为数字（BigDecimal）。
 *
 * <p>v5.1 §3.2 类型转换函数。转换失败返回 FormulaError（不自动转换）。
 * <ul>
 *   <li>Number → BigDecimal</li>
 *   <li>String → 尝试 new BigDecimal(s)，失败返回 FormulaError</li>
 *   <li>Boolean → 1 / 0</li>
 *   <li>null → FormulaError</li>
 * </ul>
 */
public class NumFunction implements FormulaFunction {

    @Override
    public String name() {
        return "NUM";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 1) {
            return FormulaError.invalidArgs("NUM", "需要 1 个参数");
        }
        Object v = args.get(0);
        if (v == null) {
            return FormulaError.typeMismatch("Number|String|Boolean", "null");
        }
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return new FormulaError("NUM 转换失败：'" + s + "' 不是有效数字", "TYPE_MISMATCH");
            }
        }
        return FormulaError.typeMismatch("Number|String|Boolean", v.getClass().getSimpleName());
    }
}
