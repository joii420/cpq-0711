package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.ExcelViewService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组件级样本卡片 + 试算服务（呼应模板级 {@link ExcelViewService#sampleCardsOfTemplate} /
 * {@link ExcelViewService#dryRunTabFormula}）。
 *
 * <p>样本卡片反查链路（spec 已验证可行）：
 * {@code QuotationLineComponentData.componentId == {id}} → 其 {@code lineItemId}
 * → {@link QuotationLineItem} → 其 {@code quotation}。返回候选样本卡片列表，供
 * {@code TabJoinFormulaDrawer} 选样本并对其试算。
 *
 * <p>试算 seam：组件上下文只改"如何找到一条引用本组件的样本 lineItem"，
 * 试算求值内核直接复用 {@link ExcelViewService#dryRunTabFormula}（其入参为 lineItemId，
 * 并不耦合 templateId——它从 lineItem 自身派生 templateId/componentData），不重写求值器。
 *
 * <p>无样本处理：无任何 componentData 引用本组件 → 样本卡片返回空列表；
 * 试算无 sample card → 返回 {"value":null,"errors":["试算不可用(无样本卡)..."]}（非 500）。
 */
@ApplicationScoped
public class ComponentSampleCardService {

    private static final Logger LOG = Logger.getLogger(ComponentSampleCardService.class);
    private static final int SAMPLE_LIMIT = 50;

    @Inject
    ExcelViewService excelViewService;

    /**
     * 行投影：用于样本卡片去重/排序的最小投影（lineItemId + sortOrder 占位）。
     * 仅承载反查所需字段，便于纯函数 {@link #projectionsToSampleCards} 单测。
     */
    public static final class CardRowProjection {
        public final UUID lineItemId;
        public final UUID quotationId;
        public final String quotationNo;
        public final String cardName;

        public CardRowProjection(UUID lineItemId, UUID quotationId, String quotationNo, String cardName) {
            this.lineItemId = lineItemId;
            this.quotationId = quotationId;
            this.quotationNo = quotationNo;
            this.cardName = cardName;
        }
    }

    /**
     * 反查引用本组件的报价行，构建候选样本卡片列表。
     *
     * <p>链路：componentData(componentId={id}) → distinct lineItemId → lineItem → quotation。
     * 无任何引用 → 返回空列表（抽屉据此禁用试算，仅允许保存表达式）。
     *
     * @return [{quotationId, quotationNo, lineItemId, cardName}]，最多 {@value #SAMPLE_LIMIT} 条
     */
    public List<Map<String, Object>> sampleCardsForComponent(UUID componentId) {
        if (componentId == null) return List.of();
        // 反查所有引用本组件的 componentData 行（按行创建序倒序，新数据优先）
        List<QuotationLineComponentData> cds = QuotationLineComponentData.list(
            "componentId = ?1 ORDER BY createdAt DESC", componentId);
        if (cds.isEmpty()) return List.of();

        List<CardRowProjection> projections = new ArrayList<>();
        // distinct lineItemId（保持倒序），并缓存 lineItem/quotation 避免重复查库
        Map<UUID, Boolean> seenLineItem = new LinkedHashMap<>();
        Map<UUID, QuotationLineItem> liCache = new LinkedHashMap<>();
        Map<UUID, Quotation> qCache = new LinkedHashMap<>();
        for (QuotationLineComponentData cd : cds) {
            UUID liId = cd.lineItemId;
            if (liId == null || seenLineItem.containsKey(liId)) continue;
            seenLineItem.put(liId, Boolean.TRUE);
            // Panache 坑：Entity::findById 方法引用编译成 invokedynamic，绑定到未增强的
            // PanacheEntityBase 占位方法 → 抛 "did you forget @Entity"。改用 lambda 包静态调用
            // （lambda 体内 Entity.findById(id) 是正常增强调用点，与 ExcelViewService 一致）。
            QuotationLineItem li = liCache.computeIfAbsent(liId, id -> QuotationLineItem.findById(id));
            if (li == null) continue;
            Quotation q = li.quotationId != null
                ? qCache.computeIfAbsent(li.quotationId, id -> Quotation.findById(id))
                : null;
            String quotationNo = q != null ? q.quotationNumber : null;
            String cardName = (li.productNameSnapshot != null && !li.productNameSnapshot.isBlank())
                ? li.productNameSnapshot
                : li.productPartNoSnapshot;
            projections.add(new CardRowProjection(li.id, li.quotationId, quotationNo, cardName));
        }
        return projectionsToSampleCards(projections, SAMPLE_LIMIT);
    }

    /**
     * 纯函数：行投影列表 → 样本卡片 DTO 列表（截断到 limit）。不依赖 DB，便于单测。
     */
    public static List<Map<String, Object>> projectionsToSampleCards(
            List<CardRowProjection> projections, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (projections == null) return result;
        for (CardRowProjection p : projections) {
            if (result.size() >= limit) break;
            if (p == null || p.lineItemId == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("quotationId", p.quotationId != null ? p.quotationId.toString() : null);
            entry.put("quotationNo", p.quotationNo);
            entry.put("lineItemId", p.lineItemId.toString());
            entry.put("cardName", p.cardName);
            result.add(entry);
        }
        return result;
    }

    /**
     * 组件级试算：构建求值上下文（component + 选定样本卡片）并复用模板级试算内核。
     *
     * <p>无样本卡片（lineItemId 缺省）→ 返回 {"value":null,"errors":["试算不可用(无样本卡)"]}（非 500）。
     *
     * @param componentId  当前编辑公式的组件（用于报错语境/将来校验，求值由 lineItem 派生上下文）
     * @param lineItemId   选定样本卡片的 lineItemId（可空 → 无样本）
     * @param column       TAB_JOIN_FORMULA 列配置
     * @param cardValuesJson 可选的前端未保存卡片值（透传给内核，与模板级一致）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dryRunForComponent(
            UUID componentId, UUID lineItemId, Map<String, Object> column, String cardValuesJson) {
        if (lineItemId == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", null);
            out.put("errors", List.of("试算不可用(无样本卡): 该组件尚无报价行引用，无法对真实卡片试算"));
            return out;
        }
        if (column == null) throw new BusinessException(400, "column is required");
        // seam：直接复用模板级求值内核——其入参为 lineItemId，从 lineItem 自身派生上下文，不耦合 templateId
        return excelViewService.dryRunTabFormula(lineItemId, column, cardValuesJson);
    }
}
