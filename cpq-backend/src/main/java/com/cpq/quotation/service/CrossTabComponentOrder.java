package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 组件级 cross_tab_ref 依赖拓扑排序（Kahn）+ 环检测 + 源组件依赖提取。 */
public final class CrossTabComponentOrder {
    private CrossTabComponentOrder() {}

    /**
     * @param components 组件标识列表（按出现顺序）
     * @param deps       组件标识 → 它依赖的源组件标识集合（仅 components 内的计入入度）
     * @return 拓扑序（保留输入相对序）；存在环抛 BusinessException(400)
     */
    public static List<String> topoOrder(List<String> components, Map<String, Set<String>> deps) {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String c : components) indeg.put(c, 0);
        for (String c : components) {
            for (String d : deps.getOrDefault(c, Set.of())) {
                if (indeg.containsKey(d)) indeg.merge(c, 1, Integer::sum);
            }
        }
        Deque<String> q = new ArrayDeque<>();
        for (String c : components) if (indeg.get(c) == 0) q.add(c);
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            order.add(c);
            for (String other : components) {
                if (deps.getOrDefault(other, Set.of()).contains(c)) {
                    indeg.put(other, indeg.get(other) - 1);
                    if (indeg.get(other) == 0) q.add(other);
                }
            }
        }
        if (order.size() != components.size()) {
            Set<String> cyc = new LinkedHashSet<>(components);
            cyc.removeAll(order);
            throw new BusinessException(400, "页签公式存在循环引用: " + cyc);
        }
        return order;
    }

    /** 扫描一个组件 formulas 节点（[{expression:[token...]}]），收集所有 cross_tab_ref 的 source。 */
    public static Set<String> extractSourceRefs(JsonNode formulas) {
        Set<String> refs = new LinkedHashSet<>();
        if (formulas == null || !formulas.isArray()) return refs;
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                if ("cross_tab_ref".equals(tk.path("type").asText(""))) {
                    String s = tk.path("source").asText("");
                    if (!s.isBlank()) refs.add(s);
                }
            }
        }
        return refs;
    }

    /**
     * 收集所有 component_subtotal 跨组件引用的目标标识（component_code 优先，否则 tab_name）。
     * 用于把"本组件公式引用别组件列小计"也纳入拓扑依赖 —— 被引用组件必须先算，
     * 否则其列小计尚未回填 → 引用列算成 0（QT-1743 管理费=0 根因，与前端 extractSubtotalRefs 对齐）。
     * 调用方需把返回的 code/tabName 解析为 componentId 后再并入 deps（自引用由 B6 两阶段处理，调用方排除）。
     */
    public static Set<String> extractSubtotalRefs(JsonNode formulas) {
        Set<String> refs = new LinkedHashSet<>();
        if (formulas == null || !formulas.isArray()) return refs;
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                if ("component_subtotal".equals(tk.path("type").asText(""))) {
                    String code = tk.path("component_code").asText("");
                    String tab = tk.path("tab_name").asText("");
                    if (!code.isBlank()) refs.add(code);
                    else if (!tab.isBlank()) refs.add(tab);
                }
            }
        }
        return refs;
    }
}
