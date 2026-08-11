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

    /**
     * task-0729 方向3 T2（V381）：L3 口径守卫告警码（如 {@code SUBTOTAL_MISMATCH}）。
     *
     * <p>🔒 与 {@link #budgetError} 语义正交，不要混用：{@code budgetError} 是<b>预算算不出来</b>
     * （{@code budgetStatus=FAILED}，阻断审核）；本字段是<b>预算算出来了、但顺带发现前后端算值分叉</b>
     * （{@code budgetStatus} 仍可为 {@code READY}，<b>不阻断</b>）。
     */
    @Column(name = "warn_code", length = 50)
    public String warnCode;

    @Column(name = "warn_message", columnDefinition = "TEXT")
    public String warnMessage;

    /** 守卫检出的差异绝对值 {@code |后端旧价重算 - li.subtotal|}。 */
    @Column(name = "warn_diff", precision = 26, scale = 12)
    public java.math.BigDecimal warnDiff;

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

    /**
     * 版本被新版取代时，该版本名下「待处理」审核记录一律置「已作废」（§11.6.3.2 / 验收 #7）。
     *
     * <p>🔒 <b>必须带 {@code status = PENDING} 谓词</b>，不能写成"该版本下所有 review 一律
     * VOIDED"：{@code APPROVED}/{@code REJECTED} 是<b>既成事实</b>，裁决 27 明确不回滚
     * （testcases.md:202）。指针 {@code material_price_version_ref} 同样不动——只有该料号
     * 后续再走一次审核才会推进。
     *
     * <p>写法与 {@code PriceAdjustStrategyService}「移出料号 → 作废其待处理审核」那处
     * 复用同一个 Panache 带状态谓词批量 update 惯用法（同实体、同 PENDING→VOIDED 语义）。
     *
     * <p>🔒 {@code updatedAt} 绑定<b>真实 Java 值</b>而非 HQL {@code now()}——同
     * {@link MaterialPriceUpdateJobItem#staleAllUnfinishedByJobIds} 踩过的坑：Hibernate 6 对
     * {@code now()} 的返回类型推断为 {@code java.lang.Object}，赋给 {@code OffsetDateTime}
     * 字段时语义校验直接拒绝（{@code SemanticException}），且该路径只在真有数据时才被执行到，
     * 空数据下永远暴露不出来。
     */
    public static int voidPendingByVersion(UUID versionId) {
        if (versionId == null) return 0;
        return update("status = ?1, updatedAt = ?2 where versionId = ?3 and status = ?4",
                STATUS_VOIDED, OffsetDateTime.now(), versionId, STATUS_PENDING);
    }

    /** D5 反例外判定：该客户×料号是否存在过 REJECTED 记录（不限本期版本）。 */
    public static boolean hasEverRejected(String customerNo, String materialNo) {
        return count("customerNo = ?1 and materialNo = ?2 and status = ?3",
                customerNo, materialNo, STATUS_REJECTED) > 0;
    }
}
