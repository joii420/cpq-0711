package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** task-0729 料号审核记录（屏 3/4）。rowRed = breached_count > 0（硬约束 19）。 */
@Entity
@Table(name = "material_price_review")
public class MaterialPriceReview extends PanacheEntityBase {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String BUDGET_QUEUED = "QUEUED";
    public static final String BUDGET_COMPUTING = "COMPUTING";
    public static final String BUDGET_READY = "READY";
    public static final String BUDGET_FAILED = "FAILED";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "version_id", nullable = false)
    public UUID versionId;

    @Column(name = "previous_version_id")
    public UUID previousVersionId;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "material_no", nullable = false, length = 50)
    public String materialNo;

    @Column(name = "template_series_id")
    public UUID templateSeriesId;

    @Column(name = "basis_quotation_id")
    public UUID basisQuotationId;

    @Column(name = "status", nullable = false, length = 20)
    public String status = STATUS_PENDING;

    @Column(name = "budget_status", nullable = false, length = 20)
    public String budgetStatus = BUDGET_QUEUED;

    @Column(name = "budget_error", columnDefinition = "TEXT")
    public String budgetError;

    @Column(name = "breached_count", nullable = false)
    public Integer breachedCount = 0;

    @Column(name = "amber_count", nullable = false)
    public Integer amberCount = 0;

    @Column(name = "missing_count", nullable = false)
    public Integer missingCount = 0;

    @Column(name = "stale_count", nullable = false)
    public Integer staleCount = 0;

    @Column(name = "column_count", nullable = false)
    public Integer columnCount = 0;

    @Column(name = "reviewed_by")
    public UUID reviewedBy;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    public String reviewComment;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    public static MaterialPriceReview findByVersionAndMaterial(UUID versionId, String materialNo) {
        return find("versionId = ?1 and materialNo = ?2", versionId, materialNo).firstResult();
    }

    /** D5 反例外判定：该客户×料号是否存在过 REJECTED 记录（不限本期版本）。 */
    public static boolean hasEverRejected(String customerNo, String materialNo) {
        return count("customerNo = ?1 and materialNo = ?2 and status = ?3",
                customerNo, materialNo, STATUS_REJECTED) > 0;
    }
}
