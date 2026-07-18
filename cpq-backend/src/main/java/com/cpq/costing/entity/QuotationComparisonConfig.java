package com.cpq.costing.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0717 报价单比对视图 — 「比对列配置」持久化实体。
 *
 * <p>一行 = 一个（报价单 × 桶）的列配置；{@code bucket} ∈ {@code SALES}/{@code FINANCE}，
 * 由入口页面决定（不看登录用户真实角色，见 需求说明.md §11.E）。{@code columns} 只存列定义
 * （ColumnDef[]，见 api.md §5），不存值 —— 值实时由 {@code /comparison-view/data} 取。
 *
 * <p>唯一键 {@code uq_qcc_quotation_bucket (quotation_id, bucket)}，PUT 端点按此键 upsert。
 */
@Entity
@Table(name = "quotation_comparison_config")
public class QuotationComparisonConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(nullable = false, length = 16)
    public String bucket;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String columns = "[]";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** 按（报价单 × 桶）唯一键查询；无记录返回 null。 */
    public static QuotationComparisonConfig findByQuotationAndBucket(UUID quotationId, String bucket) {
        return find("quotationId = ?1 and bucket = ?2", quotationId, bucket).firstResult();
    }
}
