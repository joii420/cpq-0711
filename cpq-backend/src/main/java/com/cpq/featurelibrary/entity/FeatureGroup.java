package com.cpq.featurelibrary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "cpq_feature_group")
public class FeatureGroup extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 40)
    public String code;

    @Column(nullable = false, length = 255)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(length = 80)
    public String category;

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    @Column(name = "erp_ref_code", length = 40)
    public String erpRefCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_attrs", columnDefinition = "JSONB")
    public Map<String, Object> extraAttrs;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by", length = 64)
    public String updatedBy;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
