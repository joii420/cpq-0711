package com.cpq.quotation.service;

import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.ComponentDriverService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * task-0806 · 守卫 1：driver 组件维度审计器（需求文档 FR-4，backtask §3.2）。
 *
 * <p>输入某模板 / 某 driver 组件，输出其{@link BatchSafetyLevel 批量安全级别}——供
 * {@code PriceAdjustJobExecutionService} 的批量预渲染分组决策使用（先有安全网，再动数据路径，
 * 需求文档 D-8）。
 *
 * <p>判定口径（§5.2，唯一实现）：
 * <ol>
 *   <li>{@code data_driver_path} 解析不出 {@code $view} 名 → {@link BatchSafetyLevel#PER_LINE_ITEM}
 *       （保守兜底，绝不猜）；</li>
 *   <li>按 {@code componentId + sqlViewName} 精确查 {@link ComponentSqlView}（与
 *       {@code ComponentDriverService#viewHasNoRowDimension} 同款查法——同名视图跨组件会串号，
 *       见记忆 {@code cpq-sqlview-cache-key-needs-component-dim}），查不到 → PER_LINE_ITEM；</li>
 *   <li>{@code sql_template} 含 {@code :quotationId} 或 {@code :lineItemId} → PER_LINE_ITEM
 *       （最不安全，优先判定，与是否同时含 priceBaseDate 无关）；</li>
 *   <li>否则含 {@code :priceBaseDate} → PER_PRICE_BASE_DATE；</li>
 *   <li>否则 → GLOBAL。</li>
 * </ol>
 *
 * <p>🔒 <b>只读，不缓存</b>——组件的 {@code sql_template} 随时可能被用户在「组件管理」改动
 * （如加了 {@code :quotationId}），下一次 job 执行必须读到最新判定，缓存会让守卫本身变成新的
 * 串号风险源（与 D-5 的告警"看似有效实则不生效"同一类教训）。审计对象是驱动渲染的组件配置，
 * 单次 job 内组件数最多几十个，直接查库开销可忽略。
 */
@ApplicationScoped
public class DriverBatchSafetyAuditor {

    private static final Logger LOG = Logger.getLogger(DriverBatchSafetyAuditor.class);

    @Inject
    EntityManager em;

    /**
     * 单组件判定（§5.2 判定表的唯一实现）。
     *
     * @param componentId    组件 ID（用于精确查 {@link ComponentSqlView}，避免同名视图跨组件串号）
     * @param dataDriverPath {@code component.data_driver_path}，可为 {@code null}/空
     */
    public BatchSafetyLevel classifyComponent(UUID componentId, String dataDriverPath) {
        if (dataDriverPath == null || dataDriverPath.isBlank()) {
            LOG.warnf("[batch-safety] component=%s data_driver_path 为空 -> PER_LINE_ITEM（保守兜底）", componentId);
            return BatchSafetyLevel.PER_LINE_ITEM;
        }
        String viewName = ComponentDriverService.extractSqlViewName(dataDriverPath);
        if (viewName == null) {
            LOG.warnf("[batch-safety] component=%s driverPath=%s 非 $view 形态，解析不出视图名 -> PER_LINE_ITEM（保守兜底）",
                componentId, dataDriverPath);
            return BatchSafetyLevel.PER_LINE_ITEM;
        }
        ComponentSqlView v;
        try {
            v = ComponentSqlView.find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        } catch (Exception e) {
            LOG.warnf(e, "[batch-safety] component=%s viewName=%s 查询 component_sql_view 异常 -> PER_LINE_ITEM（保守兜底）",
                componentId, viewName);
            return BatchSafetyLevel.PER_LINE_ITEM;
        }
        if (v == null || v.sqlTemplate == null) {
            LOG.warnf("[batch-safety] component=%s viewName=%s 读不到视图/sql_template 为空 -> PER_LINE_ITEM（保守兜底）",
                componentId, viewName);
            return BatchSafetyLevel.PER_LINE_ITEM;
        }
        String tpl = v.sqlTemplate;
        if (tpl.contains(":quotationId") || tpl.contains(":lineItemId")) {
            LOG.warnf("[batch-safety] component=%s viewName=%s 含 :quotationId/:lineItemId -> PER_LINE_ITEM 强制逐项", componentId, viewName);
            return BatchSafetyLevel.PER_LINE_ITEM;
        }
        if (tpl.contains(":priceBaseDate")) {
            return BatchSafetyLevel.PER_PRICE_BASE_DATE;
        }
        return BatchSafetyLevel.GLOBAL;
    }

    /**
     * 某模板全部 driver 组件（{@code component.data_driver_path} 非空）的批量安全级别。
     * 查询与 {@link BomTreeRenderService#renderInternal} §②-pre 的 driver 组件清单同款
     * （{@code template_component JOIN component}），保证审计对象与实际渲染对象一致。
     *
     * @return componentId(字符串,对齐 render() baseRows 的 key 形态) → 安全级别，按 template_component 顺序
     */
    public Map<String, BatchSafetyLevel> classifyTemplateDriverComponents(UUID templateId) {
        Map<String, BatchSafetyLevel> out = new LinkedHashMap<>();
        if (templateId == null) return out;
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT c.id, c.data_driver_path FROM template_component tc " +
                "JOIN component c ON c.id = tc.component_id " +
                "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
            .setParameter("tid", templateId)
            .getResultList();
        for (Object[] row : rows) {
            if (row == null || row[0] == null) continue;
            UUID componentId = (UUID) row[0];
            String driverPath = (String) row[1];
            out.put(componentId.toString(), classifyComponent(componentId, driverPath));
        }
        return out;
    }

    /**
     * 模板整体的批量安全级别 = 全部 driver 组件里"最不安全"的那个
     * （{@link BatchSafetyLevel} 枚举声明顺序即不安全程度升序）。
     *
     * <p>供 T3（方案 B，粗粒度：整模板要么一次批量渲染、要么整批回退逐项）判定"这个
     * {@code (templateId, priceBaseDate)} 分组能不能整体批量渲染"；T5（方案 A，细粒度分层调度）
     * 改用 {@link #classifyTemplateDriverComponents} 逐组件判定，不经过本方法。
     *
     * <p>空模板（无 driver 组件）视为 {@link BatchSafetyLevel#GLOBAL}（无东西可渲染，批不批都一样安全）。
     */
    public BatchSafetyLevel worstLevelForTemplate(UUID templateId) {
        BatchSafetyLevel worst = BatchSafetyLevel.GLOBAL;
        for (BatchSafetyLevel level : classifyTemplateDriverComponents(templateId).values()) {
            if (level.ordinal() > worst.ordinal()) worst = level;
        }
        return worst;
    }
}
