package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 选项值业务实体引用（§18A 收敛 — 替代 mat_feature_reference）
 * <p>5 种 ref_type：MATERIAL / PROCESS / COMPONENT / COST_ITEM / GLOBAL_VAR
 */
@Entity
@Table(name = "product_config_value_reference")
public class ConfiguratorValueReference extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "option_value_id", nullable = false)
    public UUID optionValueId;

    @Column(name = "ref_type", nullable = false, length = 32)
    public String refType;

    @Column(name = "ref_code", nullable = false, length = 80)
    public String refCode;

    @Column(length = 40)
    public String qty;

    @Column(length = 20)
    public String unit;

    @Column(columnDefinition = "TEXT")
    public String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> metadata;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "created_by")
    public UUID createdBy;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
