package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 B5 · 异步更新任务执行（api.md §3）。
 *
 * <p>参照既有 {@code QuoteImportService} 的既定模式：{@code ManagedExecutor.runAsync} 触发
 * （由 {@link PriceAdjustReviewService#approve} 在同步事务提交后调用）+ 逐条
 * {@code @Transactional(REQUIRES_NEW)} 独立提交（一单失败不回滚全批，backtask B5）。
 *
 * <p>三种非成功态语义（api.md §3.3）：
 * <ul>
 *   <li>FAILED —— 数据问题（含 S0 的 SUBTOTAL_MISMATCH），需人工处理后重试</li>
 *   <li>CONFLICT —— 重算期间该行被改动（row_version 不匹配），直接重试即可</li>
 *   <li>STALE —— 所属版本已被新版取代（B3 生成新版时已把未完成 job_item 提前置 STALE，
 *       本类执行期遇到 STALE 项直接跳过不处理，终态不可重试）</li>
 * </ul>
 *
 * <p>🔒 指针推进纪律（§11.6.3.2）：本类只更新 job/job_item 状态，绝不回退
 * {@code material_price_version_ref}——个别单失败不回退整个料号的指针。
 */
@ApplicationScoped
public class PriceAdjustJobExecutionService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustJobExecutionService.class);

    @Inject MaterialVersionUpgradeService materialVersionUpgradeService;
    @Inject PriceAdjustNotificationService notificationService;

    public void executeJob(UUID jobId) {
        List<MaterialPriceUpdateJobItem> items = loadWaitingItems(jobId);
        LOG.infof("[price-adjust-job] executeJob jobId=%s items=%d", jobId, items.size());
        for (MaterialPriceUpdateJobItem item : items) {
            try {
                executeItem(item.id);
            } catch (Exception e) {
                LOG.errorf(e, "[price-adjust-job] jobId=%s item=%s 未预期异常", jobId, item.id);
                markItemFailed(item.id, "UNEXPECTED_ERROR", e.getMessage());
            }
        }
        finalizeJob(jobId);
    }

    @Transactional
    List<MaterialPriceUpdateJobItem> loadWaitingItems(UUID jobId) {
        return MaterialPriceUpdateJobItem.list(
            "jobId = ?1 and status in (?2, ?3)", jobId, MaterialPriceUpdateJobItem.WAITING, MaterialPriceUpdateJobItem.CONFLICT);
    }

    /**
     * 单条明细：找目标版本 + 调用真实升版（dryRun=false），逐条独立事务提交。
     *
     * <p>task-0729 debug（2026-08-03）真根因修复：{@code @ActivateRequestContext} 从
     * {@link #executeJob} 下移到本方法——原先挂在 {@code executeJob} 上时，request-scoped bean
     * （如 {@code DataLoader}）在嵌套进本方法的 {@code @Transactional(REQUIRES_NEW)} 后无法解析，
     * 实测在每个 job item 上 100% 抛 {@code ContextNotActiveException}（272 次/34 项批次），
     * 被 {@code BomTreeRenderService} §④ 逐组件 catch 静默吞掉，导致「物料与元素BOM」等全部
     * driver 组件页签清零、却仍报 {@code SUCCESS}。挂在本方法（即 REQUIRES_NEW 事务边界本身）
     * 上后，request context 与该事务同生命周期，验证通过。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void executeItem(UUID itemId) {
        MaterialPriceUpdateJobItem item = MaterialPriceUpdateJobItem.findById(itemId);
        if (item == null) return;
        if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) return; // 终态不处理

        item.status = MaterialPriceUpdateJobItem.RUNNING;
        item.updatedAt = OffsetDateTime.now();
        item.persist();

        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(item.jobId);
        if (job == null || job.versionId == null) {
            item.status = MaterialPriceUpdateJobItem.FAILED;
            item.errorCode = "JOB_NOT_FOUND";
            item.errorMessage = "job 或 versionId 缺失";
            item.persist();
            return;
        }
        if (item.lineItemId == null) {
            item.status = MaterialPriceUpdateJobItem.FAILED;
            item.errorCode = "LINE_ITEM_MISSING";
            item.errorMessage = "line item 缺失";
            item.persist();
            return;
        }

        UpgradeResult ur = materialVersionUpgradeService.upgrade(item.lineItemId, job.versionId, false);
        switch (ur.status) {
            case SUCCESS, SKIPPED -> {
                item.status = MaterialPriceUpdateJobItem.SUCCESS;
                item.errorCode = null;
                item.errorMessage = ur.message;
            }
            case CONFLICT -> {
                item.status = MaterialPriceUpdateJobItem.CONFLICT;
                item.errorCode = "ROW_VERSION_CONFLICT";
                item.errorMessage = ur.message;
                item.retryCount = item.retryCount + 1;
            }
            case FAILED -> {
                item.status = MaterialPriceUpdateJobItem.FAILED;
                item.errorCode = ur.errorCode;
                item.errorMessage = ur.message;
                item.diffValue = ur.diffValue;
            }
        }
        item.updatedAt = OffsetDateTime.now();
        item.persist();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void markItemFailed(UUID itemId, String errorCode, String message) {
        MaterialPriceUpdateJobItem item = MaterialPriceUpdateJobItem.findById(itemId);
        if (item == null) return;
        item.status = MaterialPriceUpdateJobItem.FAILED;
        item.errorCode = errorCode;
        item.errorMessage = message;
        item.updatedAt = OffsetDateTime.now();
        item.persist();
    }

    /** 汇总批次状态：全部 SUCCESS→SUCCESS；有成功有非成功→PARTIAL；全部非成功→FAILED。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void finalizeJob(UUID jobId) {
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        if (job == null) return;
        List<MaterialPriceUpdateJobItem> all = MaterialPriceUpdateJobItem.listByJob(jobId);
        // 🔒 计数口径唯一实现见 MaterialPriceUpdateJob#recountFrom —— supersede 转 STALE 后也走它，
        //    两处各写一份迟早漂（本方法原先是唯一重算点，被取代的 job 永远走不到这里）。
        job.recountFrom(all);
        job.finishedAt = OffsetDateTime.now();
        job.persist();
        LOG.infof("[price-adjust-job] jobId=%s finalized status=%s total=%d success=%d failed=%d conflict=%d stale=%d",
            jobId, job.status, job.totalCount, job.successCount, job.failedCount, job.conflictCount, job.staleCount);

        // task-0729 B11：批次终态落库后通知（触发财务 + 受影响报价单销售负责人）。非阻断——
        // 通知失败绝不能让批次 finalize 结果回滚（同 PriceReconciler 接入 saveDraft 的既定手法）。
        try {
            notificationService.notifyJobCompletion(jobId);
        } catch (Exception e) {
            LOG.warnf(e, "[price-adjust-job] jobId=%s 通知发送失败（不影响批次结果）", jobId);
        }
    }

    // -------------------------------------------------------------------------
    // §3.4/§3.5 重试
    // -------------------------------------------------------------------------

    public void retryJob(UUID jobId) {
        markJobRunning(jobId);
        executeJob(jobId);
    }

    @Transactional
    void markJobRunning(UUID jobId) {
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        if (job == null) return;
        job.status = MaterialPriceUpdateJob.RUNNING;
        job.finishedAt = null;
        job.persist();
    }

    public void retryJobItem(UUID itemId) {
        MaterialPriceUpdateJobItem item = loadItem(itemId);
        if (item == null) {
            throw new com.cpq.common.exception.BusinessException(404, "job item 不存在: " + itemId);
        }
        if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) {
            throw new com.cpq.common.exception.BusinessException(409, "所属版本已被取代，STALE 项不可重试");
        }
        executeItem(itemId);
        finalizeJob(item.jobId);
    }

    @Transactional
    MaterialPriceUpdateJobItem loadItem(UUID itemId) {
        return MaterialPriceUpdateJobItem.findById(itemId);
    }
}
