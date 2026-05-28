package com.cpq.featurelibrary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "cpq_feature_value",
       uniqueConstraints = @UniqueConstraint(columnNames = {"field_id", "code"}))
public class FeatureValue extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "field_id", nullable = false)
    public Long fieldId;

    @Column(nullable = false, length = 40)
    public String code;

    @Column(nullable = false, length = 255)
    public String label;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "partno_include", nullable = false)
    public Boolean partnoInclude = true;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_attrs", columnDefinition = "JSONB")
    public Map<String, Object> extraAttrs;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
