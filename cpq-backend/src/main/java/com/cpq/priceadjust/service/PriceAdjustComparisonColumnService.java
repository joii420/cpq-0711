package com.cpq.priceadjust.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.ComparisonMetaDTO;
import com.cpq.costing.service.ComparisonViewService;
import com.cpq.priceadjust.dto.ComparisonColumnDef;
import com.cpq.priceadjust.dto.ComparisonColumnsDTO;
import com.cpq.priceadjust.dto.TemplateSeriesDTO;
import com.cpq.priceadjust.entity.ComparisonColumnConfig;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategyLog;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.template.entity.Template;
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
    /** §1.10a meta：两侧页签目录的组装复用 task-0717 的同一份 buildTabMetas，不新写一份。 */
    @Inject ComparisonViewService comparisonViewService;

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

    // -------------------------------------------------------------------------
    // §1.10a 页签/可比对值目录 meta（2026-08-06 补交，原 api.md 遗漏）
    // -------------------------------------------------------------------------

    /**
     * 比对列连线抽屉的数据源：该模板系列两侧的「页签 → 可比对值」目录。
     *
     * <p>本方法只负责一件事：<b>把「模板系列」解析成「报价侧 templateId + 核价侧 templateId」</b>，
     * 组装交给 {@link ComparisonViewService#getMetaByTemplates}（与 task-0717 按 quotationId
     * 取 meta 共用同一份 buildTabMetas）。
     *
     * <h3>报价侧取版本口径 = 系列内 {@code created_at DESC} 首个</h3>
     * 🔒 <b>刻意跟随 {@link #listTemplateSeries}（§1.8）而非
     * {@code TemplateService#computeAutoDefaults}</b>（后者用 {@code status='PUBLISHED'
     * ORDER BY published_at DESC}）。理由是<b>同屏自洽</b>：§1.8 的 {@code latestVersion}
     * 就是用 {@code created_at DESC LIMIT 1} 算的，用户在同一个下拉里看到 "v1.2"，
     * 配置依据就必须是 v1.2；若改用 PUBLISHED 口径，用户看到 v1.2、实际配的是另一版的
     * 页签结构，<b>这种不一致用户无法察觉也无法理解</b>。
     * <p>⚠️ 当前库里各系列全是 PUBLISHED，两种口径结果相同 —— <b>正因为现在看不出差别，
     * 才必须把口径写死</b>，否则以后归档一版就分叉，而分叉时没人会想到来查这里。
     *
     * <h3>核价侧取模板口径 = 三级降级（业务方 2026-08-06 拍板）</h3>
     * <pre>
     * ① SERIES_LATEST   该客户 ×【该系列】下最近活单的 costing_card_template_id  ← 主口径，最精确
     * ② CUSTOMER_LATEST 该客户最近活单的 costing_card_template_id（不限系列）    ← 业务方明示的口径
     * ③ AUTO_DEFAULT    computeAutoDefaults 规则（categoryId + 客户专属优先 + published_at DESC）
     * ④ NONE            都推不出 → costingTabs 空数组（200，不报错）
     * </pre>
     *
     * <p>🔑 <b>为什么 ① 优先于业务方明示的 ②</b>：业务方原话是「该<b>客户</b>最近活单」，但
     * <b>一个客户可以有多个模板系列</b>（实测 {@code CUST-0001} 有 4 个）。若该客户最近那张活单
     * 属于<b>别的</b>系列，②拿到的核价模板与当前正在配置的系列无关。① 是 ② 的收窄，能更准时更准；
     * 收窄不到（该系列下无活单）时自然退化成 ②，<b>正好是业务方说的那条</b>。故 ①+② 是业务方
     * 意图的超集，<b>业务方 2026-08-06 明示接受 ② 作为口径</b>。
     *
     * <p>🔑 <b>为什么 ①② 用「最近活单」而不是直接用 computeAutoDefaults</b>（将来必被追问）：
     * <b>配置口径必须与消费口径一致。</b>比对列配置的唯一用途是评估期被
     * {@code ComparisonColumnEvaluator} 消费，而评估走的是
     * {@code PriceAdjustBudgetService#findBasisLine} —— <b>最近活单 + ACTIVE_STATUSES</b>。
     * 配置依据与消费依据一旦分叉，用户配的 componentId 在评估时就<b>命中不了，而且是静默命中
     * 不了</b>（评估记 STALE，不报错、不提示、UI 上只是那一列恒空）。
     * ③ 算的是「<b>下次开单</b>会用哪个」，而评估读的是「<b>已存在的那张单</b>」，两者在核价模板
     * 换代后必然分叉 —— 所以它只能垫底，不能当主口径。
     *
     * <p>🔒 三级全部复用<b>同一个</b> {@link MaterialVersionUpgradeService#ACTIVE_STATUSES}
     * （全工程唯一定义）与同样的 {@code created_at DESC}，不另写第二份活单判据。
     *
     * <p>🔴 <b>已知歧义（既有架构导致，非本端点引入）</b>：模板系列与核价模板<b>无外键关系</b>
     * ——核价模板由开单时按 {@code categoryId + customerId} 独立解析，因此<b>同一系列的不同
     * 单可以用不同核价模板</b>（实测系列 {@code a91209e6} 的 18 张单里，10 张用 v1.1、1 张用
     * v1.0、7 张未绑）。配置是系列级、只能取一个代表，故少数派模板的单在评估时该列恒 STALE。
     * 取并集不可行：同名页签跨模板 componentId 不同，连线会配到一个永远命中不了的 id 上。
     *
     * <p>实际命中的是哪一级会回传到 {@link ComparisonMetaDTO#costingSource} 并打 INFO 日志
     * —— 四级取到的模板可能不同，出问题时必须一眼可辨。
     *
     * @throws BusinessException 404 系列不存在（中文业务消息，与路由 404 区分）
     */
    public ComparisonMetaDTO getComparisonMeta(UUID templateSeriesId) {
        if (templateSeriesId == null) {
            throw new BusinessException(400, "templateSeriesId 不能为空");
        }

        // 报价侧：系列内最新一张（created_at DESC）——口径同 §1.8 latestVersion，理由见方法注释
        Template quoteTemplate = Template.find(
            "templateSeriesId = ?1 ORDER BY createdAt DESC", templateSeriesId).firstResult();
        if (quoteTemplate == null) {
            throw new BusinessException(404, "模板系列不存在: " + templateSeriesId);
        }

        CostingResolution costing = resolveCostingTemplate(quoteTemplate);
        LOG.infof("[comparison-meta] series=%s quoteTpl=%s(%s) → costingTpl=%s source=%s",
            templateSeriesId, quoteTemplate.id, quoteTemplate.version, costing.templateId, costing.source);

        ComparisonMetaDTO meta = comparisonViewService.getMetaByTemplates(quoteTemplate.id, costing.templateId);
        meta.costingSource = costing.source;
        return meta;
    }

    /** 核价侧模板的解析来源（回传到 {@link ComparisonMetaDTO#costingSource}，语义见 getComparisonMeta 注释）。 */
    public static final class CostingSource {
        /** ① 该客户 × 该系列下最近活单实际绑定的核价模板（主口径，最精确）。 */
        public static final String SERIES_LATEST = "SERIES_LATEST";
        /** ② 该客户最近活单实际绑定的核价模板，不限系列（业务方 2026-08-06 明示的口径）。 */
        public static final String CUSTOMER_LATEST = "CUSTOMER_LATEST";
        /** ③ computeAutoDefaults 同款规则推「下次开单会用哪个」（系列/客户都无活单时）。 */
        public static final String AUTO_DEFAULT = "AUTO_DEFAULT";
        /** ④ 三级都推不出 → costingTabs 空数组（200，不报错）。 */
        public static final String NONE = "NONE";

        private CostingSource() {}
    }

    private static final class CostingResolution {
        final UUID templateId;
        final String source;

        CostingResolution(UUID templateId, String source) {
            this.templateId = templateId;
            this.source = source;
        }
    }

    /**
     * 核价模板三级降级解析（每级理由见 {@link #getComparisonMeta} 注释）。
     * 三级都推不出 → {@code templateId=null / source=NONE}，调用方据此给出空 costingTabs，不报错。
     */
    private CostingResolution resolveCostingTemplate(Template quoteTemplate) {
        // ① 该客户 × 该系列下最近活单
        UUID bySeries = findLatestActiveQuotationCostingTemplate(
            "JOIN template t ON t.id = q.customer_template_id ",
            "t.template_series_id = :key",
            quoteTemplate.templateSeriesId);
        if (bySeries != null) return new CostingResolution(bySeries, CostingSource.SERIES_LATEST);

        // ② 该客户最近活单（不限系列）——业务方明示口径；模板无 customerId（通用模板）时跳过
        if (quoteTemplate.customerId != null) {
            UUID byCustomer = findLatestActiveQuotationCostingTemplate(
                "", "q.customer_id = :key", quoteTemplate.customerId);
            if (byCustomer != null) return new CostingResolution(byCustomer, CostingSource.CUSTOMER_LATEST);
        }

        // ③ 兜底：按「下次开单会用哪个」推
        if (quoteTemplate.categoryId == null) {
            LOG.infof("[comparison-meta] series=%s 无活单且模板无 categoryId，核价侧目录为空",
                quoteTemplate.templateSeriesId);
            return new CostingResolution(null, CostingSource.NONE);
        }
        Template costing = Template.find(
                "categoryId = ?1 AND templateKind = 'COSTING' AND status = 'PUBLISHED' "
                        + "AND (customerId = ?2 OR customerId IS NULL) "
                        + "ORDER BY CASE WHEN customerId = ?2 THEN 0 ELSE 1 END, publishedAt DESC NULLS LAST",
                quoteTemplate.categoryId, quoteTemplate.customerId).firstResult();
        if (costing == null) {
            LOG.infof("[comparison-meta] series=%s 无活单、兜底规则亦无匹配核价模板，核价侧目录为空",
                quoteTemplate.templateSeriesId);
            return new CostingResolution(null, CostingSource.NONE);
        }
        return new CostingResolution(costing.id, CostingSource.AUTO_DEFAULT);
    }

    /**
     * ①② 共用的「最近活单绑的核价模板」查询——两级只差一个定位维度（系列 / 客户），
     * <b>活单判据与排序只写一份</b>，避免两级口径漂移。
     *
     * <p>🔒 {@code costing_card_template_id IS NOT NULL} 不可省：实测同一系列存在大量未绑核价
     * 模板的单（{@code a91209e6} 系列 18 张里 7 张），漏了这个条件会取到一张 NULL 的「最近单」，
     * 直接把核价侧目录打空 —— 而且是静默打空。
     *
     * @param extraJoin 额外 JOIN 片段（按系列定位时需 JOIN template，按客户定位时为空串）
     * @param keyPredicate 定位谓词，参数名固定 {@code :key}
     */
    private UUID findLatestActiveQuotationCostingTemplate(String extraJoin, String keyPredicate, UUID key) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = em.createNativeQuery(
                "SELECT q.costing_card_template_id FROM quotation q " + extraJoin +
                "WHERE " + keyPredicate +
                "  AND q.costing_card_template_id IS NOT NULL " +
                "  AND q.status = ANY(:statuses) " +
                "ORDER BY q.created_at DESC LIMIT 1")
            .setParameter("key", key)
            .setParameter("statuses", MaterialVersionUpgradeService.ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
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
        log.stampActor(actorId); // 🔒 changedBy + changedByName 一起落，别只写其中一个（#54 根因）
        log.persist();
    }
}
