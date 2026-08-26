package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 语义图 · 页签级列角色覆盖（第 ⑦ 张表，D-35 实测新增，task-260819 B-1）。
 *
 * <p>节点级 {@link SemanticNodeColumn#roles} 是默认值；同一列在不同页签担任的角色可能不同
 * （如「材质料号」在材质元素页签是 PART_NO+ROW_KEY），本表存页签级覆盖。
 */
@Entity
@Table(name = "semantic_tab_view_column", uniqueConstraints = @UniqueConstraint(columnNames = {"view_id", "column_id"}))
public class SemanticTabViewColumn extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "view_id", nullable = false)
    public UUID viewId;

    @Column(name = "column_id", nullable = false)
    public UUID columnId;

    @Column(name = "roles", nullable = false, columnDefinition = "TEXT[]")
    public String[] roles = new String[0];

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
