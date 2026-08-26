package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 语义图 · 页签视图（task-260819 B-1）。
 *
 * <p>6 类页签类型（主件/材质元素/零件/外购件/费用类/BOM树）→ 7 行声明——费用类因 D-34 分立建模
 * 占 2 行（{@code variant_key}='INCOMING_FIXED' / 'INCOMING_OTHER'）。
 */
@Entity
@Table(name = "semantic_tab_view", uniqueConstraints = @UniqueConstraint(columnNames = {"tab_type", "variant_key", "dialect"}))
public class SemanticTabView extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "tab_type", nullable = false, length = 40)
    public String tabType;

    @Column(name = "variant_key", nullable = false, length = 40)
    public String variantKey = "";

    @Column(name = "variant_label", length = 80)
    public String variantLabel;

    @Column(name = "anchor_node_id", nullable = false)
    public UUID anchorNodeId;

    @Column(name = "switches", nullable = false, columnDefinition = "TEXT[]")
    public String[] switches = new String[0];

    @Column(name = "dialect", nullable = false, length = 20)
    public String dialect = "QUOTE";

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
