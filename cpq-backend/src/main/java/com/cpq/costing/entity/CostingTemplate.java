package com.cpq.costing.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_template")
public class CostingTemplate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "series_id", nullable = false)
    public UUID seriesId;

    @Column(nullable = false, length = 200)
    public String name;

    /**
     * V73：关联到「模板配置」(template 表) 中的具体模板。
     * 报价单视图按 customerTemplateId 反查 → 渲染该 Excel 模板；
     * 核价单视图按 costingCardTemplateId 反查 → 渲染该 Excel 模板。
     * NULL 表示该 Excel 模板尚未关联任何 template；FK ON DELETE SET NULL。
     * V74 起，Excel 模板按本字段（而非 category_id）组织和调用。
     * V81: 「核价-汇总演示模板」切到 d5f4dab0（核价-演示模板 v1.2）。
     * V82: 8 张料号级表 attribute label 对齐原核价 Excel 列名 + 补 currency/unit/is_active。
     */
    @Column(name = "linked_template_id")
    public UUID linkedTemplateId;

    @Column(name = "is_default", nullable = false)
    public Boolean isDefault = false;

    @Column(nullable = false, length = 20)
    public String version = "v1.0";

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    @Column(columnDefinition = "TEXT")
    public String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String columns = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referenced_variables", columnDefinition = "jsonb", nullable = false)
    public String referencedVariables = "[]";

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (seriesId == null) seriesId = UUID.randomUUID();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
