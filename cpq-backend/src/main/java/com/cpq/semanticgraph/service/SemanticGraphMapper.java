package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.dto.SemanticGraphDTOs.*;
import com.cpq.semanticgraph.entity.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link SemanticGraphSnapshot}（不可变内存图）→ 读响应 DTO 的纯内存转换（task-260819 B-4）。
 *
 * <p>N+1 自检：本类所有方法只读传入的 {@link SemanticGraphSnapshot} 内存索引，不查库；
 * GET {@code /config/semantic-graph} 端点因此是 0 次额外 SQL（快照已在应用启动/保存时加载好）。
 */
public final class SemanticGraphMapper {

    private SemanticGraphMapper() {}

    public static GraphDTO toGraphDTO(SemanticGraphSnapshot snap, int graphVersion) {
        GraphDTO dto = new GraphDTO();
        dto.graphVersion = graphVersion;
        dto.updatedAt = snap.loadedAt;
        dto.updatedBy = null;

        // usedBy：node -> ["页签 · 角色"] 列表（一次遍历 tabViewNodes 建 map，O(边数+页签数)，不循环查库）
        Map<UUID, List<String>> usedByMap = new HashMap<>();
        Map<UUID, SemanticTabView> tvById = snap.tabViews.stream().collect(Collectors.toMap(t -> t.id, t -> t));
        for (SemanticTabViewNode tvn : snap.tabViewNodes) {
            SemanticTabView tv = tvById.get(tvn.viewId);
            if (tv == null) continue;
            String label = tv.tabType + ("MAIN".equals(tvn.role) ? " · 主源" : "");
            usedByMap.computeIfAbsent(tvn.nodeId, k -> new ArrayList<>()).add(label);
        }
        dto.nodes = snap.nodes.stream().map(n -> toNodeDTO(snap, n, usedByMap)).collect(Collectors.toList());
        dto.edges = snap.edges.stream().map(e -> toEdgeDTO(snap, e)).collect(Collectors.toList());
        dto.tabViews = snap.tabViews.stream().map(tv -> toTabViewDTO(snap, tv)).collect(Collectors.toList());
        return dto;
    }

    public static NodeDTO toNodeDTO(SemanticGraphSnapshot snap, SemanticNode n, Map<UUID, List<String>> usedByMap) {
        NodeDTO dto = new NodeDTO();
        dto.id = n.id;
        dto.nodeKey = n.nodeKey;
        dto.displayName = n.displayName;
        dto.shortName = n.shortName;
        dto.nodeKind = n.nodeKind;
        dto.physicalTable = n.physicalTable;
        dto.scope = n.scope;
        dto.anchorExpr = n.anchorExpr;
        dto.grainColumns = List.of(n.grainColumns);
        dto.discriminator = n.discriminator;
        dto.fixedPredicate = n.fixedPredicate;
        dto.funcSignature = n.funcSignature;
        dto.dialect = n.dialect;
        dto.sourceHandler = n.sourceHandler;
        dto.columns = snap.columnsOf(n.id).stream().map(SemanticGraphMapper::toColumnDTO).collect(Collectors.toList());
        List<String> usedBy = usedByMap.getOrDefault(n.id, List.of());
        dto.usedBy = usedBy;
        dto.orphanReason = ("SHEET".equals(n.nodeKind) && usedBy.isEmpty())
                ? (n.note != null ? n.note : "未挂任何页签视图")
                : null;
        return dto;
    }

    public static ColumnDTO toColumnDTO(SemanticNodeColumn c) {
        ColumnDTO dto = new ColumnDTO();
        dto.id = c.id;
        dto.dbColumn = c.dbColumn;
        dto.displayName = c.displayName;
        dto.dataType = c.dataType;
        dto.isCode = c.isCode;
        dto.roles = List.of(c.roles);
        dto.sortOrder = c.sortOrder;
        return dto;
    }

    public static EdgeDTO toEdgeDTO(SemanticGraphSnapshot snap, SemanticEdge e) {
        EdgeDTO dto = new EdgeDTO();
        dto.id = e.id;
        dto.fromNodeId = e.fromNodeId;
        dto.toNodeId = e.toNodeId;
        dto.edgeKind = e.edgeKind;
        dto.cardinality = e.cardinality;
        dto.keys = snap.keysOf(e.id).stream().sorted(Comparator.comparingInt(k -> k.seq)).map(k -> {
            EdgeKeyDTO kd = new EdgeKeyDTO();
            kd.seq = k.seq;
            kd.leftColumn = k.leftColumn;
            kd.rightColumn = k.rightColumn;
            return kd;
        }).collect(Collectors.toList());
        dto.fallbackOrder = e.fallbackOrder;
        dto.coalesceGroup = e.coalesceGroup;
        dto.assertStatus = e.assertStatus;
        dto.assertSampleRows = e.assertSampleRows;
        Set<String> tabTypes = new LinkedHashSet<>();
        Map<UUID, SemanticTabView> tvById = snap.tabViews.stream().collect(Collectors.toMap(t -> t.id, t -> t));
        for (SemanticTabViewNode tvn : snap.tabViewNodes) {
            if (tvn.nodeId.equals(e.fromNodeId)) {
                SemanticTabView tv = tvById.get(tvn.viewId);
                if (tv != null) tabTypes.add(tv.tabType);
            }
        }
        dto.usedByTabs = new ArrayList<>(tabTypes);
        return dto;
    }

    public static TabViewDTO toTabViewDTO(SemanticGraphSnapshot snap, SemanticTabView tv) {
        TabViewDTO dto = new TabViewDTO();
        dto.id = tv.id;
        dto.tabType = tv.tabType;
        dto.variantKey = tv.variantKey;
        dto.variantLabel = tv.variantLabel;
        dto.anchorNodeId = tv.anchorNodeId;
        dto.switches = List.of(tv.switches);
        dto.dialect = tv.dialect;
        dto.nodes = snap.tabViewNodesByView.getOrDefault(tv.id, List.of()).stream()
                .sorted(Comparator.comparingInt(n -> n.sortOrder))
                .map(n -> {
                    TabViewNodeDTO nd = new TabViewNodeDTO();
                    nd.nodeId = n.nodeId;
                    nd.role = n.role;
                    nd.addDims = List.of(n.addDims);
                    return nd;
                }).collect(Collectors.toList());
        dto.columnRoles = snap.tabViewColumnsByView.getOrDefault(tv.id, List.of()).stream()
                .sorted(Comparator.comparingInt(c -> c.sortOrder))
                .map(c -> {
                    TabViewColumnRoleDTO cd = new TabViewColumnRoleDTO();
                    cd.columnId = c.columnId;
                    cd.roles = List.of(c.roles);
                    return cd;
                }).collect(Collectors.toList());
        return dto;
    }
}
