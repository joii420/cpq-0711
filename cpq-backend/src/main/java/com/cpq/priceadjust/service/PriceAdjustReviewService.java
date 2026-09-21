package com.cpq.priceadjust.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.ImpactResultDTO;
import com.cpq.priceadjust.dto.ReviewDetailDTO;
import com.cpq.priceadjust.dto.ReviewListItemDTO;
import com.cpq.priceadjust.dto.ReviewPageResult;
import com.cpq.priceadjust.dto.ComputeNowResponse;
import com.cpq.priceadjust.dto.UpgradeResult;
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
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Inject MaterialVersionUpgradeService materialVersionUpgradeService;

    /** repair-0807 FR-6：合计与 Σ 明细比对告警阈值（D-6，不阻断、不改数，只落 WARN）。 */
    private static final BigDecimal ELEMENT_IMPACT_RECONCILE_THRESHOLD = new BigDecimal("0.01");

    // -------------------------------------------------------------------------
    // §2.1 待办池列表
    // -------------------------------------------------------------------------

    public ReviewPageResult<ReviewListItemDTO> list(
            String customerNo, String status, boolean breachedOnly, String keyword, int page, int size) {
        // 🔒 Parameters 不能塞未在查询串里出现的 key（Panache 会校验全部命中，否则报
        // "No parameter named ':x'"）——status 恒有效（缺省 PENDING），作为第一个、必然
        // 存在的子句，不用占位 "1=1"/"dummy" 这种反模式起手。
        StringBuilder base = new StringBuilder("status = :status");
        Parameters params = Parameters.with(
            "status", status != null && !status.isBlank() ? status : MaterialPriceReview.STATUS_PENDING);
        if (customerNo != null && !customerNo.isBlank()) {
            base.append(" and customerNo = :customerNo");
            params = params.and("customerNo", customerNo);
        }
        if (keyword != null && !keyword.isBlank()) {
            base.append(" and materialNo like :kw");
            params = params.and("kw", "%" + keyword + "%");
        }
        // task-260920 B-14：「只看标红」只在已算完的行里筛（未计算行的 breachedCount 没有意义）
        String q = breachedOnly
            ? base + " and breachedCount > 0 and budgetStatus = '" + MaterialPriceReview.BUDGET_READY + "'"
            : base.toString();
        // task-260920 B-13②：budget_status 只在「待处理」时有意义 —— 未计算数只数待处理行
        String notComputed = " and status = '" + MaterialPriceReview.STATUS_PENDING + "' and budgetStatus in ('"
            + MaterialPriceReview.BUDGET_QUEUED + "', '" + MaterialPriceReview.BUDGET_COMPUTING + "')";

        long total = MaterialPriceReview.count(q, params);
        long notComputedTotal = MaterialPriceReview.count(q + notComputed, params);
        long excludedByNotComputed = breachedOnly ? MaterialPriceReview.count(base + notComputed, params) : 0;
        // J-1：主排序 createdAt 倒序不变，补确定次键（批量建行后同一时刻的行成为常态，否则翻页重复 / 漏行）
        List<MaterialPriceReview> rows = MaterialPriceReview
            .find(q, Sort.by("createdAt").descending().and("materialNo").and("id"), params)
            .page(Page.of(Math.max(page - 1, 0), size)).list();

        List<ReviewListItemDTO> content = new ArrayList<>();
        for (MaterialPriceReview r : rows) content.add(toListItem(r));
        return new ReviewPageResult<>(content, page, size, total, notComputedTotal, excludedByNotComputed);
    }

    /**
     * task-260920 B-7 · 单行查询（供 compute-now 之后轮询）：字段与列表行完全一致，<b>不触发任何试算</b>。
     */
    public ReviewListItemDTO row(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) throw new BusinessException(404, "review 不存在: " + reviewId);
        return toListItem(r);
    }

    /**
     * task-260920 B-7 · 点击即算：受理并插队试算该条，结果写回审核行；一律 202（经 {@link #row} 轮询结果）。
     * 非「待处理」⇒ 409 {@code REVIEW_NOT_PENDING}（走 {@link ReviewNotReadyException} 既有信封）。
     */
    public ComputeNowResponse computeNow(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) throw new BusinessException(404, "review 不存在: " + reviewId);
        if (!MaterialPriceReview.STATUS_PENDING.equals(r.status)) {
            throw notPending(r);
        }
        String st = budgetService.requestCompute(reviewId, PriceAdjustBudgetService.ComputeMode.COMPUTE_NOW);
        if (st == null) throw new BusinessException(404, "review 不存在: " + reviewId);
        if (!MaterialPriceReview.STATUS_PENDING.equals(budgetService.loadBrief(reviewId).status())) {
            // 受理前一刻被作废 / 审核：与「非待处理」同口径
            throw notPending(MaterialPriceReview.findById(reviewId));
        }
        return new ComputeNowResponse(reviewId, st);
    }

    private static ReviewNotReadyException notPending(MaterialPriceReview r) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reviewId", r.id);
        item.put("materialNo", r.materialNo);
        item.put("reason", "状态已变化(" + r.status + ")");
        return new ReviewNotReadyException("REVIEW_NOT_PENDING", "该料号已不是待处理状态", List.of(item));
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
        // task-260920：compute-now 走整段异步降级时（请求线程等锁超时），行尚未抢占，按「计算中」呈现（纯内存）
        if (MaterialPriceReview.STATUS_PENDING.equals(r.status)
                && !MaterialPriceReview.BUDGET_READY.equals(r.budgetStatus)
                && !MaterialPriceReview.BUDGET_COMPUTING.equals(r.budgetStatus)
                && budgetService.isComputeAccepted(r.id)) {
            dto.budgetStatus = MaterialPriceReview.BUDGET_COMPUTING;
        }
        dto.budgetError = r.budgetError; // repair-260918 B-16：行对象已加载，无新增查询
        dto.reviewStatus = r.status;
        if (r.basisQuotationId != null) {
            Quotation q = Quotation.findById(r.basisQuotationId);
            if (q != null) {
                dto.basisQuotationNo = q.quotationNumber;
                dto.basisQuotationDate = q.createdAt != null ? q.createdAt.toLocalDate() : null;
            }
        }

        // task-260920 B-13①：未算完的行金额一律 null（前端渲染「—」），🚫 返 0；也不再为它们查比对列
        MaterialPriceReviewColumn productTotal = !MaterialPriceReview.BUDGET_READY.equals(r.budgetStatus) ? null
            : MaterialPriceReviewColumn.find("reviewId = ?1 and columnId = ?2", r.id, "col-default").firstResult();
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
        dto.budgetError = r.budgetError; // task-260920 B-19：取自已加载的审核行，无新增查询
        dto.reviewStatus = r.status;

        // 一、为什么变：目标版本的全部元素明细。usageQty/unitPriceImpact 按 FR-6 在下方补算
        // （只有判断依据单 dryRun 成功时才有值，见下方 basisComputed 分支）。
        List<ElementPriceVersionItem> versionItems = tgt != null
            ? ElementPriceVersionItem.listByVersion(tgt.id) : List.of();
        dto.elementChanges = new ArrayList<>();
        for (ElementPriceVersionItem it : versionItems) {
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
        }

        // 三、逐单明细：该客户×料号名下全部活单，isBasis 标记判断依据单。
        // repair-0807 FR-7：一次批量查 Quotation/QuotationLineItem，不逐行 findById（N+1，AC-13）。
        List<Object[]> rows = findAllActiveLines(r.customerNo, r.materialNo);
        List<UUID> qids = rows.stream().map(row -> (UUID) row[0]).distinct().toList();
        List<UUID> lids = rows.stream().map(row -> (UUID) row[1]).distinct().toList();
        Map<UUID, Quotation> qMap = qids.isEmpty() ? Map.of()
            : Quotation.<Quotation>list("id in ?1", qids).stream().collect(Collectors.toMap(x -> x.id, x -> x));
        Map<UUID, QuotationLineItem> liMap = lids.isEmpty() ? Map.of()
            : QuotationLineItem.<QuotationLineItem>list("id in ?1", lids).stream().collect(Collectors.toMap(x -> x.id, x -> x));
        LOG.infof("[perf] review-detail N=%d sql=%d", rows.size(), 2);

        // repair-0807 FR-5：定位判断依据行（r.basisQuotationId 下 productPartNoSnapshot=materialNo
        // 那一行），从已批量加载的 rows/liMap 里找，不再单独查一次。
        UUID basisLineItemId = null;
        BigDecimal basisCurrentSubtotal = null;
        if (r.basisQuotationId != null) {
            // task-260920 B-17① / J-3：同单多行按 sort_order 升序（空值排后）、再按行 id 升序取第一行 ——
            // 与进池、试算同一规则（原先取「结果里第一条匹配的行」，无确定次序）。纯内存。
            QuotationLineItem best = null;
            for (Object[] row : rows) {
                if (!r.basisQuotationId.equals((UUID) row[0])) continue;
                QuotationLineItem cand = liMap.get((UUID) row[1]);
                if (cand == null) continue;
                if (best == null || J3_WITHIN_QUOTATION.compare(cand, best) < 0) best = cand;
            }
            if (best != null) {
                basisLineItemId = best.id;
                basisCurrentSubtotal = best.subtotal;
            }
        }

        // repair-0807 FR-5（D-1）：只有判断依据行跑一次整体 dryRun 试算，其余行不试算——
        // 现网该料号 25+ 张活单，逐单试算会把抽屉打开耗时拉到 40s+，不可接受。
        BigDecimal adjustedTotal = null;
        boolean basisComputed = false;
        if (basisLineItemId != null && tgt != null) {
            adjustedTotal = safeDryRunAdjustedSubtotal(r.id, r.basisQuotationId, basisLineItemId, tgt.id, null);
            if (adjustedTotal != null && basisCurrentSubtotal != null) {
                basisComputed = true;
            } else {
                LOG.warnf("[price-adjust-review] review=%s dryRun 试算失败或依据单现值缺失，"
                    + "adjustedComputed=false（降级为「未试算」，不阻断）", r.id);
            }
        } else {
            LOG.warnf("[price-adjust-review] review=%s 判断依据单缺失/该单不在活单范围内，adjustedComputed=false", r.id);
        }
        BigDecimal unitPriceImpactTotal = basisComputed
            ? adjustedTotal.subtract(basisCurrentSubtotal) : BigDecimal.ZERO;
        dto.elementImpactTotal = unitPriceImpactTotal;

        // repair-0807 FR-6（D-5）：逐元素影响。单元素版本数学上必然等于整体 Δ，省 N-1 次 dryRun；
        // 多元素版本才逐个跑"只改该元素、其余沿用上一版价"的 dryRun。
        if (basisComputed) {
            if (versionItems.size() == 1) {
                ReviewDetailDTO.ElementChange only = dto.elementChanges.get(0);
                only.unitPriceImpact = unitPriceImpactTotal;
                only.usageQty = computeUsageQty(only.unitPriceImpact, only.currentPrice, only.previousPrice);
            } else if (versionItems.size() > 1) {
                BigDecimal sumImpact = BigDecimal.ZERO;
                for (int i = 0; i < versionItems.size(); i++) {
                    ElementPriceVersionItem it = versionItems.get(i);
                    ReviewDetailDTO.ElementChange ec = dto.elementChanges.get(i);
                    Map<String, ElementPrice> overridePrices = buildOverridePricesForElement(versionItems, it.elementCode);
                    BigDecimal adjustedX = safeDryRunAdjustedSubtotal(r.id, r.basisQuotationId, basisLineItemId, tgt.id, overridePrices);
                    if (adjustedX != null) {
                        ec.unitPriceImpact = adjustedX.subtract(basisCurrentSubtotal);
                        sumImpact = sumImpact.add(ec.unitPriceImpact);
                    }
                    ec.usageQty = computeUsageQty(ec.unitPriceImpact, ec.currentPrice, ec.previousPrice);
                }
                // D-6：合计始终取整体 Δ（不取 Σ 明细）；两者差 > 0.01 落 WARN，不阻断、不改数——
                // 卡片对价格非线性时（阶梯/条件公式）两者本就不必相等，那正是要知道的事。
                BigDecimal diff = sumImpact.subtract(unitPriceImpactTotal).abs();
                if (diff.compareTo(ELEMENT_IMPACT_RECONCILE_THRESHOLD) > 0) {
                    LOG.warnf("[price-adjust-review] review=%s 元素影响明细合计 %s 与整体 Δ %s 不等"
                        + "（卡片对价格非线性，页面数值仍取整体 Δ）", r.id, sumImpact, unitPriceImpactTotal);
                }
            }
        }

        dto.quotations = new ArrayList<>();
        for (Object[] row : rows) {
            UUID quotationId = (UUID) row[0];
            UUID lineItemId = (UUID) row[1];
            Quotation q = qMap.get(quotationId);
            QuotationLineItem li = liMap.get(lineItemId);
            if (q == null || li == null) continue;
            ReviewDetailDTO.QuotationRef qr = new ReviewDetailDTO.QuotationRef();
            qr.quotationId = q.id;
            qr.quotationNo = q.quotationNumber;
            qr.createdAt = q.createdAt;
            qr.status = q.status;
            qr.isBasis = q.id.equals(r.basisQuotationId);
            qr.quoteSubtotalCurrent = li.subtotal;
            if (qr.isBasis && basisComputed) {
                qr.quoteSubtotalAdjusted = adjustedTotal;
                qr.adjustedComputed = true;
            } else {
                qr.quoteSubtotalAdjusted = null;
                qr.adjustedComputed = false;
            }
            qr.comparisonViewUrl = "/quotations/" + q.id + "/comparison";
            dto.quotations.add(qr);
        }

        return dto;
    }

    /**
     * repair-0807 FR-6（D-5）：为「只改元素 targetElementCode、其余沿用上一版价」的假设构造覆盖 map——
     * target 用 {@code currentPrice}，其余元素用 {@code previousPrice}；{@code previousPrice} 为
     * null 的元素直接不放进 map（= 不动该元素，语义与 {@code MaterialVersionUpgradeService} S1
     * 的"元素不在 map 里 = 一个字节都不碰"完全一致）。target 自身 {@code currentPrice} 为 null 时
     * 同样不放入（该元素本就无本期价，无法模拟"只改它"）。
     */
    Map<String, ElementPrice> buildOverridePricesForElement(List<ElementPriceVersionItem> versionItems, String targetElementCode) {
        Map<String, ElementPrice> out = new LinkedHashMap<>();
        for (ElementPriceVersionItem it : versionItems) {
            if (targetElementCode.equals(it.elementCode)) {
                if (it.currentPrice != null) out.put(it.elementCode, new ElementPrice(it.currentPrice, it.currency));
            } else if (it.previousPrice != null) {
                out.put(it.elementCode, new ElementPrice(it.previousPrice, it.currency));
            }
        }
        return out;
    }

    /** FR-6：usageQty = unitPriceImpact ÷ (currentPrice − previousPrice)，分母为 0 / 任一侧 null → null。 */
    BigDecimal computeUsageQty(BigDecimal unitPriceImpact, BigDecimal currentPrice, BigDecimal previousPrice) {
        if (unitPriceImpact == null || currentPrice == null || previousPrice == null) return null;
        BigDecimal denom = currentPrice.subtract(previousPrice);
        if (denom.compareTo(BigDecimal.ZERO) == 0) return null;
        return unitPriceImpact.divide(denom, 6, RoundingMode.HALF_UP);
    }

    /**
     * {@link #dryRunAdjustedSubtotal} 的降级包装：任何异常都不得让审核抽屉 500（api.md §1.3
     * 「dryRun 失败必须降级为 200 + 留白，不得 500」）——抽屉是财务的只读看板，一次试算失败不该
     * 卡住整个审核流程。
     */
    private BigDecimal safeDryRunAdjustedSubtotal(UUID reviewId, UUID basisQuotationId, UUID lineItemId,
                                                    UUID targetVersionId, Map<String, ElementPrice> overridePrices) {
        // task-260920 B-15①：抽屉里的元素影响试算也是一次对依据单的 dryRun 升版 —— 每次调用前取该单的依据单锁，
        // 与后台试算 / 正式升版错开（本方法不在事务里，取锁前无任何写库）。
        try (BasisQuotationLocks.Handle lock = quotationLocks.acquire(basisQuotationId, "review-detail review=" + reviewId)) {
            return dryRunAdjustedSubtotal(lineItemId, targetVersionId, overridePrices);
        } catch (Exception e) {
            LOG.warnf(e, "[price-adjust-review] review=%s dryRun 试算异常，降级为未试算: %s", reviewId, e.getMessage());
            return null;
        }
    }

    /**
     * repair-0807 FR-5/FR-6：隔离子事务 dryRun 试算，返回升版后的 {@code li.subtotal}
     * （{@code overridePrices==null} 时按目标版本全量明细试算；非 null 时按传入的元素价 map 试算，
     * 供 FR-6 逐元素影响分解）。模式抄 {@code PriceAdjustBudgetService#runDryRunSnapshot}。
     *
     * <p>🔒 <b>@ActivateRequestContext 补在 REQUIRES_NEW 事务边界本身</b>——
     * {@code upgrade(dryRun=true)} → S5 → {@code BomTreeRenderService.render()} →
     * {@code DataLoader}（{@code @RequestScoped}）。漏补时 GET 请求线程本身虽有 request context，
     * 但嵌套进新事务的这一层不会自动继承（与 {@code PriceAdjustBudgetService#runDryRunSnapshot:437-454}
     * 记录的教训完全一致：272 次/34 项全抛 {@code ContextNotActiveException} 且被静默吞掉）。
     *
     * @return null = dryRun 未成功（找不到行/版本、FAILED、CONFLICT），调用方须降级为「未试算」
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    BigDecimal dryRunAdjustedSubtotal(UUID lineItemId, UUID targetVersionId, Map<String, ElementPrice> overridePrices) {
        if (lineItemId == null || targetVersionId == null) return null;
        UpgradeResult ur = materialVersionUpgradeService.upgrade(lineItemId, targetVersionId, true, null, overridePrices);
        if (ur == null || (ur.status != UpgradeResult.Status.SUCCESS && ur.status != UpgradeResult.Status.SKIPPED)) {
            return null;
        }
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        return li != null ? li.subtotal : null;
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

        // repair-0807 FR-7：先收集全部 review 的 findAllActiveLines 结果，再一次批量查 Quotation
        // （不要每个 review 各查一次 Quotation.findById——N+1，CLAUDE.md 硬规）。
        List<MaterialPriceReview> reviews = new ArrayList<>();
        Map<UUID, List<Object[]>> activeRowsByReview = new LinkedHashMap<>();
        Set<UUID> qidsToLoad = new LinkedHashSet<>();
        for (UUID reviewId : reviewIds) {
            MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
            if (r == null) continue;
            reviews.add(r);
            List<Object[]> activeRows = findAllActiveLines(r.customerNo, r.materialNo);
            activeRowsByReview.put(r.id, activeRows);
            for (Object[] row : activeRows) qidsToLoad.add((UUID) row[0]);
        }
        Map<UUID, Quotation> qMap = qidsToLoad.isEmpty() ? Map.of()
            : Quotation.<Quotation>list("id in ?1", new ArrayList<>(qidsToLoad)).stream()
                .collect(Collectors.toMap(x -> x.id, x -> x));

        int materialCount = 0;
        for (MaterialPriceReview r : reviews) {
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

            // 活单：计入 quotationCount + byStatus（Quotation 已批量加载，内存分发）
            for (Object[] row : activeRowsByReview.getOrDefault(r.id, List.of())) {
                UUID quotationId = (UUID) row[0];
                if (allQuotationIds.add(quotationId)) {
                    Quotation q = qMap.get(quotationId);
                    if (q != null) byStatus.merge(q.status, 1, Integer::sum);
                }
            }
            // 非活单（SENT/ACCEPTED/EXPIRED/CANCELLED）：明示不会被更新（SQL 已直接带出 status，无需再查）
            for (Object[] row : findAllExcludedLines(r.customerNo, r.materialNo)) {
                UUID quotationId = (UUID) row[0];
                String status = (String) row[1];
                if (excludedQuotationIds.add(quotationId)) {
                    excludedByStatus.merge(status, 1, Integer::sum);
                }
            }
        }

        // task-260920 B-9：逐料号 col-default 金额 —— 一条 review_id IN 批量取（🚫 在上面的逐审核循环里加查询）
        result.materials = new ArrayList<>();
        if (!reviews.isEmpty()) {
            List<UUID> rids = reviews.stream().map(x -> x.id).toList();
            Map<UUID, MaterialPriceReviewColumn> colByReview = new LinkedHashMap<>();
            for (MaterialPriceReviewColumn c : MaterialPriceReviewColumn.<MaterialPriceReviewColumn>list(
                    "reviewId in ?1 and columnId = ?2", rids, "col-default")) {
                colByReview.put(c.reviewId, c);
            }
            for (MaterialPriceReview r : reviews) { // pure in-memory
                MaterialPriceReviewColumn c = colByReview.get(r.id);
                ImpactResultDTO.MaterialAmount ma = new ImpactResultDTO.MaterialAmount();
                ma.materialNo = r.materialNo;
                if (c != null) {
                    ma.quoteCostCurrent = c.quoteCurrent;
                    ma.quoteCostAdjusted = c.quoteAdjusted;
                    ma.diffAdjusted = c.diffAdjusted;
                    ma.status = c.status;
                    ma.missingSide = c.missingSide;
                }
                result.materials.add(ma);
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
        // repair-260918 B-10：异步派发的异常补记 ERROR 日志（原先被 CompletableFuture 静默吞掉）。
        managedExecutor.runAsync(() -> jobExecutionService.executeJob(jobId))
            .exceptionally(t -> {
                LOG.errorf(t, "[price-adjust] approve jobId=%s 异步执行异常", jobId);
                return null;
            });
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
        // task-260920 B-18（D-6 / D-11）：未算出金额的行（QUEUED / COMPUTING / FAILED）不能驳回 —— 整批拒绝，
        // 与 approve 同一异常类与信封（409 REVIEW_BUDGET_NOT_READY + invalidItems）。审核行一条 IN 批量取。
        List<MaterialPriceReview> loaded = MaterialPriceReview.list("id in ?1", new ArrayList<>(new LinkedHashSet<>(req.reviewIds)));
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (MaterialPriceReview r : loaded) { // pure in-memory
            if (MaterialPriceReview.BUDGET_READY.equals(r.budgetStatus)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reviewId", r.id);
            item.put("materialNo", r.materialNo);
            item.put("reason", MaterialPriceReview.BUDGET_FAILED.equals(r.budgetStatus)
                ? "计算失败(" + (r.budgetError != null ? r.budgetError : "") + ")"
                : "预算未算完(" + r.budgetStatus + ")");
            invalid.add(item);
        }
        if (!invalid.isEmpty()) {
            throw new ReviewNotReadyException("REVIEW_BUDGET_NOT_READY", "部分料号尚未算出金额，整批拒绝", invalid);
        }
        for (MaterialPriceReview r : loaded) {
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

    /**
     * §2.6 既有「重算」：签名与异步语义不变。task-260920 B-15：置「计算中」由无条件改为条件抢占
     * （QUEUED / FAILED / READY → COMPUTING，按统一次序先取依据单锁），已在算则不重复发起；试算与后台循环同一份实现。
     */
    public void recomputeBudget(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) throw new BusinessException(404, "review 不存在: " + reviewId);
        String st = budgetService.requestCompute(reviewId, PriceAdjustBudgetService.ComputeMode.RECOMPUTE);
        LOG.infof("[price-adjust] recomputeBudget review=%s material=%s 受理后状态=%s", reviewId, r.materialNo, st);
    }

    @Inject PriceAdjustBudgetService budgetService;
    @Inject BasisQuotationLocks quotationLocks;

    /** J-3 同单内依据行次序：sort_order 升序（空值排后）→ 行 id 升序（与 SQL {@code ORDER BY li.sort_order ASC NULLS LAST, li.id ASC} 一致，uuid 按 PG 字节序比较）。 */
    private static final java.util.Comparator<QuotationLineItem> J3_WITHIN_QUOTATION =
        java.util.Comparator.comparing((QuotationLineItem li) -> li.sortOrder,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(li -> li.id.toString());

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
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno " +
                "AND (li.composite_type IS NULL OR li.composite_type <> 'PART') " +
                "AND q.status = ANY(:statuses)")
            .setParameter("cno", customerNo).setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> findAllExcludedLines(String customerNo, String materialNo) {
        return em.createNativeQuery(
                "SELECT q.id, q.status FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno " +
                "AND (li.composite_type IS NULL OR li.composite_type <> 'PART') " +
                "AND NOT (q.status = ANY(:statuses))")
            .setParameter("cno", customerNo).setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
    }
}
