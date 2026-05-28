package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "product_config_option",
       uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "code"}))
public class ConfiguratorOption extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(nullable = false, length = 64)
    public String code;

    @Column(nullable = false, length = 128)
    public String label;

    @Column(name = "option_type", nullable = false, length = 32)
    public String optionType;  // EXCLUSIVE / MULTI_SELECT / NUMERIC / TEXT / COLOR

    @Column(name = "data_type", length = 20)
    public String dataType;

    @Column(name = "assign_mode", length = 20)
    public String assignMode;

    @Column(name = "is_required", nullable = false)
    public Boolean isRequired = true;

    @Column(name = "default_value", length = 128)
    public String defaultValue;

    @Column(name = "min_value", length = 40)
    public String minValue;

    @Column(name = "max_value", length = 40)
    public String maxValue;

    @Column(name = "partno_prefix", length = 20)
    public String partnoPrefix;

    @Column(name = "partno_suffix", length = 20)
    public String partnoSuffix;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(columnDefinition = "TEXT")
    public String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> metadata;

    @Column(name = "source_feature_field_id")
    public Long sourceFeatureFieldId;

    @Column(name = "source_feature_snapshot_at")
    public OffsetDateTime sourceFeatureSnapshotAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
