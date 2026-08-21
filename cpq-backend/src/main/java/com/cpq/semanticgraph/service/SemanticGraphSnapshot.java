package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义图的不可变内存快照（task-260819 B-2）。
 *
 * <p>启动时全量加载一次；保存成功后整体换引用（{@link SemanticGraphLoader#reload()}），
 * **不原地改**——本项目已在 expand/公式层实证过「共享可变对象引用 + 并发」的竞态并于
 * 2026-06-22 回滚过一次（`docs/RECORD.md`），本类所有集合均在构造时冻结为不可变视图。
 */
public final class SemanticGraphSnapshot {

    public final int version;
    public final LocalDateTime loadedAt;

    public final List<SemanticNode> nodes;
    public final List<SemanticNodeColumn> nodeColumns;
    public final List<SemanticEdge> edges;
    public final List<SemanticEdgeKey> edgeKeys;
    public final List<SemanticTabView> tabViews;
    public final List<SemanticTabViewNode> tabViewNodes;
    public final List<SemanticTabViewColumn> tabViewColumns;

    // ---- 派生索引（构造时一次性建好，供 O(1)/O(edge数) 查找，不在读路径里查库）----
    public final Map<UUID, SemanticNode> nodeById;
    public final Map<String, SemanticNode> nodeByKeyDialect; // "<nodeKey>|<dialect>"
    public final Map<UUID, List<SemanticNodeColumn>> columnsByNode;
    public final Map<UUID, List<SemanticEdge>> edgesFrom;
    public final Map<UUID, List<SemanticEdgeKey>> keysByEdge;
    public final Map<UUID, List<SemanticTabViewNode>> tabViewNodesByView;
    public final Map<UUID, List<SemanticTabViewColumn>> tabViewColumnsByView;

    public SemanticGraphSnapshot(int version,
                                  List<SemanticNode> nodes,
                                  List<SemanticNodeColumn> nodeColumns,
                                  List<SemanticEdge> edges,
                                  List<SemanticEdgeKey> edgeKeys,
                                  List<SemanticTabView> tabViews,
                                  List<SemanticTabViewNode> tabViewNodes,
                                  List<SemanticTabViewColumn> tabViewColumns) {
        this.version = version;
        this.loadedAt = LocalDateTime.now();
        this.nodes = List.copyOf(nodes);
        this.nodeColumns = List.copyOf(nodeColumns);
        this.edges = List.copyOf(edges);
        this.edgeKeys = List.copyOf(edgeKeys);
        this.tabViews = List.copyOf(tabViews);
        this.tabViewNodes = List.copyOf(tabViewNodes);
        this.tabViewColumns = List.copyOf(tabViewColumns);

        Map<UUID, SemanticNode> byId = new HashMap<>();
        Map<String, SemanticNode> byKeyDialect = new HashMap<>();
        for (SemanticNode n : this.nodes) {
            byId.put(n.id, n);
            byKeyDialect.put(n.nodeKey + "|" + n.dialect, n);
        }
        this.nodeById = Collections.unmodifiableMap(byId);
        this.nodeByKeyDialect = Collections.unmodifiableMap(byKeyDialect);

        this.columnsByNode = Collections.unmodifiableMap(
                this.nodeColumns.stream().collect(Collectors.groupingBy(c -> c.nodeId)));
        this.edgesFrom = Collections.unmodifiableMap(
                this.edges.stream().collect(Collectors.groupingBy(e -> e.fromNodeId)));
        this.keysByEdge = Collections.unmodifiableMap(
                this.edgeKeys.stream().collect(Collectors.groupingBy(k -> k.edgeId)));
        this.tabViewNodesByView = Collections.unmodifiableMap(
                this.tabViewNodes.stream().collect(Collectors.groupingBy(n -> n.viewId)));
        this.tabViewColumnsByView = Collections.unmodifiableMap(
                this.tabViewColumns.stream().collect(Collectors.groupingBy(c -> c.viewId)));
    }

    public List<SemanticNodeColumn> columnsOf(UUID nodeId) {
        return columnsByNode.getOrDefault(nodeId, List.of());
    }

    public List<SemanticEdge> edgesFrom(UUID nodeId) {
        return edgesFrom.getOrDefault(nodeId, List.of());
    }

    public List<SemanticEdgeKey> keysOf(UUID edgeId) {
        return keysByEdge.getOrDefault(edgeId, List.of());
    }
}
