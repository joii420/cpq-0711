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

    /**
     * V200: 模板级 driver_path 覆盖. 非 null 时 publish() 时盖 component.dataDriverPath.
     * 典型用途: COMPOSITE 模板把 mat_bom → v_composite_child_elements (同一 component
     * 在 SIMPLE 模板用直接物理表路径, 在 COMPOSITE 模板用子件聚合视图).
     */
    @Column(name = "data_driver_path_override", columnDefinition = "text")
    public String dataDriverPathOverride;

    /**
     * V200: 模板级 fields 覆盖 (JSON 数组字符串). 非 null 时 publish() 时盖 component.fields.
     * 与 dataDriverPathOverride 一起用 — fields 引用的 basic_data_path 也要跟着改成
     * 视图列 (如 v_composite_child_materials.material_code).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fields_override", columnDefinition = "jsonb")
    public String fieldsOverride;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
