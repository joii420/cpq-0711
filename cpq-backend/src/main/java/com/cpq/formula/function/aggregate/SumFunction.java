package com.cpq.formula.function.aggregate;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.function.FormulaFunction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * SUM(table, conditions, field) — 对路径查询结果求和，空集返回 0。
 *
 * <p>v5.1 §3.2 聚合函数。
 *
 * <p>参数约定（与 v5.1 §3.2 保持一致）：
 * <ul>
 *   <li>args[0] — 路径字符串（如 "{元素BOM[元素='Ag'].组成含量}"），也可直接传 Collection</li>
 *   <li>args[1..n] — 可选额外过滤（v1 简化：忽略，路径中已包含 conditions）</li>
 * </ul>
 *
 * <p>实际上 FormulaEngine 会先将 {path} 替换为具体值（List），
 * 因此 SUM 接收到的 args[0] 通常是 List&lt;Map&gt; 或 List&lt;Number&gt;。
 * 如果 args[0] 是字符串路径，则通过 DataLoader 异步查询再聚合。
 */
public class SumFunction implements FormulaFunction {

    @Override
    public String name() {
        return "SUM";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("SUM", "至少需要 1 个参数");
        }
        // args[0] 可以是路径字符串或 Collection/List
        Object input = args.get(0);
        String fieldName = args.size() >= 2 ? toStringArg(args.get(1)) : null;

        Iterable<?> rows = resolveRows(input, ctx);
        if (rows == null) {
            return BigDecimal.ZERO; // 空集返回 0（v5.1 §3.2 SUM 空集语义）
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Object row : rows) {
            BigDecimal v = extractNumeric(row, fieldName);
            if (v != null) sum = sum.add(v);
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    static Iterable<?> resolveRows(Object input, EvaluationContext ctx) {
        if (input == null) return null;
        if (input instanceof Collection<?> c) return c;
        if (input instanceof Object[] arr) return List.of(arr);
        // 字符串路径：通过 DataLoader 查询
        if (input instanceof String path && path.startsWith("{") && ctx != null && ctx.getDataLoader() != null) {
            try {
                List<Map<String, Object>> result = ctx.getDataLoader().loadByPath(path).get();
                return result;
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
        }
        // 单值情况
        return List.of(input);
    }

    @SuppressWarnings("unchecked")
    static BigDecimal extractNumeric(Object row, String fieldName) {
        if (row == null) return null;
        Object target = row;
        if (fieldName != null && row instanceof Map<?, ?> m) {
            target = m.get(fieldName);
        }
        if (target instanceof BigDecimal bd) return bd;
        if (target instanceof Number n) return new BigDecimal(n.toString());
        if (target instanceof String s) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    static String toStringArg(Object v) {
        return v instanceof String s ? s : null;
    }
}
