package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 料号审核逐比对列预算明细。屏3汇总标记与屏4抽屉明细均读此表，逐位一致（裁决41）。
 * status: RED=差异<0；MISSING=任一侧取不到值(计入breached)；AMBER=0≤差异<threshold；
 * STALE=componentId/metric因模板改版失效(不计breached/amber)；NORMAL=其余（E13）
 */
@Entity
@Table(name = "material_price_review_column")
public class MaterialPriceReviewColumn extends PanacheEntityBase {

    public static final String RED = "RED";
    public static final String AMBER = "AMBER";
    public static final String NORMAL = "NORMAL";
    public static final String MISSING = "MISSING";
    public static final String STALE = "STALE";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "review_id", nullable = false)
    public UUID reviewId;

    @Column(name = "column_id", nullable = false, length = 50)
    public String columnId;

    @Column(name = "column_label", length = 200)
    public String columnLabel;

    @Column(name = "threshold", precision = 20, scale = 6)
    public BigDecimal threshold;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "quote_current", precision = 20, scale = 6)
    public BigDecimal quoteCurrent;

    @Column(name = "quote_adjusted", precision = 20, scale = 6)
    public BigDecimal quoteAdjusted;

    @Column(name = "costing_current", precision = 20, scale = 6)
    public BigDecimal costingCurrent;

    @Column(name = "costing_adjusted", precision = 20, scale = 6)
    public BigDecimal costingAdjusted;

    @Column(name = "diff_current", precision = 20, scale = 6)
    public BigDecimal diffCurrent;

    @Column(name = "diff_adjusted", precision = 20, scale = 6)
    public BigDecimal diffAdjusted;

    @Column(name = "status", nullable = false, length = 20)
    public String status;

    @Column(name = "missing_side", length = 20)
    public String missingSide;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    public static List<MaterialPriceReviewColumn> listByReview(UUID reviewId) {
        return list("reviewId", io.quarkus.panache.common.Sort.by("sortOrder"), reviewId);
    }
}
