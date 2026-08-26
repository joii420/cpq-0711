package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 语义图 · 边（task-260819 B-1）。
 *
 * <p>{@code from_node_id} / {@code to_node_id} 上的外键**故意用默认 RESTRICT**（不是 CASCADE）——
 * AC-54 要求删掉仍被引用的节点必须在库层就崩，CASCADE 会静默连带删边，是最坏情况。
 *
 * <p>⚠️ {@code coalesce_group} 是 api.md §1.1 已声明、{@code 需求文档.md §4.5} 草案 DDL 遗漏的字段
 * （多源 COALESCE 分组标识，"边数=19"是按此字段去重后的逻辑计数，物理行数=22）。
 */
@Entity
@Table(name = "semantic_edge", uniqueConstraints = @UniqueConstraint(columnNames = {"from_node_id", "to_node_id", "edge_kind"}))
public class SemanticEdge extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "from_node_id", nullable = false)
    public UUID fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    public UUID toNodeId;

    /** GRAIN | SUB | SAME | JOIN | LOOKUP | PRICE */
    @Column(name = "edge_kind", nullable = false, length = 20)
    public String edgeKind;

    /** MANY_TO_ONE | ONE_TO_MANY */
    @Column(name = "cardinality", nullable = false, length = 20)
    public String cardinality;

    @Column(name = "fallback_order")
    public Integer fallbackOrder;

    @Column(name = "coalesce_group", length = 40)
    public String coalesceGroup;

    /**
     * task-260819 D-45③（V393）：查名 LOOKUP 边专用开关——为 true 时编译器在 COALESCE 末尾追加
     * 连接键左列（原始编码列）作为最终兜底，查不到名称时退回显示编码而不是留空。默认 false——
     * 是否退回是每条边的业务选择（mc_view 的材质名称查名就没有退回），不能当默认行为。
     */
    @Column(name = "fallback_to_join_key", nullable = false)
    public boolean fallbackToJoinKey = false;

    /** PASS | FAIL | THIN | NA。仅在 POST /validate 或写端点在线校验时更新，GET / 不做实时探测。 */
    @Column(name = "assert_status", nullable = false, length = 10)
    public String assertStatus = "NA";

    @Column(name = "assert_sample_rows")
    public Long assertSampleRows;

    @Column(name = "note", columnDefinition = "TEXT")
    public String note;

    @Column(name = "created_by", length = 80)
    public String createdBy;
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_by", length = 80)
    public String updatedBy;
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();
    @Column(name = "status", nullable = false, length = 20)
    public String status = "ACTIVE";
}
