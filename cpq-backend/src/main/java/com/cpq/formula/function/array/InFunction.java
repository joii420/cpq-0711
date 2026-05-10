package com.cpq.formula.function.array;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * IN(value, array) — 检查 value 是否在 array 中（精确匹配）。
 *
 * <p>v5.1 §3.2 数组函数。array 可以是 List 或任意 Collection。
 * 比较使用 {@link Objects#equals}（字符串区分大小写）。
 */
public class InFunction implements FormulaFunction {

    @Override
    public String name() {
        return "IN";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() < 2) {
            return FormulaError.invalidArgs("IN", "需要 2 个参数：IN(value, array)");
        }
        Object value = args.get(0);
        Object array = args.get(1);

        if (array instanceof Collection<?> c) {
            return c.stream().anyMatch(item -> Objects.equals(item, value) || toStringEqual(item, value));
        }
        // array 是单值时，直接比较
        return Objects.equals(array, value) || toStringEqual(array, value);
    }

    private static boolean toStringEqual(Object a, Object b) {
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }
}
