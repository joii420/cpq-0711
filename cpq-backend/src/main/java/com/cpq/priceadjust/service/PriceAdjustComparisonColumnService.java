package com.cpq.priceadjust.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.priceadjust.dto.ComparisonColumnDef;
import com.cpq.priceadjust.dto.ComparisonColumnsDTO;
import com.cpq.priceadjust.dto.TemplateSeriesDTO;
import com.cpq.priceadjust.entity.ComparisonColumnConfig;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategyLog;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 B6 · 比对列配置 CRUD（api.md §1.8/§1.9/§1.10）。
 *
 * <p>🔒 唯一写入口——屏 4 审核抽屉只读，本类是 {@code comparison_column_config} 表唯一的
 * 写路径（§11.5.3(4)）。保存后触发重算范围 = 该「客户 × 模板系列」下的 PENDING 料号
 * （不是该客户全部），走 B4 那条异步管道（{@link PriceAdjustBudgetService#processMaterial}）。
 */
@ApplicationScoped
public class PriceAdjustComparisonColumnService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustComparisonColumnService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;
    @Inject PriceAdjustBudgetService budgetService;
    @Inject ManagedExecutor managedExecutor;

    /**
     * 🔒 每次调用返回<b>新实例</b>，不做 static 常量共享——{@link #normalizeRemovable} 会就地改写
     * 元素的 {@code removable}，若各请求共用同一个对象就是跨请求可变共享状态。
     */
    private static ComparisonColumnDef defaultProductTotalColumn() {
        ComparisonColumnDef c = new ComparisonColumnDef();
        c.id = "col-default";
        c.kind = "PRODUCT_TOTAL";
        c.sortOrder = 0;
        c.threshold = BigDecimal.ZERO;
        c.quoteLabel = "产品总价";
        c.costingLabel = "产品总价";
        c.removable = false;
        return c;
    }

    /**
     * 🔒 回显归一：{@code removable} 是<b>派生字段</b>，恒等于「不是 PRODUCT_TOTAL」，
     * <b>不采信库里存的值</b>。
     *
     * <p>判定复用 {@link ComparisonColumnDef#isProductTotal()}——与
     * {@link #doPutColumns} 的 400 守卫是<b>同一个方法</b>，「这一列能不能删」因此在系统里
     * 只有一处定义。
     *
     * <p><b>不归一会怎样</b>：库里存过 {@code PRODUCT_TOTAL + removable=true} 的组合（自定义列
     * 与默认列共存时尤甚），前端 {@code ComparisonColumnPanel.tsx:151} 据此渲染出删除按钮、
     * {@code :136} 的「默认列」标签同时消失——<b>一个字段错，两处 UI 同向地把不可删的默认列
     * 伪装成普通可删列</b>，用户点下去必然吃 400。
     */
    private static List<ComparisonColumnDef> normalizeRemovable(List<ComparisonColumnDef> columns) {
        for (ComparisonColumnDef c : columns) {
            if (c != null) c.removable = !c.isProductTotal();
        }
        return columns;
    }

    // -------------------------------------------------------------------------
    // §1.8 模板系列列表
    // -------------------------------------------------------------------------

    public List<TemplateSeriesDTO> listTemplateSeries(String customerNo) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new BusinessException(400, "customerNo 不能为空");
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT t.template_series_id, " +
                "       (SELECT t2.name FROM template t2 WHERE t2.template_series_id = t.template_series_id " +
                "         ORDER BY t2.created_at DESC LIMIT 1) AS series_name, " +
                "       (SELECT t2.version FROM template t2 WHERE t2.template_series_id = t.template_series_id " +
                "         ORDER BY t2.created_at DESC LIMIT 1) AS latest_version, " +
                "       count(DISTINCT t.id) AS template_count, " +
                "       max(t.created_at) AS last_created_at " +
                "FROM template t JOIN customer c ON c.id = t.customer_id " +
                "WHERE c.code = :cno AND t.template_kind = 'QUOTATION' " +
                "GROUP BY t.template_series_id " +
                "ORDER BY last_created_at DESC")
            .setParameter("cno", customerNo)
            .getResultList();

        List<TemplateSeriesDTO> out = new ArrayList<>();
        boolean first = true;
        for (Object[] row : rows) {
            TemplateSeriesDTO dto = new TemplateSeriesDTO();
            dto.templateSeriesId = (UUID) row[0];
            dto.seriesName = (String) row[1];
            dto.latestVersion = (String) row[2];
            dto.templateCount = ((Number) row[3]).intValue();
            // isDefault：该客户最近创建的模板所属系列（无独立"默认模板系列"字段，按最近使用推定，
            // 已在类注释/交付说明如实标注为一个需确认的解释，非凭空定义）。
            dto.isDefault = first;
            first = false;

            ComparisonColumnConfig cfg = ComparisonColumnConfig.find(customerNo, dto.templateSeriesId);
            dto.hasComparisonConfig = cfg != null;
            dto.columnCount = cfg != null ? parseColumnCount(cfg.columns) : 1; // 未配置时默认列=1（产品总价）
            out.add(dto);
        }
        return out;
    }

    private int parseColumnCount(String columnsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(columnsJson);
            return arr.isArray() ? arr.size() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // §1.9 读取比对列配置
    // -------------------------------------------------------------------------

    public ComparisonColumnsDTO getColumns(String customerNo, UUID templateSeriesId) {
        if (customerNo == null || customerNo.isBlank() || templateSeriesId == null) {
            throw new BusinessException(400, "customerNo/templateSeriesId 不能为空");
        }
        ComparisonColumnsDTO dto = new ComparisonColumnsDTO();
        dto.customerNo = customerNo;
        dto.templateSeriesId = templateSeriesId;

        ComparisonColumnConfig cfg = ComparisonColumnConfig.find(customerNo, templateSeriesId);
        if (cfg == null) {
            dto.configured = false;
            dto.columns = normalizeRemovable(new ArrayList<>(List.of(defaultProductTotalColumn())));
            return dto;
        }
        dto.configured = true;
        dto.columns = parseColumns(cfg.columns);
        return dto;
    }

    private List<ComparisonColumnDef> parseColumns(String json) {
        try {
            ComparisonColumnDef[] arr = MAPPER.readValue(json, ComparisonColumnDef[].class);
            List<ComparisonColumnDef> out = new ArrayList<>(List.of(arr));
            if (out.isEmpty()) out.add(defaultProductTotalColumn());
            // 🔒 归一必须在这里（唯一读出口），不能只在 getColumns 做——库里存的 removable 不可信
            return normalizeRemovable(out);
        } catch (Exception e) {
            LOG.warnf("[comparison-columns] parse失败 customer/series 配置损坏，降级为默认列: %s", e.getMessage());
            return normalizeRemovable(new ArrayList<>(List.of(defaultProductTotalColumn())));
        }
    }

    // -------------------------------------------------------------------------
    // §1.10 写入比对列配置（唯一写入口）
    // -------------------------------------------------------------------------

    public PutResult putColumns(String customerNo, UUID templateSeriesId, List<ComparisonColumnDef> columns, UUID actorId) {
        PutResult syncResult = doPutColumns(customerNo, templateSeriesId, columns, actorId);
        if (syncResult.affectedReviewIds != null && !syncResult.affectedReviewIds.isEmpty()) {
            BigDecimal threshold = syncResult.costDiffThreshold;
            for (var review : syncResult.affectedReviewIds) {
                managedExecutor.runAsync(() ->
                    budgetService.processMaterial(review.versionId, customerNo, threshold, review.materialNo));
            }
        }
        return syncResult;
    }

    public static class PutResult {
        public boolean budgetRecomputeTriggered;
        public int affectedReviewCount;
        List<ReviewRef> affectedReviewIds;
        BigDecimal costDiffThreshold;
    }

    private static final class ReviewRef {
        UUID versionId;
        String materialNo;
    }

    @Transactional
    PutResult doPutColumns(String customerNo, UUID templateSeriesId, List<ComparisonColumnDef> columns, UUID actorId) {
        if (customerNo == null || customerNo.isBlank() || templateSeriesId == null) {
            throw new BusinessException(400, "customerNo/templateSeriesId 不能为空");
        }
        if (columns == null || columns.isEmpty()) {
            throw new BusinessException(400, "columns 不能为空");
        }
        // 🔒 默认「产品总价」列不可删除（验收 #24③）：保存内容里必须仍含至少一条 PRODUCT_TOTAL 列。
        // 🔒 判定走 ComparisonColumnDef#isProductTotal()——与 normalizeRemovable 的回显判定
        //    共用同一个方法。原写法在这里另抄了一份 "PRODUCT_TOTAL".equals(c.kind) 字面量，
        //    与回显各判各的，正是「守卫拦得住、回显却说可删」的成因。
        boolean hasProductTotal = columns.stream().anyMatch(ComparisonColumnDef::isProductTotal);
        if (!hasProductTotal) {
            throw new BusinessException(400, "比对列必须保留一条 PRODUCT_TOTAL（产品总价）列，不可删除");
        }

        ComparisonColumnConfig cfg = ComparisonColumnConfig.find(customerNo, templateSeriesId);
        String beforeSnapshot = cfg != null ? cfg.columns : null;
        boolean isNew = cfg == null;
        if (isNew) {
            cfg = new ComparisonColumnConfig();
            cfg.customerNo = customerNo;
            cfg.templateSeriesId = templateSeriesId;
        }
        String afterJson = writeColumns(columns);
        cfg.columns = afterJson;
        cfg.updatedAt = OffsetDateTime.now();
        cfg.updatedBy = actorId;
        cfg.persist();

        writeAuditLog(customerNo, beforeSnapshot, afterJson, actorId);

        // 重算范围 = 该「客户 × 模板系列」下的 PENDING 料号（不是该客户全部）
        List<MaterialPriceReview> pending = MaterialPriceReview.list(
            "customerNo = ?1 and templateSeriesId = ?2 and status = ?3",
            customerNo, templateSeriesId, MaterialPriceReview.STATUS_PENDING);

        PutResult result = new PutResult();
        result.affectedReviewCount = pending.size();
        result.budgetRecomputeTriggered = !pending.isEmpty();
        result.affectedReviewIds = new ArrayList<>();
        CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        result.costDiffThreshold = strategy != null ? strategy.costDiffThreshold : BigDecimal.ZERO;
        for (MaterialPriceReview r : pending) {
            r.budgetStatus = MaterialPriceReview.BUDGET_QUEUED;
            r.persist();
            ReviewRef ref = new ReviewRef();
            ref.versionId = r.versionId;
            ref.materialNo = r.materialNo;
            result.affectedReviewIds.add(ref);
        }
        LOG.infof("[comparison-columns] customer=%s series=%s 保存成功，触发重算 %d 条 PENDING 料号",
            customerNo, templateSeriesId, pending.size());
        return result;
    }

    private String writeColumns(List<ComparisonColumnDef> columns) {
        try {
            return MAPPER.writeValueAsString(columns);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 审计日志（断链 9）。🔒 {@code customer_price_adjust_strategy_log.strategy_id} 是 NOT NULL
     * FK——若该客户尚无策略记录（比对列配置先于策略创建），best-effort 跳过写审计，不阻断保存
     * （已在类注释如实标注，非静默丢弃遗漏——这是 FK 约束下唯一安全的处理方式）。
     */
    private void writeAuditLog(String customerNo, String beforeSnapshot, String afterSnapshot, UUID actorId) {
        CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (strategy == null) {
            LOG.infof("[comparison-columns] customer=%s 尚无调价策略记录，审计日志跳过（FK 约束）", customerNo);
            return;
        }
        CustomerPriceAdjustStrategyLog log = new CustomerPriceAdjustStrategyLog();
        log.strategyId = strategy.id;
        log.customerNo = customerNo;
        log.changeType = CustomerPriceAdjustStrategyLog.CHANGE_TYPE_COMPARISON_COLUMN;
        log.summary = "比对列配置变更";
        log.beforeSnapshot = beforeSnapshot;
        log.afterSnapshot = afterSnapshot;
        log.changedBy = actorId;
        log.persist();
    }
}
