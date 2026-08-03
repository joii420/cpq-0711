package com.cpq.priceadjust.service;

import com.cpq.notification.service.NotificationService;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.quotation.entity.Quotation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0729 B11 · 批量调价执行完成后的通知（复用既有 {@link NotificationService}，不新增通道）。
 *
 * <p>由 {@link PriceAdjustJobExecutionService#finalizeJob} 在批次终态落库后调用，覆盖两类收件人：
 * <ol>
 *   <li><b>触发批次的财务人员</b>（{@link MaterialPriceUpdateJob#triggeredBy}）——一条批次汇总通知，
 *       success/failed/conflict/stale 计数全量透出（不只报喜，裁决"失败项两边都必须可见"）；</li>
 *   <li><b>受影响报价单的销售负责人</b>（{@code quotation.salesRepId}）——按报价单聚合（同一报价单
 *       多个料号行只发一条，不逐行轰炸），内容含"此前手工调整可能已被覆盖，请复核"（裁决37的
 *       补偿措施），若该报价单同批次还有失败/冲突项，同一条通知里一并说明（同样是"两边可见"，
 *       不能销售侧只看到"已更新"这半句真话）。销售只收自己名下报价单的通知，不做全局广播。</li>
 * </ol>
 *
 * <p>🔒 <b>幂等纪律</b>：{@link MaterialPriceUpdateJob#notified} 建表时已预留但此前未使用——本类
 * 是其唯一写入点。语义 = "只在批次第一次终态落库时通知一次"，{@code retryJob}/{@code retryJobItem}
 * 触发的后续 {@code finalizeJob} 不会重复通知（避免同一批次因重试反复打扰财务/销售）。这是本次
 * 交付在无更细粒度规格时做出的取舍，如后续需要"重试后结果有变化就应再通知一次"，需另行调整。
 *
 * <p>🔒 <b>不发明前端路由</b>：批次汇总通知的 {@code link} 留空——"更新任务页"前端路由当前尚未接入
 * `cpq-frontend/src/router/index.tsx`（grep 确认），本次是纯后端交付，不代为猜一个可能不存在的
 * 路径造成死链接；报价单通知的 {@code link} 用已确认存在的 {@code /quotations/:id}（router 第111行）。
 *
 * <p>失败不阻断价格版本升级主流程——本类任何异常均由调用方 {@code finalizeJob} 兜底 catch，
 * 通知发送失败绝不能让批次执行本身回滚（同 {@code PriceReconciler} 接入 saveDraft 的既定手法）。
 */
@ApplicationScoped
public class PriceAdjustNotificationService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustNotificationService.class);

    @Inject
    NotificationService notificationService;

    /** 每报价单聚合的计数容器。 */
    private static final class QuotationTally {
        int success;
        int failed;
        int conflict;
    }

    @Transactional
    public void notifyJobCompletion(UUID jobId) {
        if (jobId == null) return;
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        if (job == null) {
            LOG.warnf("[price-adjust-notify] jobId=%s 不存在，跳过通知", jobId);
            return;
        }
        if (Boolean.TRUE.equals(job.notified)) {
            LOG.infof("[price-adjust-notify] jobId=%s 已通知过，跳过（幂等）", jobId);
            return;
        }

        List<MaterialPriceUpdateJobItem> items = MaterialPriceUpdateJobItem.listByJob(jobId);

        notifyFinance(job);
        notifyQuotationCreators(job, items);

        job.notified = true;
        job.persist();
    }

    private void notifyFinance(MaterialPriceUpdateJob job) {
        if (job.triggeredBy == null) {
            LOG.warnf("[price-adjust-notify] jobId=%s triggeredBy 为空，跳过财务通知", job.id);
            return;
        }
        StringBuilder content = new StringBuilder();
        content.append("客户【").append(job.customerNo).append("】价格版本【")
               .append(job.versionNo != null ? job.versionNo : job.versionId)
               .append("】批量更新已完成，共 ").append(job.totalCount).append(" 条：成功 ")
               .append(job.successCount).append("，失败 ").append(job.failedCount)
               .append("，冲突 ").append(job.conflictCount);
        if (job.staleCount != null && job.staleCount > 0) {
            content.append("，已被新版取代 ").append(job.staleCount);
        }
        content.append("。");
        boolean hasIssue = (job.failedCount != null && job.failedCount > 0)
                || (job.conflictCount != null && job.conflictCount > 0);
        content.append(hasIssue ? "失败/冲突项需人工处理，请到调价管理页核实明细。" : "全部成功，无需处理。");

        String title = hasIssue
            ? "调价批次执行完成（含失败/冲突项）：" + job.customerNo
            : "调价批次执行完成：" + job.customerNo;

        notificationService.create(job.triggeredBy, "PRICE_ADJUST_JOB_SUMMARY", title,
            content.toString(), null, "MaterialPriceUpdateJob", job.id);
    }

    private void notifyQuotationCreators(MaterialPriceUpdateJob job, List<MaterialPriceUpdateJobItem> items) {
        Map<UUID, QuotationTally> byQuotation = new LinkedHashMap<>();
        for (MaterialPriceUpdateJobItem item : items) {
            if (item.quotationId == null) continue;
            // STALE：该项所属版本已被新版取代，本批次对它其实什么都没做，不构成"需要复核"的事件。
            if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) continue;
            QuotationTally t = byQuotation.computeIfAbsent(item.quotationId, k -> new QuotationTally());
            switch (item.status) {
                case MaterialPriceUpdateJobItem.SUCCESS -> t.success++;
                case MaterialPriceUpdateJobItem.FAILED -> t.failed++;
                case MaterialPriceUpdateJobItem.CONFLICT -> t.conflict++;
                default -> { /* WAITING/RUNNING 不应残留到 finalize 之后，忽略 */ }
            }
        }
        if (byQuotation.isEmpty()) return;

        List<Quotation> quotations = Quotation.list("id in ?1", byQuotation.keySet());
        Map<UUID, Quotation> quotationById = new LinkedHashMap<>();
        for (Quotation q : quotations) quotationById.put(q.id, q);

        for (Map.Entry<UUID, QuotationTally> e : byQuotation.entrySet()) {
            UUID quotationId = e.getKey();
            QuotationTally t = e.getValue();
            Quotation q = quotationById.get(quotationId);
            if (q == null) {
                LOG.warnf("[price-adjust-notify] jobId=%s quotationId=%s 报价单不存在，跳过", job.id, quotationId);
                continue;
            }
            if (q.salesRepId == null) {
                LOG.warnf("[price-adjust-notify] jobId=%s quotationId=%s 无销售负责人，跳过", job.id, quotationId);
                continue;
            }

            boolean hasIssue = t.failed > 0 || t.conflict > 0;
            String quotationLabel = q.quotationNumber != null ? q.quotationNumber : quotationId.toString();
            StringBuilder content = new StringBuilder();
            content.append("客户价格版本【")
                   .append(job.versionNo != null ? job.versionNo : job.versionId)
                   .append("】更新涉及您的报价单【").append(quotationLabel).append("】");
            if (!hasIssue) {
                content.append(" 的 ").append(t.success).append(" 个料号行，此前手工调整的价格可能已被覆盖，请复核。");
            } else {
                content.append("：成功 ").append(t.success);
                if (t.success > 0) content.append("（此前手工调整的价格可能已被覆盖，请复核）");
                content.append("，失败 ").append(t.failed).append("，冲突 ").append(t.conflict)
                       .append("（需人工处理或重试，请联系财务）。");
            }

            String title = hasIssue ? "报价单价格更新存在异常，请查看【" + quotationLabel + "】"
                                     : "报价单价格已按新版本调整，请复核【" + quotationLabel + "】";

            notificationService.create(q.salesRepId, "PRICE_ADJUST_QUOTATION_REVIEW", title,
                content.toString(), "/quotations/" + quotationId, "Quotation", quotationId);
        }
    }
}
