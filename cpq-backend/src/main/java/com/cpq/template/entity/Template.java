package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "template")
public class Template extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_series_id", nullable = false)
    public UUID templateSeriesId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(length = 20)
    public String version;

    @Column(length = 30)
    public String category;

    @Column(name = "customer_id")
    public UUID customerId;

    @Column(name = "category_id")
    public UUID categoryId;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "usage_note", columnDefinition = "TEXT")
    public String usageNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_attributes", columnDefinition = "jsonb")
    public String productAttributes = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subtotal_formula", columnDefinition = "jsonb")
    public String subtotalFormula = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "components_snapshot", columnDefinition = "jsonb")
    public String componentsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_view_config", columnDefinition = "jsonb")
    public String excelViewConfig;

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    /**
     * 模板类型 — V71 引入。
     *  QUOTATION 报价模板：按客户专属 / 通用兜底维度（customer_id 可有可无）
     *  COSTING   核价模板：默认所有客户可用（customer_id 留空 = 通用）
     */
    @Column(name = "template_kind", nullable = false, length = 20)
    public String templateKind = "QUOTATION";

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
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
