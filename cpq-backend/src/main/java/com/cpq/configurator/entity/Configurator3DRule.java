package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 选项值的 3D 渲染规则（§3.5 + V241）。
 * <p>5 种 Action：SHOW_MESH / HIDE_MESH / REPLACE_MATERIAL / SWAP_MESH / TRANSFORM_MESH
 */
@Entity
@Table(name = "product_config_3d_rule")
public class Configurator3DRule extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "option_value_id", nullable = false)
    public UUID optionValueId;

    @Column(nullable = false, length = 32)
    public String action;

    @Column(name = "target_mesh", length = 128)
    public String targetMesh;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> params;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
