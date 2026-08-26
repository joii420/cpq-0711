package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 字段树（task-260819 B-7/B-8，api.md §1.4）—— 配置器左侧字段面板的数据源。
 *
 * <p>2026-08-21 裁决：形状是 {@code {groups:[...]}}（按 Sheet 分组），不是扁平 {@code List<NodeDTO>}
 * （见 api.md §1.4 的裁决说明）。本类替换 {@code SemanticGraphService.getFieldTree} 原来的扁平实现。
 *
 * <p>N+1 自检：全部基于传入的不可变 {@link SemanticGraphSnapshot} 内存索引遍历，不查库。
 */
@ApplicationScoped
public class FieldTreeBuilder {

    public static final class Field {
        public String sourceNodeKey;
        public String sourceColumn;
        public String displayName;
        public String dataType;
        public List<String> roles;
        public String viewColumn;
        public String lookupLib;
        public boolean isCore;
        /** true = 价格策略元素符号列（左键，B-24/D-65）——前端据此在拖入价格策略列时自动带出本列。 */
        public boolean elemKey;
    }

    public static final class Group {
        public String groupKey;
        public String groupName;
        public String groupKind;
        public List<String> dims;
        public boolean conflict;
        public String conflictReason;
        public List<Field> fields;
    }

    public static final class FieldTreeResponse {
        public String tabType;
        public String variantKey;
        public String anchorDesc;
        public List<String> availableTabTypes;
        public List<Map<String, String>> variants;
        public List<String> switches;
        public List<Group> groups;
    }

    /**
     * @param selectedConfig 当前已选列（builder_config.columns），null/空 = 不计算 conflict（恒 false）
     */
    public FieldTreeResponse build(SemanticGraphSnapshot snap, String tabType, String variantKey,
                                    List<BuilderConfig.ColumnConfig> selectedConfig) {
        String vk = variantKey == null ? "" : variantKey;
        SemanticTabView tv = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(tabType) && t.variantKey.equals(vk))
                .findFirst()
                .orElseThrow(() -> new BuilderApiException(404, "COMPILE_TABVIEW_NOT_FOUND",
                        "未找到页签视图: " + tabType + "/" + vk, Map.of()));
        SemanticNode anchor = snap.nodeById.get(tv.anchorNodeId);

