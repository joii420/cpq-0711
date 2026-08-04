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
        return topoOrder(components, deps, Map.of());
    }

    /**
     * repair-0803 重载：成环时用<b>组件名称</b>渲染链路，替代原先直接打印 componentId 集合
     * （`[56c8a517-…, 74c0cede-…]` 对配置员不可读，见 FR-12 / AC-14）。
     *
     * <p>零破坏：两参签名 delegate 到此并传空映射 → 名称查不到时回落原 id 文案。
     *
     * @param nameById 组件标识 → 组件（页签）名称；缺失项按 id 原样显示
     */
    public static List<String> topoOrder(List<String> components, Map<String, Set<String>> deps,
                                         Map<String, String> nameById) {
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
            throw new BusinessException(400, "页签公式存在循环引用: " + renderCycleNames(cyc, deps, nameById));
        }
        return order;
    }

    /** 环成员 → 可读链路文案（优先按真实回路顺序 `A → B → A`；提不出路径则退化为名称列举）。 */
    private static String renderCycleNames(Set<String> members, Map<String, Set<String>> deps,
                                           Map<String, String> nameById) {
        List<String> path = findCyclePath(members, deps);
        if (path.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String m : members) names.add(displayName(m, nameById));
            return String.join(", ", names);
        }
        StringBuilder sb = new StringBuilder();
        for (String n : path) sb.append(displayName(n, nameById)).append(" → ");
        sb.append(displayName(path.get(0), nameById));   // 闭合回首节点
        return sb.toString();
    }

    private static String displayName(String id, Map<String, String> nameById) {
        String n = nameById == null ? null : nameById.get(id);
        return (n != null && !n.isBlank()) ? n : id;
    }

    /**
     * 在给定节点子集内找一条回路（沿 {@code deps} 的「依赖」边前进），返回按序节点；找不到返回空表。
     *
     * <p>供 {@link #topoOrder} 渲染可读文案与模板发布期产出结构化环链路共用（repair-0803）。
     * 节点规模 = 一张卡片的页签数（个位数），朴素 DFS 足够。
     */
    public static List<String> findCyclePath(Collection<String> members, Map<String, Set<String>> deps) {
        Set<String> scope = new LinkedHashSet<>(members);
        for (String start : scope) {
            Deque<String> stack = new ArrayDeque<>();
            Set<String> onPath = new LinkedHashSet<>();
            List<String> found = dfsCycle(start, start, deps, scope, onPath, stack);
            if (found != null && !found.isEmpty()) return found;
        }
        return List.of();
    }

    private static List<String> dfsCycle(String cur, String target, Map<String, Set<String>> deps,
                                         Set<String> scope, Set<String> onPath, Deque<String> stack) {
        onPath.add(cur);
        stack.addLast(cur);
        for (String next : deps.getOrDefault(cur, Set.of())) {
            if (!scope.contains(next)) continue;
            if (next.equals(target)) return new ArrayList<>(stack);          // 回到起点 → 成环
            if (onPath.contains(next)) continue;
            List<String> r = dfsCycle(next, target, deps, scope, onPath, stack);
            if (r != null && !r.isEmpty()) return r;
        }
        stack.removeLast();
        onPath.remove(cur);
        return List.of();
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
     * 否则其列小计尚未回填 → 引用列算成 0（QT-1743 管理费=0 根因）。
     * 调用方需把返回的 code/tabName 解析为 componentId 后再并入 deps（自引用由 B6 两阶段处理，调用方排除）。
     *
     * <p><b>注意</b>：本方法丢弃「引用的是哪一列」，按页签粒度看依赖，会把引用零依赖列
     * （INPUT_*）也算成顺序依赖，进而可能造出假环（repair-0803）。生产建图请改用
     * {@link #buildComponentDeps(List)}；本方法保留供既有调用方与单测使用。
     */
    public static Set<String> extractSubtotalRefs(JsonNode formulas) {
        Set<String> refs = new LinkedHashSet<>();
        for (SubtotalRef sr : extractSubtotalRefDetails(formulas)) refs.add(sr.ref());
        return refs;
    }

    // ── repair-0803：component_subtotal 依赖边的「列粒度」精化 ───────────────────

    /**
     * 建图输入：一个参与拓扑排序的页签。
     *
     * @param cid      组件标识（componentId 字符串，图中的节点键）
     * @param code     组件编码（可空；解析 component_subtotal 的 component_code）
     * @param tabName  页签名（可空；component_code 缺失时的回退解析键）
     * @param formulas 该页签 formulas 节点（[{expression:[token...]}]）
     * @param fields   该页签 fields 节点（判定被引用列是否顺序敏感）
     */
    public record TabDep(String cid, String code, String tabName, JsonNode formulas, JsonNode fields) {}

    /** component_subtotal 引用明细：目标页签标识 + 被引用列名 + 是否整页签合计。 */
    private record SubtotalRef(String ref, String column, boolean tabTotal) {}

    /**
     * 构建组件级依赖图（cross_tab_ref 全量 + component_subtotal 按列粒度精化）。
     *
     * <p><b>为什么要按列判定</b>（repair-0803 / QT-20260803-0052）：拓扑序只解决「谁先算」。
     * 一列的值是否取决于计算次序，取决于它<b>是不是公式列</b> ——
     * {@link FormulaCalculator} 的 {@code collectFormulaFields} 只对 {@code field_type=="FORMULA"}
     * 的字段求公式（{@code formula_assignments} 亦只在 FORMULA 列内部生效），其余列
     * （INPUT_NUMBER、BASIC_DATA、DATA_SOURCE…）的值在 PASS1 之前就已由 baseRows/editRows/driver 定死，
     * 无论页签谁先算都不会变。给这类引用建边，收益为零，却会凭空造出反向边：
     * 「产品.税率(INPUT) → 物料成本 → 产品.管理费(FORMULA)」这条列级直线依赖链，
     * 折成页签粒度后成了 产品⇄物料 闭环 → {@link #topoOrder} 误报循环引用 → 整卡渲染失败。
     *
     * <p><b>保守优先</b>：只有能确证被引用列非公式列时才省略边；整页签合计
     * （{@code is_tab_total} / {@code __amount_total__}）、列名缺失、目标 fields 不可读、
     * 查无此列——一律照旧建边（宁可多排一次序，不可算错值，守住 QT-1743 的修复）。
     *
     * @param tabs 参与拓扑的页签（仅 NORMAL；调用方需自行过滤 SUBTOTAL/EXCEL）
     * @return cid → 依赖的 cid 集合（可直接喂 {@link #topoOrder}）
     */
    public static Map<String, Set<String>> buildComponentDeps(List<TabDep> tabs) {
        Map<String, String> refToCid = new HashMap<>();
        Map<String, JsonNode> fieldsByCid = new HashMap<>();
        for (TabDep t : tabs) {
            String cid = t.cid();
            if (cid == null || cid.isBlank()) continue;
            refToCid.put(cid, cid);
            if (t.code() != null && !t.code().isBlank()) refToCid.put(t.code(), cid);
            if (t.tabName() != null && !t.tabName().isBlank()) refToCid.put(t.tabName(), cid);
            fieldsByCid.put(cid, t.fields());
        }
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (TabDep t : tabs) {
            // cross_tab_ref：按行取源组件已算行，恒为顺序依赖，不做列粒度豁免
            Set<String> d = new LinkedHashSet<>(extractSourceRefs(t.formulas()));
            for (SubtotalRef sr : extractSubtotalRefDetails(t.formulas())) {
                String tcid = refToCid.get(sr.ref());
                // 卡片外引用（解析不到）不入图；自引用由引擎内两阶段处理（B6），不建边
                if (tcid == null || tcid.equals(t.cid())) continue;
                if (isOrderSensitiveColumn(fieldsByCid.get(tcid), sr)) d.add(tcid);
            }
            deps.put(t.cid(), d);
        }
        return deps;
    }

    /** 被引用列的值是否取决于页签计算次序（即是否需要为它建依赖边）。 */
    private static boolean isOrderSensitiveColumn(JsonNode targetFields, SubtotalRef sr) {
        if (sr.tabTotal()) return true;                                 // 整页签合计含全部公式列
        if (sr.column() == null || sr.column().isBlank()) return true;  // 列名缺失 → 保守
        if (targetFields == null || !targetFields.isArray()) return true;  // fields 不可读 → 保守
        for (JsonNode f : targetFields) {
            if (!sr.column().equals(fieldNameOf(f))) continue;
            return isFormulaType(fieldTypeOf(f));
        }
        return true;                                                    // 查无此列 → 保守
    }

    /** 公式类字段（FORMULA 及未来的 *_FORMULA 变体，如 LIST_FORMULA）→ 值由公式产生，顺序敏感。 */
    private static boolean isFormulaType(String fieldType) {
        return "FORMULA".equals(fieldType) || fieldType.endsWith("_FORMULA");
    }

    /** 兼容 snake_case（component.fields）与 camelCase（快照 structure），对齐 FormulaCalculator。 */
    private static String fieldTypeOf(JsonNode f) {
        if (f.has("fieldType")) return f.path("fieldType").asText("");
        return f.path("field_type").asText("");
    }

    private static String fieldNameOf(JsonNode f) {
        String n = f.path("name").asText("");
        return !n.isEmpty() ? n : f.path("key").asText("");
    }

    /** 扫描 formulas，收集 component_subtotal 引用明细（目标标识 + 列名 + 是否整页签合计）。 */
    private static List<SubtotalRef> extractSubtotalRefDetails(JsonNode formulas) {
        List<SubtotalRef> out = new ArrayList<>();
        if (formulas == null || !formulas.isArray()) return out;
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                if (!"component_subtotal".equals(tk.path("type").asText(""))) continue;
                String code = tk.path("component_code").asText("");
                String tab = tk.path("tab_name").asText("");
                String ref = !code.isBlank() ? code : tab;
                if (ref.isBlank()) continue;
                String column = tk.path("value").asText("");
                boolean tabTotal = tk.path("is_tab_total").asBoolean(false)
                    || "__amount_total__".equals(column);
                out.add(new SubtotalRef(ref, column, tabTotal));
            }
        }
        return out;
    }
}
