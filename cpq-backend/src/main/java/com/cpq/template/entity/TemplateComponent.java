package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "template_component")
public class TemplateComponent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(name = "component_id", nullable = false)
    public UUID componentId;

    @Column(name = "tab_name", length = 200)
    public String tabName;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preset_rows", columnDefinition = "jsonb", nullable = false)
    public String presetRows = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "formula_assignments", columnDefinition = "jsonb", nullable = false)
    public String formulaAssignments = "{}";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
