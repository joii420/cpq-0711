package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 3D 选配模板（product_config_template）。
 *
 * <p>与 V203 {@code config_template}（LIST_FORMULA 数据源，包 {@code com.cpq.configtemplate}）
 * 是两套独立系统：本类是销售/客户用的"3D 产品选配"模板。
 *
 * <p>详见 docs/3D产品选配方案.md §7.2
 */
@Entity
@Table(name = "product_config_template")
public class ConfiguratorTemplate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 64)
    public String code;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(length = 80)
    public String category;

    @Column(name = "base_part_no", length = 64)
    public String basePartNo;

    @Column(name = "base_model_id")
    public UUID baseModelId;

    @Column(name = "base_model_version")
    public Integer baseModelVersion;

    @Column(name = "base_model_snapshot_at")
    public OffsetDateTime baseModelSnapshotAt;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "show_price", nullable = false)
    public Boolean showPrice = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> metadata;

    @Column(nullable = false, length = 16)
    public String status = "DRAFT";

    @Column(nullable = false)
    public Integer version = 1;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
