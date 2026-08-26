package com.cpq.semanticgraph.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 语义图 REST 契约 DTO（task-260819 B-4），字段与 api.md §1.1/§1.2 逐字对应。
 * 全部用嵌套静态类收纳在一个文件里，避免为每个小 DTO 单开一个文件增加维护面。
 */
public final class SemanticGraphDTOs {
    private SemanticGraphDTOs() {}

    public static class GraphDTO {
        public int graphVersion;
        public LocalDateTime updatedAt;
        public String updatedBy;
        public List<NodeDTO> nodes;
        public List<EdgeDTO> edges;
        public List<TabViewDTO> tabViews;
    }

    public static class ColumnDTO {
        public UUID id;
        public String dbColumn;
        public String displayName;
        public String dataType;
        public boolean isCode;
        public List<String> roles;
        public int sortOrder;
    }

    public static class NodeDTO {
        public UUID id;
        public String nodeKey;
        public String displayName;
        public String shortName;
        public String nodeKind;
        public String physicalTable;
        public String scope;
        public String anchorExpr;
        public List<String> grainColumns;
        public String discriminator;
        public String fixedPredicate;
        public String funcSignature;
        public String dialect;
        public String sourceHandler;
        public List<ColumnDTO> columns;
        public List<String> usedBy;
        public String orphanReason;
    }

    public static class EdgeKeyDTO {
        public int seq;
        public String leftColumn;
        public String rightColumn;
    }

    public static class EdgeDTO {
        public UUID id;
        public UUID fromNodeId;
        public UUID toNodeId;
        public String edgeKind;
        public String cardinality;
        public List<EdgeKeyDTO> keys;
        public Integer fallbackOrder;
        public String coalesceGroup;
        public String assertStatus;
        public Long assertSampleRows;
        public List<String> usedByTabs;
    }

    public static class TabViewNodeDTO {
        public UUID nodeId;
        public String role;
        public List<String> addDims;
    }

    public static class TabViewColumnRoleDTO {
        public UUID columnId;
        public List<String> roles;
    }

    public static class TabViewDTO {
        public UUID id;
        public String tabType;
        public String variantKey;
        public String variantLabel;
        public UUID anchorNodeId;
        public List<String> switches;
        public String dialect;
        public List<TabViewNodeDTO> nodes;
        public List<TabViewColumnRoleDTO> columnRoles;
    }

    // ---- 写请求体（与读响应片段同形，见 api.md §1.2）----

    public static class NodeUpsertRequest {
        public String nodeKey;
        public String displayName;
        public String shortName;
        public String nodeKind;
        public String physicalTable;
        public String scope;
        public String anchorExpr;
        public List<String> grainColumns;
        public String discriminator;
        public String fixedPredicate;
        public String funcSignature;
        public String dialect;
        public String sourceHandler;
        public String note;
        public List<ColumnUpsertRequest> columns;
    }

    public static class ColumnUpsertRequest {
        public UUID id; // null = 新增
        public String dbColumn;
        public String displayName;
        public String dataType;
        public boolean isCode;
        public List<String> roles;
        public int sortOrder;
    }

    public static class EdgeUpsertRequest {
        public UUID fromNodeId;
        public UUID toNodeId;
        public String edgeKind;
        public String cardinality;
        public Integer fallbackOrder;
        public String coalesceGroup;
        public String note;
        public List<EdgeKeyDTO> keys;
    }

    public static class TabViewNodeUpsertRequest {
        public UUID nodeId;
        public String role;
        public List<String> addDims;
    }

    public static class TabViewUpsertRequest {
        public String tabType;
        public String variantKey;
        public String variantLabel;
        public UUID anchorNodeId;
        public List<String> switches;
        public String dialect;
        public List<TabViewNodeUpsertRequest> nodes;
    }
}
