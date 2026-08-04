package com.cpq.priceadjust.service;

import com.cpq.costing.service.ComparisonViewService;
import com.cpq.priceadjust.dto.ComparisonColumnDef;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.ComparisonColumnConfig;
import com.cpq.priceadjust.entity.CustomerPriceAdjustMaterial;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceReviewColumn;
import com.cpq.priceadjust.entity.MaterialPriceVersionRef;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * task-0729 B4 · 预算计算（异步）+ 比对算法。
 *
 * <p>入口 {@link #onVersionGenerated}：由 {@link PriceAdjustVersionGenerationService
 * #generateVersionAndEnqueueBudget} 在版本生成事务提交后经 {@code ManagedExecutor.runAsync}
 * 调用（无请求上下文的后台线程，故本方法及内部转发的 {@code @Transactional} 方法都需要能在
 * 该线程上工作——本类不用请求作用域 bean，仅 {@code @ActivateRequestContext} 保证 CDI 正常）。
 *
 * <p>逐料号独立事务（{@code REQUIRES_NEW}）处理，单料号失败不连累同版其它料号
 * （backtask B4.4「单料号预算失败不连累同版其他料号」）。
 */
@ApplicationScoped
public class PriceAdjustBudgetService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustBudgetService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ACTIVE_STATUSES = MaterialVersionUpgradeService.ACTIVE_STATUSES;

    @Inject EntityManager em;
    @Inject ComparisonViewService comparisonViewService;
    @Inject MaterialVersionUpgradeService materialVersionUpgradeService;

    @ActivateRequestContext
    public void onVersionGenerated(UUID versionId) {
        ScopeContext ctx = loadScopeContext(versionId);
        if (ctx == null) {
            LOG.warnf("[price-adjust-budget] versionId=%s 找不到版本或策略，跳过", versionId);
            return;
        }
        LOG.infof("[price-adjust-budget] onVersionGenerated versionId=%s customer=%s materials=%d",
            versionId, ctx.customerNo, ctx.materials.size());
        int poolCount = 0, advancedCount = 0, failCount = 0;
        for (String materialNo : ctx.materials) {
            try {
                boolean entered = processMaterial(versionId, ctx.customerNo, ctx.costDiffThreshold, materialNo);
                if (entered) poolCount++; else advancedCount++;
            } catch (Exception e) {
                failCount++;
                LOG.errorf(e, "[price-adjust-budget] versionId=%s material=%s 处理失败", versionId, materialNo);
            }
        }
        LOG.infof("[price-adjust-budget] versionId=%s done: 进池=%d 直接推进指针=%d 异常=%d",
            versionId, poolCount, advancedCount, failCount);
    }

    // -------------------------------------------------------------------------
    // 范围解析（B4.1 前置）
    // -------------------------------------------------------------------------

    private static final class ScopeContext {
        String customerNo;
        BigDecimal costDiffThreshold;
        List<String> materials;
    }

    @Transactional
    ScopeContext loadScopeContext(UUID versionId) {
        ElementPriceVersion version = ElementPriceVersion.findById(versionId);
        if (version == null) return null;
        CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(version.customerNo);
        if (strategy == null) return null;

        ScopeContext ctx = new ScopeContext();
        ctx.customerNo = version.customerNo;
        ctx.costDiffThreshold = strategy.costDiffThreshold;
        ctx.materials = resolveScopeMaterials(strategy);
        return ctx;
    }

    /**
     * 策略料号范围解析：SPECIFIED → customer_price_adjust_material 清单；
     * ALL → 该客户历史报价单里出现过的全部销售料号（{@code quotation_line_item
     * .product_part_no_snapshot} 去重）——backtask/需求说明未给出 ALL 模式的具体数据源
     * SQL，本实现按"该客户实际报过价的料号集合"这一最贴合业务语义的口径落地，已在交付
     * 说明中如实标注为一个需确认的假设（非凭空捏造字段，取的是既有列）。
     */
    List<String> resolveScopeMaterials(CustomerPriceAdjustStrategy strategy) {
        if ("SPECIFIED".equals(strategy.materialScopeMode)) {
            List<CustomerPriceAdjustMaterial> rows = CustomerPriceAdjustMaterial.listByStrategy(strategy.id);
            List<String> out = new ArrayList<>();
            for (CustomerPriceAdjustMaterial m : rows) out.add(m.materialNo);
            return out;
        }
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery(
                "SELECT DISTINCT li.product_part_no_snapshot " +
                "FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot IS NOT NULL")
            .setParameter("cno", strategy.customerNo)
            .getResultList();
        return rows;
    }

    // -------------------------------------------------------------------------
    // 逐料号处理（B4.1 成员判定 + 指针推进/进池）
    // -------------------------------------------------------------------------

    /**
     * @return true=进入待办池（已建 review 行）；false=无活单直接推进指针，未进池
     *
     * <p>repair-0803（BL-0108 同根因续集）：{@code @ActivateRequestContext} 补在本方法
     * （REQUIRES_NEW 事务边界本身）上——与 {@link PriceAdjustJobExecutionService#executeItem}
     * 的修法完全同一套模式（详见该方法 javadoc）。本方法有 4 个调用方（{@link #onVersionGenerated}
     * / {@code PriceAdjustReviewService#recomputeSingleReview} /
     * {@code PriceAdjustComparisonColumnService} / {@code PriceAdjustStrategyService}），其中
     * 后两个**直接**通过 {@code managedExecutor.runAsync} 调用本方法，完全绕开前两者身上挂的
     * {@code @ActivateRequestContext}——挂在调用方各自补一遍必然漏，挂在本方法（唯一收敛点）
     * 一次性覆盖全部 4 个入口。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean processMaterial(UUID versionId, String customerNo, BigDecimal costDiffThreshold, String materialNo) {
        ElementPriceVersion version = ElementPriceVersion.findById(versionId);
        if (version == null) return false;

        MaterialPriceVersionRef ref = MaterialPriceVersionRef.findRef(customerNo, materialNo);
        UUID previousVersionId = ref != null ? ref.versionId : null;
        if (previousVersionId != null && previousVersionId.equals(versionId)) {
            return false; // 已经指向本版本，无需重复处理
        }

        boolean hasRejectedHistory = MaterialPriceReview.hasEverRejected(customerNo, materialNo);
        BasisLine basis = findBasisLine(customerNo, materialNo);

        // D5：无活单料号 —— 不进待办池，指针照常推进（除非 D5 反例外：存在过 REJECTED 记录）
        if (basis == null && !hasRejectedHistory) {
            advancePointer(customerNo, materialNo, versionId);
            return false;
        }

        // 进池：建 review 行 + 走预算计算（B4.2 + B4.3）。
        // 🔒 find-or-create：uq_mpr_version_material UNIQUE(version_id, material_no) 决定这里必须
        // 幂等——recomputeBudget（B5 §2.6）会用同一 versionId+materialNo 重跑本方法，若无条件 new+
        // persist 会撞唯一约束；命中既有行时原地刷新字段，不新建第二条。
        MaterialPriceReview review = MaterialPriceReview.findByVersionAndMaterial(versionId, materialNo);
        if (review == null) {
            review = new MaterialPriceReview();
            review.versionId = versionId;
            review.customerNo = customerNo;
            review.materialNo = materialNo;
            review.status = MaterialPriceReview.STATUS_PENDING;
        }
        review.previousVersionId = previousVersionId;
        review.budgetStatus = MaterialPriceReview.BUDGET_COMPUTING;
        review.budgetError = null;
        if (basis != null) {
            review.basisQuotationId = basis.quotationId;
            review.templateSeriesId = basis.templateSeriesId;
        }
        review.persist();

        if (basis == null) {
            // D5 反例外命中，但确实无活单可预算 —— 无可比对数据，READY 空列，交人工裁决
            review.budgetStatus = MaterialPriceReview.BUDGET_READY;
            review.columnCount = 0;
            review.persist();
            return true;
        }

        try {
            computeBudget(review, basis, versionId, costDiffThreshold);
        } catch (Exception e) {
            review.budgetStatus = MaterialPriceReview.BUDGET_FAILED;
            review.budgetError = e.getMessage();
            review.persist();
            LOG.errorf(e, "[price-adjust-budget] review=%s material=%s 预算计算失败", review.id, materialNo);
        }
        return true;
    }

    private void advancePointer(String customerNo, String materialNo, UUID versionId) {
        MaterialPriceVersionRef ref = MaterialPriceVersionRef.findRef(customerNo, materialNo);
        if (ref == null) {
            ref = new MaterialPriceVersionRef();
            ref.customerNo = customerNo;
            ref.materialNo = materialNo;
        }
        ref.versionId = versionId;
        ref.updatedAt = java.time.OffsetDateTime.now();
        ref.persist();
    }

    private static final class BasisLine {
        UUID quotationId;
        UUID lineItemId;
        UUID templateSeriesId;
    }

    /** 判断依据单：该料号建单日期倒序首张活单（ACTIVE_STATUSES）（E11-6）。 */
    private BasisLine findBasisLine(String customerNo, String materialNo) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT q.id, li.id, q.customer_template_id " +
                "FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno AND q.status = ANY(:statuses) " +
                "ORDER BY q.created_at DESC LIMIT 1")
            .setParameter("cno", customerNo)
            .setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        BasisLine bl = new BasisLine();
        bl.quotationId = (UUID) row[0];
        bl.lineItemId = (UUID) row[1];
        UUID templateId = (UUID) row[2];
        if (templateId != null) {
            Template t = Template.findById(templateId);
            bl.templateSeriesId = t != null ? t.templateSeriesId : null;
        }
        return bl;
    }

    // -------------------------------------------------------------------------
    // B4.2 dryRun 预算 + B4.3 比对差异/着色
    // -------------------------------------------------------------------------

    private void computeBudget(MaterialPriceReview review, BasisLine basis, UUID versionId, BigDecimal costDiffThreshold) {
        List<ComparisonColumnDef> columns = resolveComparisonColumns(review.customerNo, basis.templateSeriesId, costDiffThreshold);

        // 升版前「现值」：直接读当前已落库的卡片值，不重算（B4.3 取值口径与 task-0717 一致）。
        QuotationLineItem li = QuotationLineItem.findById(basis.lineItemId);
        ComparisonViewService.SideValues currentQuote = li != null ? comparisonViewService.extractSide(li.quoteCardValues) : null;
        ComparisonViewService.SideValues currentCosting = li != null ? comparisonViewService.extractSide(li.costingCardValues) : null;

        // 升版后「调整值」：隔离子事务跑 dryRun（🔒 禁止在预算阶段触发整单 ensureCardValues，硬约束4）。
        DryRunSnapshot snap = runDryRunSnapshot(basis.lineItemId, versionId);

        if (snap.upgradeResult == null || snap.upgradeResult.status == UpgradeResult.Status.FAILED
                || snap.upgradeResult.status == UpgradeResult.Status.CONFLICT) {
            review.budgetStatus = MaterialPriceReview.BUDGET_FAILED;
            review.budgetError = snap.upgradeResult != null ? snap.upgradeResult.message : "dryRun 升版预算未知失败";
            review.persist();
            return;
        }

        MaterialPriceReviewColumn.delete("reviewId", review.id);
        int breached = 0, amber = 0, missingCount = 0, stale = 0;
        int sortOrder = 0;
        for (ComparisonColumnDef col : columns) {
            ComparisonColumnEvaluator.ColumnEval curEval =
                ComparisonColumnEvaluator.evaluate(currentQuote, currentCosting, col);
            ComparisonColumnEvaluator.ColumnEval adjEval =
                ComparisonColumnEvaluator.evaluate(snap.quoteSide, snap.costingSide, col);

            MaterialPriceReviewColumn row = new MaterialPriceReviewColumn();
            row.reviewId = review.id;
            row.columnId = col.id;
            row.columnLabel = col.isProductTotal() ? "产品总价" : col.quoteLabel;
            row.threshold = col.threshold;
            row.sortOrder = sortOrder++;
            row.quoteCurrent = curEval.quoteVal;
            row.costingCurrent = curEval.costingVal;
            row.quoteAdjusted = adjEval.quoteVal;
            row.costingAdjusted = adjEval.costingVal;
            row.diffCurrent = curEval.diff;
            row.diffAdjusted = adjEval.diff;
            row.status = adjEval.status; // 汇总标记按"调整后"评估（屏3/4 关注升版后的结果）
            row.missingSide = adjEval.missingSide;
            row.persist();

            switch (adjEval.status) {
                case ComparisonColumnEvaluator.RED -> breached++;
                case ComparisonColumnEvaluator.MISSING -> { breached++; missingCount++; } // E4：MISSING 计入 breached
                case ComparisonColumnEvaluator.AMBER -> amber++;
                case ComparisonColumnEvaluator.STALE -> stale++; // 不计 breached/amber
                default -> { /* NORMAL 不计数 */ }
            }
        }

        review.breachedCount = breached;
        review.amberCount = amber;
        review.missingCount = missingCount;
        review.staleCount = stale;
        review.columnCount = columns.size();
        review.budgetStatus = MaterialPriceReview.BUDGET_READY;
        review.persist();

        LOG.infof("[price-adjust-budget] review=%s material=%s READY columns=%d breached=%d amber=%d missing=%d stale=%d",
            review.id, review.materialNo, columns.size(), breached, amber, missingCount, stale);
    }

    private static final class DryRunSnapshot {
        UpgradeResult upgradeResult;
        ComparisonViewService.SideValues quoteSide;
        ComparisonViewService.SideValues costingSide;
    }

    /**
     * 隔离子事务跑 dryRun 升版，事务内立即读出调整后的卡片值并解析为分离态 POJO（{@code SideValues}
     * 不持有任何 Hibernate 托管引用，可安全带出事务边界）；方法返回时子事务因 dryRun=true 已被
     * {@code upgrade()} 内部标记 rollback-only 自动回滚，不落任何痕迹。
     *
     * <p>repair-0803（BL-0108 同根因续集）：本方法内部调 {@code materialVersionUpgradeService
     * .upgrade(dryRun=true)} → S5 重算核价卡片 → {@code BomTreeRenderService.render()} →
     * {@code DataLoader}（{@code @RequestScoped}）。本方法又是**嵌套**在 {@link #processMaterial}
     * 的 REQUIRES_NEW 内的**第二层** REQUIRES_NEW（会挂起外层事务另开一个）——外层
     * {@code processMaterial} 补了 {@code @ActivateRequestContext} 不代表这一层自动继承，
     * 与 {@code executeItem} 的教训完全一致：**每一层 REQUIRES_NEW 边界都要单独补**，不能只补最外层。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    DryRunSnapshot runDryRunSnapshot(UUID lineItemId, UUID targetVersionId) {
        DryRunSnapshot snap = new DryRunSnapshot();
        snap.upgradeResult = materialVersionUpgradeService.upgrade(lineItemId, targetVersionId, true);
        if (snap.upgradeResult.status == UpgradeResult.Status.SUCCESS
                || snap.upgradeResult.status == UpgradeResult.Status.SKIPPED) {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li != null) {
                snap.quoteSide = comparisonViewService.extractSide(li.quoteCardValues);
                Quotation q = Quotation.findById(li.quotationId);
                if (q != null && q.costingCardTemplateId != null) {
                    snap.costingSide = comparisonViewService.extractSide(li.costingCardValues);
                }
            }
        }
        return snap;
    }

    // -------------------------------------------------------------------------
    // 比对列解析（B4.3「比对列定位」）
    // -------------------------------------------------------------------------

    /**
     * 料号 → 依据单 → quotation.customer_template_id → template_series_id → 查
     * comparison_column_config；未配则用默认「产品总价」列（kind=PRODUCT_TOTAL，
     * 不依赖 componentId，跨模板通用），阈值取策略的 cost_diff_threshold（E13）。
     */
    List<ComparisonColumnDef> resolveComparisonColumns(String customerNo, UUID templateSeriesId, BigDecimal costDiffThreshold) {
        if (templateSeriesId != null) {
            ComparisonColumnConfig cfg = ComparisonColumnConfig.find(customerNo, templateSeriesId);
            if (cfg != null && cfg.columns != null) {
                try {
                    JsonNode arr = MAPPER.readTree(cfg.columns);
                    if (arr.isArray() && arr.size() > 0) {
                        List<ComparisonColumnDef> out = new ArrayList<>();
                        for (JsonNode n : arr) {
                            out.add(MAPPER.treeToValue(n, ComparisonColumnDef.class));
                        }
                        return out;
                    }
                } catch (Exception e) {
                    LOG.warnf("[price-adjust-budget] comparison_column_config 解析失败 customer=%s series=%s: %s",
                        customerNo, templateSeriesId, e.getMessage());
                }
            }
        }
        ComparisonColumnDef def = new ComparisonColumnDef();
        def.id = "col-default";
        def.kind = "PRODUCT_TOTAL";
        def.sortOrder = 0;
        def.threshold = costDiffThreshold != null ? costDiffThreshold : BigDecimal.ZERO;
        List<ComparisonColumnDef> out = new ArrayList<>();
        out.add(def);
        return out;
    }
}
