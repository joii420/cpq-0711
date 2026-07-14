package com.cpq.modelconfig.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 3D 模型配置（task-0712 B5，新表 {@code model_config}）。
 *
 * <p>按「对象类型 + 对象键」绑定：{@code SALES_PART}(=销售料号) / {@code MATERIAL}(=材质配方码)。
 * 同一 subject 支持多版本，仅一条 {@code is_current=true}（部分唯一索引
 * {@code uq_model_config_current} 并发保证）。
 *
 * <p>详见 dev-docs/task-0712-选配模板和报价单选配功能/backtask.md B5、api.md §4、需求说明.md §4.5。
 */
@Entity
@Table(name = "model_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"subject_type", "subject_key", "version"}))
public class ModelConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "subject_type", nullable = false, length = 20)
    public String subjectType;

    @Column(name = "subject_key", nullable = false, length = 64)
    public String subjectKey;

    @Column(nullable = false)
    public Integer version = 1;

    @Column(name = "is_current", nullable = false)
    public Boolean isCurrent = true;

    @Column(length = 255)
    public String label;

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
