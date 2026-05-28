package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "product_config_option_value",
       uniqueConstraints = @UniqueConstraint(columnNames = {"option_id", "code"}))
public class ConfiguratorOptionValue extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "option_id", nullable = false)
    public UUID optionId;

    @Column(nullable = false, length = 64)
    public String code;

    @Column(nullable = false, length = 128)
    public String label;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "price_delta", nullable = false, precision = 18, scale = 4)
    public BigDecimal priceDelta = BigDecimal.ZERO;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "partno_include", nullable = false)
    public Boolean partnoInclude = true;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "feature_type", length = 40)
    public String featureType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> attributes;

    @Column(columnDefinition = "TEXT[]")
    public String[] tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geometry_ref", columnDefinition = "JSONB")
    public Map<String, Object> geometryRef;

    @Column(name = "sub_model_part_no", length = 64)
    public String subModelPartNo;

    @Column(name = "attach_mode", length = 20)
    public String attachMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attach_position", columnDefinition = "JSONB")
    public Map<String, Object> attachPosition;

    @Column(name = "replace_base_mesh")
    public Boolean replaceBaseMesh = false;

    @Column(name = "source_feature_value_id")
    public Long sourceFeatureValueId;

    @Column(name = "source_feature_snapshot_at")
    public OffsetDateTime sourceFeatureSnapshotAt;

    @Column(name = "local_only", nullable = false)
    public Boolean localOnly = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
