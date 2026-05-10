package com.cpq.formula.function.lookup;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * EXISTS(path) — 存在性检查，返回 Boolean。
 *
 * <p>v5.1 §3.2 查找函数。
 * 路径有结果 → true；无结果 → false；查询失败 → FormulaError。
 */
@ApplicationScoped
public class ExistsFunction implements FormulaFunction {

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.isEmpty()) {
            return FormulaError.invalidArgs("EXISTS", "需要 1 个参数（路径）");
        }
        Object input = args.get(0);

        if (input == null) return Boolean.FALSE;
        if (input instanceof Collection<?> c) return !c.isEmpty();

        if (input instanceof String path && path.startsWith("{")) {
            DataLoader loader = ctx != null ? ctx.getDataLoader() : null;
            if (loader == null) return FormulaError.invalidArgs("EXISTS", "EvaluationContext 中缺少 DataLoader");
            try {
                List<Map<String, Object>> rows = loader.loadByPath(path).get();
                return rows != null && !rows.isEmpty();
            } catch (InterruptedException | ExecutionException e) {
                return new FormulaError("EXISTS 查询失败：" + e.getMessage(), "QUERY_ERROR");
            }
        }
        // 其他情况视为存在（非 null 标量值）
        return Boolean.TRUE;
    }
}
