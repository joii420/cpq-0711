package com.cpq.formula.function.lookup;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * LOOKUP(path, field?) — 单值查询，无结果返 NULL，多结果取首行。
 *
 * <p>v5.1 §3.2 查找函数。
 *
 * <p>参数约定（简化版 v1）：
 * <ul>
 *   <li>args[0] — 路径字符串（含 {path} 语法），或已解析的 Collection</li>
 *   <li>args[1] — 可选 field 名（从 Map 中取指定字段）</li>
 * </ul>
 *
 * <p>当 args[0] 是路径字符串时，通过 {@link DataLoader} 查询（同路径 dedupe）。
 * 多行结果取首行，无结果返回 null。
 */
@ApplicationScoped
public class LookupFunction implements FormulaFunction {

    @Override
    public String name() {
        return "LOOKUP";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("LOOKUP", "至少需要 1 个参数（路径）");
        }
        Object input  = args.get(0);
        String field  = args.size() >= 2 ? toStr(args.get(1)) : null;

        List<Map<String, Object>> rows = resolveRows(input, ctx);
        if (rows == null || rows.isEmpty()) return null;

        Map<String, Object> firstRow = rows.get(0);
        if (field != null && firstRow.containsKey(field)) return firstRow.get(field);
        if (field != null) return null;
        // 无 field 指定：若单列则返回该列值，否则返回整行 Map
        if (firstRow.size() == 1) return firstRow.values().iterator().next();
        return firstRow;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveRows(Object input, EvaluationContext ctx) {
        if (input == null) return null;
        if (input instanceof List<?> list) {
            if (list.isEmpty()) return List.of();
            if (list.get(0) instanceof Map) return (List<Map<String, Object>>) list;
            // 如果是标量列表，包装成 [{value: v}] 形式
            return list.stream()
                    .map(v -> (Map<String, Object>) Map.of("value", v))
                    .toList();
        }
        if (input instanceof String path && path.startsWith("{")) {
            DataLoader loader = ctx != null ? ctx.getDataLoader() : null;
            if (loader == null) return null;
            try {
                return loader.loadByPath(path).get();
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
        }
        return null;
    }

    private static String toStr(Object v) {
        return v instanceof String s ? s : (v != null ? v.toString() : null);
    }
}
