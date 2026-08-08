package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** task-0729 更新批次（屏 6 + 常驻更新任务页）。 */
@Entity
@Table(name = "material_price_update_job")
public class MaterialPriceUpdateJob extends PanacheEntityBase {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String STALE = "STALE";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "version_id")
    public UUID versionId;

    @Column(name = "version_no", length = 20)
    public String versionNo;

    @Column(name = "triggered_by")
    public UUID triggeredBy;

    @Column(name = "triggered_at", nullable = false)
    public OffsetDateTime triggeredAt = OffsetDateTime.now();

    @Column(name = "status", nullable = false, length = 20)
    public String status = RUNNING;

    @Column(name = "total_count", nullable = false)
    public Integer totalCount = 0;

    @Column(name = "success_count", nullable = false)
    public Integer successCount = 0;

    @Column(name = "failed_count", nullable = false)
    public Integer failedCount = 0;

    @Column(name = "conflict_count", nullable = false)
    public Integer conflictCount = 0;

    @Column(name = "stale_count", nullable = false)
    public Integer staleCount = 0;

    /** repair-0807 FR-4：SKIPPED 终态计数（V384）。有跳过时批次不得报 SUCCESS，见 {@link #recountFrom}。 */
    @Column(name = "skipped_count", nullable = false)
    public Integer skippedCount = 0;

    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;

    @Column(name = "notified", nullable = false)
    public Boolean notified = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * 按 items 的<b>真实状态</b>重算五个计数器 + 批次状态。
     *
     * <p>🔒 <b>全量重算，不做增量推算</b>（禁止 {@code failedCount -= staleCount} 这类算法）——
     * 推算在并发或部分失败时会漂，直接数一遍 items 才是唯一权威。
     *
     * <p>🔒 <b>本方法是计数口径的唯一实现</b>，两个调用方都必须走它：
     * <ul>
     *   <li>{@code PriceAdjustJobExecutionService#finalizeJob}（批次执行完）</li>
     *   <li>{@code PriceAdjustVersionGenerationService}（旧版被取代、items 批量转 STALE 后）</li>
     * </ul>
     * 2026-08-05 抽出：此前只有 finalizeJob 会重算，而被取代的 job 永远不会再执行 → 表头
     * 「失败 1 / 冲突 1 / 已失效 0」与明细行全 STALE 对不上（验收 #61 修复的连带展示缺陷）。
     * 各写各的迟早漂，所以做成一份。
     *
     * <p>不碰 {@code finishedAt}：那是"批次执行结束"的语义，由 finalizeJob 单独负责；
     * supersede 不是一次执行。
     */
    public void recountFrom(java.util.List<MaterialPriceUpdateJobItem> items) {
        int success = 0, failed = 0, conflict = 0, stale = 0, skipped = 0;
        for (MaterialPriceUpdateJobItem it : items) {
            switch (it.status) {
                case MaterialPriceUpdateJobItem.SUCCESS -> success++;
                case MaterialPriceUpdateJobItem.FAILED -> failed++;
                case MaterialPriceUpdateJobItem.CONFLICT -> conflict++;
                case MaterialPriceUpdateJobItem.STALE -> stale++;
                case MaterialPriceUpdateJobItem.SKIPPED -> skipped++;
                default -> { /* WAITING/RUNNING 不计入终态，但计入 totalCount */ }
            }
        }
        this.successCount = success;
        this.failedCount = failed;
        this.conflictCount = conflict;
        this.staleCount = stale;
        this.skippedCount = skipped;
        this.totalCount = items.size();
        // repair-0807 FR-4（api.md §3.2 判定表，MaterialPriceUpdateJob#recountFrom 唯一实现）：
        // 🚨 "有跳过就不许报 SUCCESS"——本次缺陷的传播路径正是"32/32 全成功"骗过了财务，其中
        //    至少 1 张单一个字节都没改。判定顺序不可打乱：SUCCESS 的条件必须显式排除 skipped>0。
        if (failed == 0 && conflict == 0 && stale == 0 && skipped == 0) {
            this.status = SUCCESS;
        } else if (success == 0 && stale == items.size()) {
            this.status = STALE;
        } else if (success == 0 && skipped == 0) {
            this.status = FAILED;
        } else {
            this.status = PARTIAL; // 含"全部成功但有跳过"
        }
    }

    /**
     * 批量重算（供旧版被取代后使用）。必须与 {@code staleAllUnfinishedByJobIds} <b>在同一事务里</b>
     * 调用，否则表头会短暂与明细不一致。
     *
     * <p>⚠️ 先 {@code flush} 再读 items：{@code staleAllUnfinishedByJobIds} 是 Panache 批量
     * UPDATE，本方法要读到它刚写的状态。
     */
    public static void recountByJobIds(java.util.List<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return;
        getEntityManager().flush();
        getEntityManager().clear(); // 批量 UPDATE 绕过一级缓存，清掉可能已加载的陈旧 item 实体
        for (UUID jobId : jobIds) {
            MaterialPriceUpdateJob job = findById(jobId);
            if (job == null) continue;
            job.recountFrom(MaterialPriceUpdateJobItem.listByJob(jobId));
            job.persist();
        }
    }
}
