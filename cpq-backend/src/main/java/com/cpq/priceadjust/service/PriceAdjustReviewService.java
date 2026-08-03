package com.cpq.priceadjust.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.dto.ImpactResultDTO;
import com.cpq.priceadjust.dto.ReviewDetailDTO;
import com.cpq.priceadjust.dto.ReviewListItemDTO;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceReviewColumn;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.entity.MaterialPriceVersionRef;
import com.cpq.priceadjust.exception.ReviewNotReadyException;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-0729 B5 · 审核 API（api.md §2）。
 *
 * <p>approve = 同步推进指针 + 异步建 job（202）；reject 只改状态、指针不动、不产生 job；
 * 三种非成功态语义（FAILED/CONFLICT/STALE）在 {@link PriceAdjustJobExecutionService} 处理。
 */
@ApplicationScoped
public class PriceAdjustReviewService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustReviewService.class);
    private static final Set<String> ACTIVE_STATUSES = MaterialVersionUpgradeService.ACTIVE_STATUSES;

    @Inject EntityManager em;
    @Inject PriceAdjustJobExecutionService jobExecutionService;
    @Inject ManagedExecutor managedExecutor;

    // -------------------------------------------------------------------------
    // §2.1 待办池列表
    // -------------------------------------------------------------------------

    public PageResult<ReviewListItemDTO> list(
            String customerNo, String status, boolean breachedOnly, String keyword, int page, int size) {
        // 🔒 Parameters 不能塞未在查询串里出现的 key（Panache 会校验全部命中，否则报
        // "No parameter named ':x'"）——status 恒有效（缺省 PENDING），作为第一个、必然
        // 存在的子句，不用占位 "1=1"/"dummy" 这种反模式起手。
        StringBuilder q = new StringBuilder("status = :status");
        Parameters params = Parameters.with(
            "status", status != null && !status.isBlank() ? status : MaterialPriceReview.STATUS_PENDING);
        if (customerNo != null && !customerNo.isBlank()) {
            q.append(" and customerNo = :customerNo");
            params = params.and("customerNo", customerNo);
        }
        if (breachedOnly) {
            q.append(" and breachedCount > 0");
        }
        if (keyword != null && !keyword.isBlank()) {
            q.append(" and materialNo like :kw");
            params = params.and("kw", "%" + keyword + "%");
        }

        long total = MaterialPriceReview.count(q.toString(), params);
        List<MaterialPriceReview> rows = MaterialPriceReview
            .find(q.toString(), Sort.by("createdAt").descending(), params)
            .page(Page.of(Math.max(page - 1, 0), size)).list();

        List<ReviewListItemDTO> content = new ArrayList<>();
        for (MaterialPriceReview r : rows) content.add(toListItem(r));
        return new PageResult<>(content, page, size, total);
    }

    private ReviewListItemDTO toListItem(MaterialPriceReview r) {
        ReviewListItemDTO dto = new ReviewListItemDTO();
        dto.reviewId = r.id;
        dto.customerNo = r.customerNo;
        Customer c = Customer.find("code", r.customerNo).firstResult();
        dto.customerName = c != null ? c.name : r.customerNo;
        dto.materialNo = r.materialNo;
        dto.materialName = lookupMaterialName(r.materialNo, r.basisQuotationId);

        ElementPriceVersion cur = r.previousVersionId != null ? ElementPriceVersion.findById(r.previousVersionId) : null;
        ElementPriceVersion tgt = ElementPriceVersion.findById(r.versionId);
        dto.currentVersionNo = cur != null ? cur.versionNo : null;
        dto.targetVersionNo = tgt != null ? tgt.versionNo : null;

        dto.budgetStatus = r.budgetStatus;
        dto.reviewStatus = r.status;
        if (r.basisQuotationId != null) {
            Quotation q = Quotation.findById(r.basisQuotationId);
            if (q != null) {
                dto.basisQuotationNo = q.quotationNumber;
                dto.basisQuotationDate = q.createdAt != null ? q.createdAt.toLocalDate() : null;
            }
        }

        MaterialPriceReviewColumn productTotal = MaterialPriceReviewColumn
            .find("reviewId = ?1 and columnId = ?2", r.id, "col-default").firstResult();
        if (productTotal != null) {
            dto.quoteCostCurrent = productTotal.quoteCurrent;
            dto.quoteCostAdjusted = productTotal.quoteAdjusted;
            dto.costingCost = productTotal.costingCurrent;
            dto.diffCurrent = productTotal.diffCurrent;
            dto.diffAdjusted = productTotal.diffAdjusted;
        }

        dto.columnCount = r.columnCount;
        dto.breachedCount = r.breachedCount;
        dto.amberCount = r.amberCount;
        dto.missingCount = r.missingCount;
        dto.staleCount = r.staleCount;
        dto.rowRed = r.breachedCount > 0; // 硬约束 19：服务端给出，前端不得自算
        return dto;
    }

    private String lookupMaterialName(String materialNo, UUID basisQuotationId) {
        if (basisQuotationId == null) return materialNo;
        QuotationLineItem li = QuotationLineItem
            .find("quotationId = ?1 and productPartNoSnapshot = ?2", basisQuotationId, materialNo).firstResult();
        return li != null && li.productNameSnapshot != null ? li.productNameSnapshot : materialNo;
    }

    // -------------------------------------------------------------------------
    // §2.2 审核抽屉详情（三段）
    // -------------------------------------------------------------------------

    public ReviewDetailDTO detail(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) throw new BusinessException(404, "review 不存在: " + reviewId);

        ReviewDetailDTO dto = new ReviewDetailDTO();
        dto.reviewId = r.id;
        dto.customerNo = r.customerNo;
        dto.materialNo = r.materialNo;
        dto.materialName = lookupMaterialName(r.materialNo, r.basisQuotationId);
        ElementPriceVersion cur = r.previousVersionId != null ? ElementPriceVersion.findById(r.previousVersionId) : null;
        ElementPriceVersion tgt = ElementPriceVersion.findById(r.versionId);
        dto.currentVersionNo = cur != null ? cur.versionNo : null;
        dto.targetVersionNo = tgt != null ? tgt.versionNo : null;
        dto.budgetStatus = r.budgetStatus;
        dto.reviewStatus = r.status;

        // 一、为什么变：目标版本的全部元素明细（简化实现：matchedRule 粗粒度描述，
        // usageQty/unitPriceImpact 需逐行 driver 回溯，本期未做，如实留空——见交付说明已知限制）
        dto.elementChanges = new ArrayList<>();
        BigDecimal impactTotal = BigDecimal.ZERO;
        if (tgt != null) {
            for (ElementPriceVersionItem it : ElementPriceVersionItem.listByVersion(tgt.id)) {
                ReviewDetailDTO.ElementChange ec = new ReviewDetailDTO.ElementChange();
                ec.elementCode = it.elementCode;
                com.cpq.configure.entity.Element el = com.cpq.configure.entity.Element.find("elementCode", it.elementCode).firstResult();
                ec.elementName = el != null ? el.elementName : it.elementCode;
                ec.matchedRule = it.inheritedFromPrevious ? "无本期价，沿用上一版" : "客户调价策略";
                ec.previousPrice = it.previousPrice;
                ec.currentPrice = it.currentPrice;
                ec.changeRate = it.changeRate;
                ec.noPrice = Boolean.TRUE.equals(it.noPrice);
                ec.inheritedFromPrevious = Boolean.TRUE.equals(it.inheritedFromPrevious);
                dto.elementChanges.add(ec);
            }
        }

        // 二、比对结果
        dto.templateSeriesId = r.templateSeriesId;
        if (r.templateSeriesId != null) {
            Template t = Template.find("templateSeriesId = ?1", r.templateSeriesId).firstResult();
            dto.templateSeriesName = t != null ? t.name : null;
        }
        dto.comparisonColumns = new ArrayList<>();
        for (MaterialPriceReviewColumn col : MaterialPriceReviewColumn.listByReview(r.id)) {
            ReviewDetailDTO.ColumnResult cr = new ReviewDetailDTO.ColumnResult();
            cr.columnId = col.columnId;
            cr.label = col.columnLabel;
            cr.threshold = col.threshold;
            cr.quoteCurrent = col.quoteCurrent;
            cr.quoteAdjusted = col.quoteAdjusted;
            cr.costingCurrent = col.costingCurrent;
            cr.costingAdjusted = col.costingAdjusted;
            cr.diffCurrent = col.diffCurrent;
            cr.diffAdjusted = col.diffAdjusted;
            cr.status = col.status;
            cr.missingSide = col.missingSide;
            dto.comparisonColumns.add(cr);
            if ("col-default".equals(col.columnId)) impactTotal = col.diffAdjusted != null ? col.diffAdjusted : BigDecimal.ZERO;
        }
        dto.elementImpactTotal = impactTotal;

        // 三、逐单明细：该客户×料号名下全部活单，isBasis 标记判断依据单
        dto.quotations = new ArrayList<>();
        for (Object[] row : findAllActiveLines(r.customerNo, r.materialNo)) {
            UUID quotationId = (UUID) row[0];
            UUID lineItemId = (UUID) row[1];
            Quotation q = Quotation.findById(quotationId);
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (q == null || li == null) continue;
            ReviewDetailDTO.QuotationRef qr = new ReviewDetailDTO.QuotationRef();
            qr.quotationId = q.id;
            qr.quotationNo = q.quotationNumber;
            qr.createdAt = q.createdAt;
            qr.status = q.status;
            qr.isBasis = q.id.equals(r.basisQuotationId);
            qr.quoteSubtotalCurrent = li.subtotal;
            qr.comparisonViewUrl = "/quotations/" + q.id + "/comparison";
            dto.quotations.add(qr);
        }

        return dto;
    }

    // -------------------------------------------------------------------------
    // §2.3 影响面预览（只读，不产生副作用）
    // -------------------------------------------------------------------------

    public ImpactResultDTO impact(List<UUID> reviewIds) {
        ImpactResultDTO result = new ImpactResultDTO();
        result.versionPaths = new ArrayList<>();
        result.breachedMaterials = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> excludedByStatus = new LinkedHashMap<>();
        Set<UUID> allQuotationIds = new LinkedHashSet<>();
        Set<UUID> excludedQuotationIds = new LinkedHashSet<>();

        int materialCount = 0;
        for (UUID reviewId : reviewIds) {
            MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
            if (r == null) continue;
            materialCount++;
            ElementPriceVersion cur = r.previousVersionId != null ? ElementPriceVersion.findById(r.previousVersionId) : null;
            ElementPriceVersion tgt = ElementPriceVersion.findById(r.versionId);
            ImpactResultDTO.VersionPath vp = new ImpactResultDTO.VersionPath();
            vp.materialNo = r.materialNo;
            vp.from = cur != null ? cur.versionNo : null;
            vp.to = tgt != null ? tgt.versionNo : null;
            result.versionPaths.add(vp);

            if (r.breachedCount > 0) {
                ImpactResultDTO.BreachedMaterial bm = new ImpactResultDTO.BreachedMaterial();
                bm.materialNo = r.materialNo;
                bm.breachedCount = r.breachedCount;
                result.breachedMaterials.add(bm);
            }

            // 活单：计入 quotationCount + byStatus
            for (Object[] row : findAllActiveLines(r.customerNo, r.materialNo)) {
                UUID quotationId = (UUID) row[0];
                if (allQuotationIds.add(quotationId)) {
                    Quotation q = Quotation.findById(quotationId);
                    if (q != null) byStatus.merge(q.status, 1, Integer::sum);
                }
            }
            // 非活单（SENT/ACCEPTED/EXPIRED/CANCELLED）：明示不会被更新
            for (Object[] row : findAllExcludedLines(r.customerNo, r.materialNo)) {
                UUID quotationId = (UUID) row[0];
                String status = (String) row[1];
                if (excludedQuotationIds.add(quotationId)) {
                    excludedByStatus.merge(status, 1, Integer::sum);
                }
            }
        }

        result.materialCount = materialCount;
        result.quotationCount = allQuotationIds.size();
        result.byStatus = byStatus;
        result.excludedQuotationCount = excludedQuotationIds.size();
        result.excludedByStatus = excludedByStatus;
        return result;
    }

    // -------------------------------------------------------------------------
    // §2.4 通过（同步推进指针 + 异步建 job）
    // -------------------------------------------------------------------------

    /**
     * 对外入口：先同步完成校验+指针推进+建 job（事务提交），再在事务之外触发异步执行。
     * 🔒 async 派发必须放在 {@code @Transactional} 方法之外（同 B3
     * {@code generateVersionAndEnqueueBudget} 的既定模式）——若在事务方法内部调用
     * {@code managedExecutor.runAsync}，异步线程可能在外层事务真正提交前就抢跑，读不到刚插入
     * 的 job/job_item 行；本方法上一版直接在 {@code doApprove} 内部调用 runAsync，真实联调时
     * 复现为 HTTP 500（review 状态已提交为 APPROVED，但响应异常），修正为这个两段式后问题消失。
     */
    public ApproveResult approve(ApproveRejectRequest req, UUID actorId) {
        ApproveResult result = doApprove(req, actorId);
        UUID jobId = result.jobId;
        managedExecutor.runAsync(() -> jobExecutionService.executeJob(jobId));
        return result;
    }

    @Transactional
    ApproveResult doApprove(ApproveRejectRequest req, UUID actorId) {
        if (req == null || req.reviewIds == null || req.reviewIds.isEmpty()) {
            throw new BusinessException(400, "reviewIds 不能为空");
        }

        List<MaterialPriceReview> reviews = new ArrayList<>();
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (UUID id : req.reviewIds) {
            MaterialPriceReview r = MaterialPriceReview.findById(id);
            if (r == null) {
                invalid.add(Map.of("reviewId", id, "reason", "不存在"));
                continue;
            }
            if (!MaterialPriceReview.BUDGET_READY.equals(r.budgetStatus)) {
                invalid.add(Map.of("reviewId", id, "materialNo", r.materialNo, "reason", "预算未算完(" + r.budgetStatus + ")"));
                continue;
            }
            if (!MaterialPriceReview.STATUS_PENDING.equals(r.status)) {
                invalid.add(Map.of("reviewId", id, "materialNo", r.materialNo, "reason", "状态已变化(" + r.status + ")"));
                continue;
            }
            ElementPriceVersion v = ElementPriceVersion.findById(r.versionId);
            if (v == null || !ElementPriceVersion.STATUS_PENDING.equals(v.status)) {
                invalid.add(Map.of("reviewId", id, "materialNo", r.materialNo, "reason", "所属版本已被取代"));
                continue;
            }
            reviews.add(r);
        }

        if (!invalid.isEmpty()) {
            String errorCode = invalid.stream().anyMatch(m -> String.valueOf(m.get("reason")).contains("预算未算完"))
                ? "REVIEW_BUDGET_NOT_READY" : "REVIEW_STATUS_CHANGED";
            throw new ReviewNotReadyException(errorCode, "部分料号不满足通过条件，整批拒绝", invalid);
        }

        // 同步：指针推进 + review 置 APPROVED
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.customerNo = reviews.get(0).customerNo;
        job.versionId = reviews.get(0).versionId;
        ElementPriceVersion jobVersion = ElementPriceVersion.findById(job.versionId);
        job.versionNo = jobVersion != null ? jobVersion.versionNo : null;
        job.triggeredBy = actorId;
        job.status = MaterialPriceUpdateJob.RUNNING;
        job.persist();

        int itemCount = 0;
        Set<UUID> quotationIds = new LinkedHashSet<>();
        for (MaterialPriceReview r : reviews) {
            r.status = MaterialPriceReview.STATUS_APPROVED;
            r.reviewedBy = actorId;
            r.reviewedAt = OffsetDateTime.now();
            r.reviewComment = req.comment;
            r.persist();

            advancePointer(r.customerNo, r.materialNo, r.versionId);

            for (Object[] row : findAllActiveLines(r.customerNo, r.materialNo)) {
                UUID quotationId = (UUID) row[0];
                UUID lineItemId = (UUID) row[1];
                MaterialPriceUpdateJobItem item = new MaterialPriceUpdateJobItem();
                item.jobId = job.id;
                item.quotationId = quotationId;
                item.materialNo = r.materialNo;
                item.lineItemId = lineItemId;
                item.status = MaterialPriceUpdateJobItem.WAITING;
                item.persist();
                itemCount++;
                quotationIds.add(quotationId);
            }
        }
        job.totalCount = itemCount;
        job.persist();

        ApproveResult result = new ApproveResult();
        result.jobId = job.id;
        result.materialCount = reviews.size();
        result.quotationCount = quotationIds.size();
        result.itemCount = itemCount;
        return result;
    }

    public static class ApproveResult {
        public UUID jobId;
        public int materialCount;
        public int quotationCount;
        public int itemCount;
    }

    // -------------------------------------------------------------------------
    // §2.5 驳回（reason 必填；指针不动；不产生 job）
    // -------------------------------------------------------------------------

    @Transactional
    public void reject(ApproveRejectRequest req, UUID actorId) {
        if (req == null || req.reviewIds == null || req.reviewIds.isEmpty()) {
            throw new BusinessException(400, "reviewIds 不能为空");
        }
        if (req.reason == null || req.reason.isBlank()) {
            throw new BusinessException(400, "reason 不能为空（裁决 8）");
        }
        for (UUID id : req.reviewIds) {
            MaterialPriceReview r = MaterialPriceReview.findById(id);
            if (r == null) continue;
            r.status = MaterialPriceReview.STATUS_REJECTED;
            r.reviewedBy = actorId;
            r.reviewedAt = OffsetDateTime.now();
            r.reviewComment = req.reason;
            r.persist();
        }
    }

    // -------------------------------------------------------------------------
    // §2.6 预算重试
    // -------------------------------------------------------------------------

    public void recomputeBudget(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) throw new BusinessException(404, "review 不存在: " + reviewId);
        markComputing(reviewId);
        UUID versionId = r.versionId;
        managedExecutor.runAsync(() -> recomputeSingleReview(reviewId, versionId));
    }

    @Transactional
    void markComputing(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r != null) {
            r.budgetStatus = MaterialPriceReview.BUDGET_COMPUTING;
            r.persist();
        }
    }

    @jakarta.enterprise.context.control.ActivateRequestContext
    void recomputeSingleReview(UUID reviewId, UUID versionId) {
        // 复用 PriceAdjustBudgetService 的单料号处理逻辑：直接重跑 processMaterial
        // （幂等：会重新定位 basis + 重算 columns，覆盖旧的 FAILED 状态）。
        try {
            MaterialPriceReview r = loadReview(reviewId);
            if (r == null) return;
            budgetServiceInstance().processMaterial(versionId, r.customerNo, resolveThreshold(r.customerNo), r.materialNo);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust] recomputeBudget review=%s failed", reviewId);
        }
    }

    @Transactional
    MaterialPriceReview loadReview(UUID reviewId) {
        return MaterialPriceReview.findById(reviewId);
    }

    @Inject PriceAdjustBudgetService budgetService;
    private PriceAdjustBudgetService budgetServiceInstance() { return budgetService; }

    @Transactional
    BigDecimal resolveThreshold(String customerNo) {
        com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy s =
            com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        return s != null ? s.costDiffThreshold : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // 共用辅助
    // -------------------------------------------------------------------------

    private void advancePointer(String customerNo, String materialNo, UUID versionId) {
        MaterialPriceVersionRef ref = MaterialPriceVersionRef.findRef(customerNo, materialNo);
        if (ref == null) {
            ref = new MaterialPriceVersionRef();
            ref.customerNo = customerNo;
            ref.materialNo = materialNo;
        }
        ref.versionId = versionId;
        ref.updatedAt = OffsetDateTime.now();
        ref.persist();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> findAllActiveLines(String customerNo, String materialNo) {
        return em.createNativeQuery(
                "SELECT q.id, li.id FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno AND q.status = ANY(:statuses)")
            .setParameter("cno", customerNo).setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> findAllExcludedLines(String customerNo, String materialNo) {
        return em.createNativeQuery(
                "SELECT q.id, q.status FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno AND NOT (q.status = ANY(:statuses))")
            .setParameter("cno", customerNo).setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
    }
}
