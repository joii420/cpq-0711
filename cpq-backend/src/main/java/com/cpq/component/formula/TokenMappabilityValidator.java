package com.cpq.component.formula;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TokenMappabilityValidator {

    public record Result(boolean mappable, String reason) {}

    /**
     * 判定一条页签/小计公式 token 数组是否可落进宿主行键分组模型（v4-C 命门1）。
     *
     * <p>规则（新）：任何 cross_tab_ref 的 {@code match} 为空列表（或缺失）→ 拒绝。
     * 空 match 表示无公共行键约束，聚合退化为全源表，NONE 则会静默广播全表 —— 两者均不可映射。
     * {@code component_subtotal}（无 cross_tab_ref token）不受影响。
     *
     * <p>旧规则"≥2 个 agg=NONE 即拒"已废弃（v4-C 前）：只要 match 非空，多个 NONE 可按行键对齐，合法。
     *
     * <p>单源（含聚合）跨页签 + 任意本组件 field/number/operator，match 非空时均可映射。
     */
    public Result validate(List<Map<String, Object>> expr) {
        for (Map<String, Object> t : expr) {
            if (!"cross_tab_ref".equals(t.get("type"))) continue;
            Object m = t.get("match");
            boolean emptyMatch = !(m instanceof List) || ((List<?>) m).isEmpty();
            if (emptyMatch) {
                return new Result(false,
                    "存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。" +
                    "请改引可比页签或用其整页签小计 [页签(总计)]。");
            }
        }
        return new Result(true, null);
    }
}
