package com.cpq.partmodel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 3D 模型注册（mat_part_model）。
 *
 * <p>同一 part_no 可有多个版本（version 递增），is_current 标识当前版本。
 *
 * <p>详见 docs/3D产品选配方案.md §7.9
 */
@Entity
@Table(name = "mat_part_model",
       uniqueConstraints = @UniqueConstraint(columnNames = {"part_no", "version"}))
public class PartModel extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "part_no", nullable = false, length = 64)
    public String partNo;

    @Column(nullable = false)
    public Integer version = 1;

    @Column(length = 255)
    public String label;

    @Column(name = "is_current", nullable = false)
    public Boolean isCurrent = true;

    @Column(name = "glb_url", nullable = false, columnDefinition = "TEXT")
    public String glbUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    public String thumbnailUrl;

    @Column(name = "mesh_count")
    public Integer meshCount;

    public Integer vertices;

    @Column(name = "size_kb")
    public Integer sizeKb;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    public Map<String, Object> metadata;

    @Column(name = "uploaded_by")
    public UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    public OffsetDateTime uploadedAt = OffsetDateTime.now();
}
