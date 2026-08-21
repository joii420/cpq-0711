package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 语义图 · 页签可用节点（主源/附属源，task-260819 B-1）。
 *
 * <p>🚨 建模判据（D-26）：{@code add_dims} 表达"该附属源在这个页签里相对主源额外增加的维度"，
 * 是 (页签×节点) 的属性，**必须挂本表**，不能挂 {@link SemanticNode} 或 {@link SemanticEdge}——
 * 挂错会把 D-26 已修掉的"附属源冲突判定误拦"bug 固化进 schema。
 */
@Entity
@Table(name = "semantic_tab_view_node", uniqueConstraints = @UniqueConstraint(columnNames = {"view_id", "node_id"}))
public class SemanticTabViewNode extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "view_id", nullable = false)
    public UUID viewId;

    @Column(name = "node_id", nullable = false)
    public UUID nodeId;

    /** MAIN | AUX */
    @Column(name = "role", nullable = false, length = 10)
    public String role;

    @Column(name = "add_dims", nullable = false, columnDefinition = "TEXT[]")
    public String[] addDims = new String[0];

    @Column(name = "sort_order", nullable = false)
    public int sortOrder = 0;

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