        FieldTreeResponse resp = new FieldTreeResponse();
        resp.tabType = tabType;
        resp.variantKey = vk;
        resp.anchorDesc = anchor != null ? anchor.displayName : null;
        resp.availableTabTypes = List.of("主件", "材质元素", "零件", "外购件", "费用类", "BOM 树");
        resp.variants = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(tabType) && t.variantLabel != null)
                .map(t -> Map.of("key", t.variantKey, "label", t.variantLabel))
                .collect(Collectors.toList());
        resp.switches = List.of(tv.switches);

        // 已选列所在的 GRAIN 组（用于 conflict 判定，B-8）
        Set<String> selectedGrainNodeKeys = new HashSet<>();
        if (selectedConfig != null) {
            for (BuilderConfig.ColumnConfig col : selectedConfig) {
                SemanticNode n = snap.nodeByKeyDialect.get(col.sourceNodeKey + "|QUOTE");
                if (n == null) continue;
                boolean isGrainTarget = snap.edgesFrom(anchor.id).stream()
                        .anyMatch(e -> "GRAIN".equals(e.edgeKind) && e.toNodeId.equals(n.id));
                if (isGrainTarget) selectedGrainNodeKeys.add(n.nodeKey);
            }
        }

        Map<UUID, List<SemanticTabViewColumn>> overrideByColumn = snap.tabViewColumnsByView
                .getOrDefault(tv.id, List.of()).stream().collect(Collectors.groupingBy(c -> c.columnId));

        // 价格策略边（若锚点声明了一条）：key[0].leftColumn 是锚点自己的"元素符号"列（B-24/D-65）。
        SemanticEdge priceEdge = snap.edgesFrom(anchor.id).stream()
                .filter(e -> "PRICE".equals(e.edgeKind)).findFirst().orElse(null);
        String elemKeySourceColumn = null;
        if (priceEdge != null) {
            List<SemanticEdgeKey> priceKeys = snap.keysOf(priceEdge.id).stream()
                    .sorted(Comparator.comparingInt(k -> k.seq)).toList();
            if (!priceKeys.isEmpty()) elemKeySourceColumn = priceKeys.get(0).leftColumn;
        }

        List<Group> groups = new ArrayList<>();
        List<SemanticTabViewNode> tvns = snap.tabViewNodesByView.getOrDefault(tv.id, List.of());
        for (SemanticTabViewNode tvn : tvns) {
            SemanticNode node = snap.nodeById.get(tvn.nodeId);
            if (node == null) continue;
            boolean isMain = "MAIN".equals(tvn.role);

            Group g = new Group();
            g.groupKey = node.nodeKey;
            g.groupName = node.displayName;
            g.dims = new ArrayList<>(List.of(tvn.addDims));

            SemanticEdge edgeFromAnchor = isMain ? null : snap.edgesFrom(anchor.id).stream()
                    .filter(e -> e.toNodeId.equals(node.id)).findFirst().orElse(null);
            g.groupKind = isMain ? "MAIN" : (edgeFromAnchor != null ? edgeFromAnchor.edgeKind : "AUX");

            if (!isMain && "GRAIN".equals(g.groupKind)) {
                if (g.dims.isEmpty()) g.dims = new ArrayList<>(List.of(node.grainColumns));
                boolean conflictsWithOther = !selectedGrainNodeKeys.isEmpty()
                        && !selectedGrainNodeKeys.contains(node.nodeKey);
                g.conflict = conflictsWithOther;
                g.conflictReason = conflictsWithOther
                        ? "与已选的『按 " + String.join("/", g.dims) + " 展开』冲突 —— 两者只能选一类。要改用本组，请先移除那一组的列"
                        : null;
            } else {
                g.conflict = false;
                g.conflictReason = null;
            }

            List<Field> fields = new ArrayList<>();
            for (SemanticNodeColumn col : snap.columnsOf(node.id)) {
                Field f = new Field();
                f.sourceNodeKey = node.nodeKey;
                f.sourceColumn = col.dbColumn;
                f.displayName = col.displayName;
                f.dataType = col.dataType;
                f.roles = mergedRoles(col, overrideByColumn.get(col.id));
                f.viewColumn = AliasGenerator.quoteViewColumn(node.shortName, col.displayName);
                f.lookupLib = null;
                f.isCore = false;
                f.elemKey = isMain && elemKeySourceColumn != null && elemKeySourceColumn.equals(col.dbColumn);
                fields.add(f);
            }

            if (isMain) {
                fields.addAll(syntheticLookupFields(snap, anchor));
            }
            g.fields = fields;
            groups.add(g);
        }

        // 价格策略原子组（B-24/D-65）：单独成组返回，groupKind='PRICE' —— 前端靠它渲染成带框块
        // 并在拖入时自动带出上面标了 elemKey 的元素符号列。元素键列本身仍留在 MAIN 组（不移动）。
        if (priceEdge != null) {
            SemanticNode funcNode = snap.nodeById.get(priceEdge.toNodeId);
            if (funcNode != null) {
                Group priceGroup = new Group();
                priceGroup.groupKey = funcNode.nodeKey;
                priceGroup.groupName = "价格策略";
                priceGroup.groupKind = "PRICE";
                priceGroup.dims = new ArrayList<>();
                priceGroup.conflict = false;
                priceGroup.conflictReason = null;
                List<Field> priceFields = new ArrayList<>();
                for (SemanticNodeColumn col : snap.columnsOf(funcNode.id)) {
                    Field f = new Field();
                    f.sourceNodeKey = funcNode.nodeKey;
                    f.sourceColumn = col.dbColumn;
                    f.displayName = col.displayName;
                    f.dataType = col.dataType;
                    f.roles = List.of(col.roles);
                    f.viewColumn = AliasGenerator.bareColumn(col.displayName);
                    f.lookupLib = "价格策略";
                    f.isCore = "unit_price".equals(col.dbColumn);
                    f.elemKey = false;
                    priceFields.add(f);
                }
                priceGroup.fields = priceFields;
                groups.add(priceGroup);
            }
        }
        resp.groups = groups;
        return resp;
    }

    private List<String> mergedRoles(SemanticNodeColumn col, List<SemanticTabViewColumn> overrides) {
        if (overrides != null && !overrides.isEmpty()) return List.of(overrides.get(0).roles);
        return List.of(col.roles);
    }

    /**
     * 经 LOOKUP 边到达的"虚拟字段"（查名结果），挂在锚点自己的组下展示。
     * 🔴 B-24/D-65：PRICE 边不再在这里合并进 MAIN 组——已改为在 {@link #build} 里单独成
     * {@code groupKind='PRICE'} 的组返回，避免与新逻辑重复输出「元素单价/货币」两份。
     */
    private List<Field> syntheticLookupFields(SemanticGraphSnapshot snap, SemanticNode anchor) {
        List<Field> out = new ArrayList<>();
        Set<String> handledGroups = new HashSet<>();
        for (SemanticEdge e : snap.edgesFrom(anchor.id)) {
            SemanticNode target = snap.nodeById.get(e.toNodeId);
            if (target == null) continue;
            if ("LOOKUP".equals(e.edgeKind)) {
                if (e.coalesceGroup != null) {
                    if (!handledGroups.add(e.coalesceGroup)) continue; // 同组只出现一次（用 fallback=0 的列代表）
                    SemanticEdge lead = snap.edgesFrom(anchor.id).stream()
                            .filter(x -> e.coalesceGroup.equals(x.coalesceGroup))
                            .min(Comparator.comparingInt(x -> x.fallbackOrder == null ? 0 : x.fallbackOrder))
                            .orElse(e);
                    SemanticNode leadNode = snap.nodeById.get(lead.toNodeId);
                    for (SemanticNodeColumn col : snap.columnsOf(leadNode.id)) {
                        if (!col.isCode) out.add(lookupField(leadNode, col));
                    }
                } else {
                    for (SemanticNodeColumn col : snap.columnsOf(target.id)) {
                        if (!col.isCode) out.add(lookupField(target, col));
                    }
                }
            }
        }
        return out;
    }

    private Field lookupField(SemanticNode lookupNode, SemanticNodeColumn col) {
        Field f = new Field();
        f.sourceNodeKey = lookupNode.nodeKey;
        f.sourceColumn = col.dbColumn;
        f.displayName = col.displayName;
        f.dataType = col.dataType;
        f.roles = List.of(col.roles);
        f.viewColumn = AliasGenerator.quoteViewColumn(lookupNode.shortName, col.displayName);
        f.lookupLib = lookupNode.displayName;
        f.isCore = false;
        return f;
    }
}
