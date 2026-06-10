package com.cpq.component.formula;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TokenMappabilityValidator {

    public record Result(boolean mappable, String reason) {}

    /**
     * 判定一条页签/小计公式 token 数组是否可落进单源 cross_tab_ref 模型。
     * 规则：不允许出现 ≥2 个 agg=NONE 的 cross_tab_ref（裸明细外部引用）在同一表达式里——
     * 这意味着多外部页签互相 row-key 对齐，token 模型无宿主锚点，不可映射。
     * 单源（含聚合）跨页签 + 任意本组件 field/number/operator 均可映射。
     */
    public Result validate(List<Map<String, Object>> expr) {
        long bareForeignDetails = expr.stream()
            .filter(t -> "cross_tab_ref".equals(t.get("type")))
            .filter(t -> "NONE".equals(String.valueOf(t.getOrDefault("agg", "NONE"))))
            .count();
        if (bareForeignDetails >= 2) {
            return new Result(false,
                "存在 2+ 个未聚合的跨页签明细引用（多外部页签互相对齐），" +
                "token 模型无宿主行锚点。此类复杂多表连表请改用 Excel 组件。");
        }
        return new Result(true, null);
    }
}
