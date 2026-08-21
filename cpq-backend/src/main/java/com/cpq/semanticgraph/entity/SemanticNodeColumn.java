package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** 语义图 · 节点的列（task-260819 B-1）。roles 是节点级默认值，页签可用 {@link SemanticTabViewColumn} 覆盖（D-35）。 */
@Entity
@Table(name = "semantic_node_column", uniqueConstraints = @UniqueConstraint(columnNames = {"node_id", "db_column"}))
public class SemanticNodeColumn extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "node_id", nullable = false)
    public UUID nodeId;

    @Column(name = "db_column", nullable = false, length = 120)
    public String dbColumn;

    @Column(name = "display_name", nullable = false, length = 200)
    public String displayName;

    /** TEXT | NUMBER | DECIMAL | MONEY */
    @Column(name = "data_type", nullable = false, length = 20)
    public String dataType;

    @Column(name = "is_code", nullable = false)
    public boolean isCode = false;

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
