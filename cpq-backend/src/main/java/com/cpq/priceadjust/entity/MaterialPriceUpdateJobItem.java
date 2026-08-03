package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 更新批次明细：报价单 × 料号。三种非成功态语义见 api.md §3.3：
 * FAILED（数据问题，含 SUBTOTAL_MISMATCH）/ CONFLICT（row_version 不匹配）/
 * STALE（所属版本已被取代，终态不可重试）。
 */
@Entity
@Table(name = "material_price_update_job_item")
public class MaterialPriceUpdateJobItem extends PanacheEntityBase {

    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CONFLICT = "CONFLICT";
    public static final String STALE = "STALE";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "job_id", nullable = false)
    public UUID jobId;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "material_no", nullable = false, length = 50)
    public String materialNo;

    @Column(name = "line_item_id")
    public UUID lineItemId;

    @Column(name = "status", nullable = false, length = 20)
    public String status = WAITING;

    @Column(name = "error_code", length = 50)
    public String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "diff_value", precision = 20, scale = 6)
    public BigDecimal diffValue;

    @Column(name = "retry_count", nullable = false)
    public Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    public static List<MaterialPriceUpdateJobItem> listByJob(UUID jobId) {
        return list("jobId", jobId);
    }

    /**
     * job 名下未完成项（WAITING/RUNNING）一律置 STALE 终态（§11.6.3.2，版本被新版取代时）。
     * jobIds 由调用方（PriceAdjustVersionGenerationService）先按 versionId 查出 MaterialPriceUpdateJob
     * 再传入，避免这里跨实体写子查询。
     *
     * <p>🔒 2026-08-03 修复（回归阻断 #21/#64）：原写法 {@code updatedAt = now()} 用 HQL 函数
     * {@code now()}，Hibernate 6 对该函数返回类型推断为 {@code java.lang.Object}，赋值给
     * {@code OffsetDateTime} 字段时语义校验直接拒绝（{@code SemanticException: Cannot assign
     * expression of type 'java.lang.Object' to target path 'alias_0.updatedAt'}）。此前两次
     * 测试"成功"是因为当时待 supersede 版本名下还没有 job（{@code jobIds} 判空提前 return，
     * 本行 HQL 从未被真正编译执行）——不是本次同步引入的新 bug，是这条从未被真实数据路径触达过
     * 的既有代码首次被执行时暴露的语义错误。改为绑定真实 Java {@link OffsetDateTime#now()} 参数，
     * 不再依赖 HQL 端函数推断类型。
     */
    public static int staleAllUnfinishedByJobIds(List<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return 0;
        return update("status = ?1, updatedAt = ?2 where status in (?3, ?4) and jobId in ?5",
                STALE, OffsetDateTime.now(), WAITING, RUNNING, jobIds);
    }
}
