package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 比对列配置（§11.5.4 改判裁决43）。维度=客户×模板系列，不是按客户一份。
 * 唯一写入口=屏1价格调整策略Tab；屏4审核抽屉只读。⚠️ 不要沿用 customer_comparison_config 这个名字。
 */
@Entity
@Table(name = "comparison_column_config")
public class ComparisonColumnConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "template_series_id", nullable = false)
    public UUID templateSeriesId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb", nullable = false)
    public String columns = "[]";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    public UUID updatedBy;

    public static ComparisonColumnConfig find(String customerNo, UUID templateSeriesId) {
        return find("customerNo = ?1 and templateSeriesId = ?2", customerNo, templateSeriesId).firstResult();
    }
}
