package com.cpq.importexcel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "import_mapping_template")
public class ImportMappingTemplate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 300)
    public String name;

    @Column(name = "excel_template_id", nullable = false)
    public UUID excelTemplateId;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mappings", nullable = false, columnDefinition = "jsonb")
    public String columnMappings = "[]";

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
