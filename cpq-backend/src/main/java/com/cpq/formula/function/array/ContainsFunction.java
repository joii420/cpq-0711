package com.cpq.formula.function.array;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.util.Collection;
import java.util.List;

/**
 * CONTAINS(array, value) — 检查数组/字符串是否包含指定值。
 *
 * <p>v5.1 §3.2 数组函数。
 * <ul>
 *   <li>Collection: 委托 {@link InFunction}</li>
 *   <li>String: 子串包含（contains）</li>
 * </ul>
 */
public class ContainsFunction implements FormulaFunction {

    private final InFunction inFn = new InFunction();

    @Override
    public String name() {
        return "CONTAINS";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() < 2) {
            return FormulaError.invalidArgs("CONTAINS", "需要 2 个参数：CONTAINS(array, value)");
        }
        Object container = args.get(0);
        Object value     = args.get(1);

        // String 子串检查
        if (container instanceof String s) {
            String search = value != null ? value.toString() : "";
            return s.contains(search);
        }

        // Collection 成员检查（参数顺序对调为 IN 的顺序）
        if (container instanceof Collection<?>) {
            return inFn.invoke(List.of(value, container), ctx);
        }

        return FormulaError.typeMismatch("String|Collection", container == null ? "null" : container.getClass().getSimpleName());
    }
}
