package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 核价单 — 报价单提交时自动建，锚定核价流程元数据与审批状态。
 * 表 costing_order 由 V304 创建（精简）、V305 升级为完整实体。
 * <p>
 * 状态枚举：PENDING / APPROVED / REJECTED / WITHDRAWN。
 * 同一报价单至多一条 active 记录（partial unique index uq_co_active）。
 */
@Entity
@Table(name = "costing_order")
public class CostingOrder extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "submitted_by")
    public UUID submittedBy;

    @Column(name = "entered_costing_at", nullable = false)
    public OffsetDateTime enteredCostingAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    // ── V305 新增字段 ──────────────────────────────────────────────────────────

    @Column(name = "costing_order_number", nullable = false)
    public String costingOrderNumber;

    @Column(name = "status", nullable = false)
    public String status = "PENDING";

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    public String rejectReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frozen_dto", columnDefinition = "jsonb")
    public String frozenDto;

    @Column(name = "total_amount", precision = 18, scale = 4)
    public BigDecimal totalAmount;

    // ── task-0713 B5 新增字段（D1 落定：核价侧 live 重算 + 结果缓存回核价单）──────────────
    // 绝不复用 total_amount（那是含 Step3 折扣的报价总额，语义不同）。

    /** {lineItemId: {costingCardValues, costingExcelValues}}，已应用本单 override 的核价侧渲染缓存。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costing_render", columnDefinition = "jsonb")
    public String costingRender;

    /** 核价侧单据总价 = Σ 核价成本 subtotal，不含 Step3 折扣。 */
    @Column(name = "costing_total_amount", precision = 18, scale = 4)
    public BigDecimal costingTotalAmount;

    @Column(name = "reviewed_by")
    public UUID reviewedBy;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── 生命周期回调 ───────────────────────────────────────────────────────────

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (enteredCostingAt == null) enteredCostingAt = now;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        // 兜底单号：任何未显式赋值的持久化都自给单号，满足 V305 NOT NULL。
        // T2 的 CostingFreezeService 会在 persist 前显式赋值，此处不覆盖。
        if (costingOrderNumber == null) {
            long seq = ((Number) Panache.getEntityManager()
                    .createNativeQuery("SELECT nextval('costing_order_number_seq')")
                    .getSingleResult()).longValue();
            costingOrderNumber = String.format("HJ-%s-%04d",
                    now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), seq);
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ── 查询方法 ───────────────────────────────────────────────────────────────

    /**
     * 按报价单 ID 查核价单（兼容 V304 历史调用）。累积多条后返回最新一条，
     * 语义对齐 {@link #findLatestByQuotation}，避免无序 firstResult 的不确定性。
     */
    public static CostingOrder findByQuotation(UUID quotationId) {
        return find("quotationId = ?1 order by createdAt desc", quotationId).firstResult();
    }

    /**
     * 查该报价单当前 active 核价单（status IN PENDING/APPROVED）。
     * 对应 partial unique index uq_co_active，至多一条。
     */
    public static CostingOrder findActiveByQuotation(UUID qid) {
        return find("quotationId = ?1 and status in ('PENDING','APPROVED')", qid).firstResult();
    }

    /**
     * 查该报价单最新核价单（按创建时间倒序取第一条）。
     */
    public static CostingOrder findLatestByQuotation(UUID qid) {
        return find("quotationId = ?1 order by createdAt desc", qid).firstResult();
    }

    /**
     * 查该报价单全部核价单历史（按创建时间倒序）。
     */
    public static List<CostingOrder> findAllByQuotation(UUID qid) {
        return list("quotationId = ?1 order by createdAt desc", qid);
    }
}
