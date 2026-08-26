package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.util.UUID;

/** 语义图 · 边的连接键（支持 .and() 多组，task-260819 B-1）。 */
@Entity
@Table(name = "semantic_edge_key", uniqueConstraints = @UniqueConstraint(columnNames = {"edge_id", "seq"}))
public class SemanticEdgeKey extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "edge_id", nullable = false)
    public UUID edgeId;

    @Column(name = "left_column", nullable = false, length = 120)
    public String leftColumn;

    @Column(name = "right_column", nullable = false, length = 120)
    public String rightColumn;

    @Column(name = "seq", nullable = false)
    public int seq = 0;
}
