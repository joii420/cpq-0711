package com.cpq.quotation.service.formula;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * task-0803：BOM 树页签的父子关系（按 baseRows 下标表达，纯计算，可直接单测）。
 *
 * <p>🚨 <b>父子边一律按 {@code __nodeId} / {@code __parentId} 认，禁止按料号</b>：
 * BOM 是 DAG，同一料号会挂在多个父件下（现网实例 {@code 3110520789} 同挂
 * {@code 2120011658} / {@code 2120011659} 两个父件），按料号认边必然串号，
 * 把两个父件的子树混成一坨。
 *
 * <p><b>墓碑行</b>（报价侧 {@code deleted_tree_nodes} 命中）既不作子、也不作父：
 * 子全被删则父变叶子，父被删则子视为无父（需求 §4.3.1 推论）。
 *
 * <p><b>系统列位置</b>：{@code __nodeId} / {@code __parentId} / {@code __lvl} 在 baseRow
 * <b>顶层</b>，不在 {@code driverRow} 内（由 {@code BomTreeRenderService.treeRowNode} 写入）。
 */
public final class TreeRelations {

    private final int size;
    private final int[] parent;                  // -1 = 无父
    private final List<List<Integer>> children;
    private final int[] lvl;
    private final boolean[] alive;

    private TreeRelations(int size, int[] parent, List<List<Integer>> children,
                          int[] lvl, boolean[] alive) {
        this.size = size;
        this.parent = parent;
        this.children = children;
        this.lvl = lvl;
        this.alive = alive;
    }

    /** baseRows 里任意一行带非空 {@code __nodeId} → 视为树页签行集。 */
    public static boolean isTreeRows(JsonNode baseRows) {
        if (baseRows == null || !baseRows.isArray()) return false;
        for (JsonNode r : baseRows) {
            if (text(r, "__nodeId") != null) return true;
        }
        return false;
    }

    /**
     * @param baseRows       完整 baseRows（<b>不要</b>预先过滤墓碑，本类按 deletedNodeIds 自行处理，
     *                       以保证下标与调用方的 baseRows 一一对应）
     * @param deletedNodeIds 墓碑节点 id 集合；{@code null}/空 = 不过滤
     */
    public static TreeRelations of(JsonNode baseRows, Set<String> deletedNodeIds) {
        int n = (baseRows != null && baseRows.isArray()) ? baseRows.size() : 0;
        Set<String> dead = deletedNodeIds != null ? deletedNodeIds : Set.of();

        String[] nodeIds = new String[n];
        String[] parentIds = new String[n];
        int[] lvl = new int[n];
        boolean[] alive = new boolean[n];
        Map<String, Integer> byNodeId = new HashMap<>();

        for (int i = 0; i < n; i++) {
            JsonNode r = baseRows.get(i);
            nodeIds[i] = text(r, "__nodeId");
            parentIds[i] = text(r, "__parentId");
            JsonNode l = r == null ? null : r.get("__lvl");
            lvl[i] = (l != null && l.isNumber()) ? l.asInt() : 0;
            alive[i] = nodeIds[i] != null && !dead.contains(nodeIds[i]);
            if (nodeIds[i] != null) byNodeId.put(nodeIds[i], i);
        }

        int[] parent = new int[n];
        List<List<Integer>> children = new ArrayList<>(n);
        for (int i = 0; i < n; i++) children.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            parent[i] = -1;
            if (!alive[i] || parentIds[i] == null) continue;
            Integer p = byNodeId.get(parentIds[i]);
            if (p == null || !alive[p]) continue;   // 父不存在或已墓碑 → 视为无父
            parent[i] = p;
            children.get(p).add(i);
        }
        return new TreeRelations(n, parent, children, lvl, alive);
    }

    /** 取 baseRow 顶层字符串系统列；缺失/null/空串统一返回 {@code null}。 */
    private static String text(JsonNode row, String key) {
        JsonNode v = row == null ? null : row.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    public int size() {
        return size;
    }

    /** 该行是否存活（有 nodeId 且未被墓碑）。 */
    public boolean alive(int i) {
        return i >= 0 && i < size && alive[i];
    }

    /** 直接父行下标；无父（根行 / 父已墓碑 / 下标越界）→ {@code -1}。 */
    public int parentOf(int i) {
        return (i >= 0 && i < size) ? parent[i] : -1;
    }

    /** 直接子行下标（<b>不含孙辈</b>，已排除墓碑）。 */
    public List<Integer> childrenOf(int i) {
        return (i >= 0 && i < size) ? children.get(i) : List.of();
    }

    /**
     * 该行的树层级，原样透传 baseRow 的 {@code __lvl}。
     *
     * <p>🚨 <b>根节点的 lvl 是 1，不是 0</b> —— 系统既有约定，源头在
     * {@code CostingTreeGrouping:38}（根硬编码 {@code nd.lvl = 1}），子孙按 {@code node_path}
     * 里的 {@code /} 数递增。2026-08-03 端到端实测确认；本方法不做任何归一化，
     * 改动前请先确认上游约定是否变化。
     */
    public int lvl(int i) {
        return (i >= 0 && i < size) ? lvl[i] : 0;
    }

    public boolean isRoot(int i) {
        return parentOf(i) < 0;
    }

    public boolean isLeaf(int i) {
        return childrenOf(i).isEmpty();
    }
}
