package com.cpq.priceadjust.service;

import com.cpq.datasource.sqlview.PriceBaseDateUtil;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.BatchSafetyLevel;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.DriverBatchSafetyAuditor;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    @Inject BomTreeRenderService bomTreeRenderService;
    @Inject DriverBatchSafetyAuditor safetyAuditor;

    /**
     * task-0806 · FR-1（方案 B）+ FR-4/FR-5/FR-6/FR-7：逐项循环<b>之前</b>按
     * {@code (costingCardTemplateId, 取价基准日)} 分组批量预渲染核价树，结果按 {@code lineItemId}
     * 分发给各 {@link #executeItem}；预渲染本身只读、不参与 item 事务（需求文档 §4 事务边界）。
     */
    public void executeJob(UUID jobId) {
        List<MaterialPriceUpdateJobItem> items = loadWaitingItems(jobId);
        LOG.infof("[price-adjust-job] executeJob jobId=%s items=%d", jobId, items.size());
        Map<UUID, CardSnapshotService.PrecomputedTreeRows> precomputedByLineItem = precomputeBatch(jobId, items);
        for (MaterialPriceUpdateJobItem item : items) {
            try {
                CardSnapshotService.PrecomputedTreeRows precomputed =
                    item.lineItemId != null ? precomputedByLineItem.get(item.lineItemId) : null;
                executeItem(item.id, precomputed);
            } catch (Exception e) {
                LOG.errorf(e, "[price-adjust-job] jobId=%s item=%s 未预期异常", jobId, item.id);
                markItemFailed(item.id, "UNEXPECTED_ERROR", e.getMessage());
            }
        }
        finalizeJob(jobId);
    }

    /**
     * task-0806 · 分组键：{@code (costingCardTemplateId, priceBaseDate)}（需求文档 D-3/§5.3）。
     * 🔒 日期口径必须与 {@link PriceBaseDateUtil#deriveFrom} 同源，不得另写一份（AP-52）。
     */
    private static final class GroupKey {
        final UUID templateId;
        final LocalDate priceBaseDate;

        GroupKey(UUID templateId, LocalDate priceBaseDate) {
            this.templateId = templateId;
            this.priceBaseDate = priceBaseDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey)) return false;
            GroupKey other = (GroupKey) o;
            return Objects.equals(templateId, other.templateId) && Objects.equals(priceBaseDate, other.priceBaseDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, priceBaseDate);
        }
    }

    /**
     * task-0806 · 批量预渲染主体（方案 B）。
     *
     * <p>整体只读、不参与任何 item 事务；任一层级失败都<b>软回退</b>（不写入对应 item 的预渲染
     * 结果，留空 = 该 item 走 {@link MaterialVersionUpgradeService#upgrade(UUID, UUID, boolean)}
     * 三参默认路径，内部按原逻辑各自调用一次 {@code render()}）——FR-5 守卫 2 的核心：
     * 批量预渲染的任何异常都不得让整批 job 失败，只能让"批量"这个优化本身失效，退化为改造前的
     * 逐项慢路径（但仍然<b>正确</b>）。
     *
     * <p>三层"软失败"边界，由粗到细：
     * <ol>
     *   <li>整个方法级 try/catch：批量取行/取单据本身异常（如 DB 抖动）→ 空 map，全部逐项；</li>
     *   <li>每个分组独立 {@code @Transactional(REQUIRES_NEW)} + try/catch：FR-6 customerId 唯一性
     *       断言失败 / {@code render()} 本身抛异常（FR-5，如注入的必然失败 driver 组件）→ 该分组不
     *       写入，组内全部 item 逐项；</li>
     *   <li>FR-4 守卫 1 前置闸门：分组内任一 driver 组件判定为 {@link BatchSafetyLevel#PER_LINE_ITEM}
     *       → 直接跳过批量渲染（不算异常，是正常的保守分流），该组照样逐项。</li>
     * </ol>
     *
     * <p>🚨 <b>分组级 {@code REQUIRES_NEW} 是必需的，不是可选的美化</b>——实测（AC-4 单测）发现：
     * 若各分组共用同一个外层事务，一个分组因真实 SQL 异常（如注入的必然失败 driver 组件）失败后，
     * PostgreSQL 会把<b>整个物理事务</b>标记为 aborted（"current transaction is aborted, commands
     * ignored until end of transaction block"）——哪怕 Java 层 {@code catch} 住了异常，同一事务里
     * 后续分组的任何 SQL 都会连带失败。必须让每个分组在<b>独立事务</b>（{@link #renderGroupInNewTx}）
     * 里执行，失败时只回滚它自己，物理连接不同，不会连累其它分组。
     */
    @Transactional
    Map<UUID, CardSnapshotService.PrecomputedTreeRows> precomputeBatch(UUID jobId, List<MaterialPriceUpdateJobItem> items) {
        Map<UUID, CardSnapshotService.PrecomputedTreeRows> result = new LinkedHashMap<>();
        Map<GroupKey, List<QuotationLineItem>> groups;
        Map<UUID, Quotation> quotationById;
        try {
            List<UUID> lineItemIds = new ArrayList<>();
            for (MaterialPriceUpdateJobItem item : items) {
                if (item.lineItemId != null) lineItemIds.add(item.lineItemId);
            }
            if (lineItemIds.isEmpty()) return result;

            // N+1 纪律：各一条 IN 批量查询，不逐 item 单查。
            List<QuotationLineItem> lineItems = QuotationLineItem.list("id in ?1", lineItemIds);
            if (lineItems.isEmpty()) return result;
            Set<UUID> quotationIds = new LinkedHashSet<>();
            for (QuotationLineItem li : lineItems) {
                if (li.quotationId != null) quotationIds.add(li.quotationId);
            }
            if (quotationIds.isEmpty()) return result;
            List<Quotation> quotations = Quotation.list("id in ?1", new ArrayList<>(quotationIds));
            quotationById = new LinkedHashMap<>();
            for (Quotation q : quotations) quotationById.put(q.id, q);

            // 分组：(costingCardTemplateId, priceBaseDate)。无核价模板的行本就不需要预渲染
            // （upgrade() S5 对 costingCardTemplateId==null 直接跳过核价侧重算），不入组。
            groups = new LinkedHashMap<>();
            for (QuotationLineItem li : lineItems) {
                Quotation q = quotationById.get(li.quotationId);
                if (q == null || q.costingCardTemplateId == null) continue;
                LocalDate priceBaseDate = PriceBaseDateUtil.deriveFrom(q.createdAt);
                GroupKey key = new GroupKey(q.costingCardTemplateId, priceBaseDate);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(li);
            }
        } catch (Exception ex) {
            LOG.warnf(ex, "[price-adjust-job] jobId=%s 批量预渲染取数/分组阶段异常，全部回退逐项渲染: %s", jobId, ex.getMessage());
            return new LinkedHashMap<>();
        }

        for (Map.Entry<GroupKey, List<QuotationLineItem>> e : groups.entrySet()) {
            GroupKey key = e.getKey();
            List<QuotationLineItem> groupItems = e.getValue();
            try {
                Map<UUID, Map<String, ArrayNode>> rendered = renderGroupInNewTx(jobId, key, groupItems, quotationById);
                if (rendered == null) {
                    // FR-4 守卫 1 命中 PER_LINE_ITEM：正常分流，不是异常，不写日志噪音（renderGroupInNewTx 内已 WARN）。
                    continue;
                }
                for (QuotationLineItem li : groupItems) {
                    result.put(li.id, new CardSnapshotService.PrecomputedTreeRows(rendered.get(li.id)));
                }
            } catch (Exception ex) {
                // FR-5 守卫 2：批量预渲染分组失败（含 FR-6 customerId 唯一性断言、render() 本身抛异常）
                // -> 该组不写入任何 item 的预渲染结果，各 item 走 upgrade() 默认路径（内部各自逐项调用
                // render()），让 FAILED 精确落到出问题的单个 item，不拖累整组 / 整批。该分组自己的
                // REQUIRES_NEW 事务已随异常回滚，不影响本方法继续处理其它分组。
                LOG.warnf(ex, "[price-adjust-job] jobId=%s 批量预渲染分组 (template=%s, priceBaseDate=%s, items=%d) " +
                        "失败，回退逐项渲染（FR-5）: %s",
                    jobId, key.templateId, key.priceBaseDate, groupItems.size(), ex.getMessage());
            }
        }
        LOG.infof("[price-adjust-job] jobId=%s 批量预渲染完成：%d/%d 个 line item 命中预渲染结果",
            jobId, result.size(), items.size());
        return result;
    }

    /**
     * 单分组渲染，独立事务（见 {@link #precomputeBatch} 的 REQUIRES_NEW 必要性说明）。
     *
     * @return 该分组的 {@code render()} 结果；{@code null} = FR-4 守卫命中 PER_LINE_ITEM，
     *         正常跳过（不是异常）；抛异常 = FR-6 断言失败或 {@code render()} 本身失败，由调用方
     *         （{@link #precomputeBatch}）捕获并软回退。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Map<UUID, Map<String, ArrayNode>> renderGroupInNewTx(UUID jobId, GroupKey key, List<QuotationLineItem> groupItems,
                                                          Map<UUID, Quotation> quotationById) {
        // FR-6：分组内 customerId 必须唯一，不唯一直接抛错（被 precomputeBatch 的 catch 接住 -> 软回退逐项）。
        // 🔒 复用 precomputeBatch 已批量加载的 quotationById（N+1 纪律）——quotationById 里的 Quotation
        // 是外层事务加载的托管实体，本方法只读它们的标量字段（customerId），不触发懒加载，跨事务读安全。
        UUID groupCustomerId = null;
        boolean first = true;
        for (QuotationLineItem li : groupItems) {
            Quotation q = quotationById.get(li.quotationId);
            UUID cid = q != null ? q.customerId : null;
            if (first) {
                groupCustomerId = cid;
                first = false;
            } else if (!Objects.equals(groupCustomerId, cid)) {
                throw new IllegalStateException(String.format(
                    "分组 (template=%s, priceBaseDate=%s) 内 customerId 不唯一（%s vs %s）—— " +
                    "render() 只取分组首个 line item 的 customerId，跨客户会静默串号，拒绝批量渲染",
                    key.templateId, key.priceBaseDate, groupCustomerId, cid));
            }
        }

        // FR-4 守卫 1：分组内任一 driver 组件不安全（PER_LINE_ITEM）-> 整组不批量，逐项兜底。
        BatchSafetyLevel level = safetyAuditor.worstLevelForTemplate(key.templateId);
        if (level == BatchSafetyLevel.PER_LINE_ITEM) {
            LOG.warnf("[price-adjust-job] jobId=%s 分组 (template=%s, priceBaseDate=%s, items=%d) " +
                    "含 PER_LINE_ITEM 组件，不批量渲染，逐项走默认路径",
                jobId, key.templateId, key.priceBaseDate, groupItems.size());
            return null;
        }

        return bomTreeRenderService.render(key.templateId, groupItems);
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
     *
     * <p>🔒 task-0806：{@code @ActivateRequestContext}/{@code @Transactional(REQUIRES_NEW)} 的挂载
     * 位置原样不动（需求文档硬约束 4）——本次只新增 {@code precomputed} 参数，未改注解、未改事务边界。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void executeItem(UUID itemId) {
        executeItem(itemId, null);
    }

    /**
     * task-0806 · FR-1 载体：接受批量预渲染好的核价树结果，透传给
     * {@link MaterialVersionUpgradeService#upgrade(UUID, UUID, boolean, CardSnapshotService.PrecomputedTreeRows)}。
     * {@code precomputed == null}（未命中批量预渲染 / 单条重试）时行为与改造前的
     * {@link #executeItem(UUID)} 逐位一致。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void executeItem(UUID itemId, CardSnapshotService.PrecomputedTreeRows precomputed) {
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

        UpgradeResult ur = materialVersionUpgradeService.upgrade(item.lineItemId, job.versionId, false, precomputed);
        // ---- 方向3 T2：L3 口径守卫告警落库（非 dryRun 路径）----
        // 🔒 warn_* 与 error_* 正交：status 仍是 SUCCESS，只是顺带检出前后端算值分叉、**未阻断**。
        //    刻意不复用 errorCode —— 本类语义是「errorCode 非空 = 非成功态」，复用会产出
        //    「status=SUCCESS 却带 errorCode」的行，把屏 7 的「可重试」判定带偏。
        //    差异值复用既有 diffValue 列（语义相同，不新增）。
        item.warnCode = ur.warnCode;
        item.warnMessage = ur.warnMessage;
        if (ur.warnCode != null) {
            if (ur.diffValue != null) item.diffValue = ur.diffValue;
            LOG.warnf("[price-adjust-job] item=%s material=%s L3 守卫告警 %s diff=%s（不阻断升版）",
                item.id, item.materialNo, ur.warnCode, ur.diffValue);
        }
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
