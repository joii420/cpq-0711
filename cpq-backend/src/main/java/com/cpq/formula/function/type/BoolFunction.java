package com.cpq.formula.function.type;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOOL(v) — 将值转换为 Boolean。
 *
 * <p>v5.1 §3.2 类型转换函数。
 * <ul>
 *   <li>Boolean → 原值</li>
 *   <li>Number: 0 → false, 其他 → true</li>
 *   <li>String: "true"/"1" → true, "false"/"0" → false, 其他 → FormulaError</li>
 *   <li>null → FormulaError</li>
 * </ul>
 */
public class BoolFunction implements FormulaFunction {

    @Override
    public String name() {
        return "BOOL";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() != 1) {
            return FormulaError.invalidArgs("BOOL", "需要 1 个参数");
        }
        Object v = args.get(0);
        if (v == null) {
            return FormulaError.typeMismatch("Boolean|Number|String", "null");
        }
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0;
        }
        if (v instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "true",  "1" -> Boolean.TRUE;
                case "false", "0" -> Boolean.FALSE;
                default -> new FormulaError("BOOL 转换失败：无法将 '" + s + "' 转换为布尔值", "TYPE_MISMATCH");
            };
        }
        return FormulaError.typeMismatch("Boolean|Number|String", v.getClass().getSimpleName());
    }
}
