package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.card.ComponentDataEffectiveRows;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Step3 行级折扣权威重算。
 *
 * <p>以 row_data 为真相源，按 discountSource / discountRateApplied 算折后小计与各金额字段，
 * 就地写入 {@link QuotationLineItem} 的 9 个折扣字段（不 persist，由调用方事务统一提交）。
 *
 * <p>调用时机：{@code QuotationService.submit} 在改状态为 SUBMITTED 之前，遍历全部行调用本方法，
 * 并汇总 {@code lineTotalAmount} → {@code quotation.totalAmount}。
 */
@ApplicationScoped
public class LineDiscountService {

    private static final String SUBTOTAL_SOURCE = "SUBTOTAL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    FormulaCalculator formulaCalculator;

    /**
     * 重算单行并就地写入 9 个金额字段（不 persist，由调用方事务统一提交）。
     *
     * <p>计算口径：
     * <ul>
     *   <li>S0 = 无折扣产品小计（subtotalWithDiscount(..., null, 1.0)）</li>
     *   <li>scale = 1 - discountRateApplied/100（null→0 → scale=1.0）</li>
     *   <li>source=null/""/SUBTOTAL → S1=S0*scale，base=S0</li>
     *   <li>source=页签code → S1=subtotalWithDiscount(..., source, scale)，base=该页签列和</li>
     *   <li>lineUnitPrice=S0，lineFinalPrice=S1，discountBaseAmount=base</li>
     *   <li>lineDiscountAmount=(S0-S1)*annualVolume，lineTotalAmount=S1*annualVolume</li>
     *   <li>全部 setScale(4, HALF_UP)</li>
     * </ul>
     */
    public void recompute(QuotationLineItem li) {
        List<QuotationLineComponentData> cdList =
            QuotationLineComponentData.list("lineItemId = ?1", li.id);
        Map<UUID, ComponentDataEffectiveRows.Meta> metaById = loadMetas(cdList);
        UUID subtotalCid = findSubtotalComponentId(metaById);

        // S0：无折扣产品小计
        BigDecimal s0;
        if (subtotalCid != null) {
            s0 = ComponentDataEffectiveRows.subtotalWithDiscount(
                cdList, metaById, subtotalCid, formulaCalculator, null, 1.0);
        } else {
            s0 = li.subtotal != null ? li.subtotal : BigDecimal.ZERO;
        }
        s0 = s0.setScale(4, RoundingMode.HALF_UP);

        // 折扣系数
        BigDecimal rate = li.discountRateApplied != null ? li.discountRateApplied : BigDecimal.ZERO;
        double scale = 1.0 - rate.doubleValue() / 100.0;
        String source = li.discountSource;

        // S1 + base
        BigDecimal s1;
        BigDecimal base;
        if (source == null || source.isBlank() || SUBTOTAL_SOURCE.equals(source)) {
            // 整体折扣：对产品小计整体乘 scale
            s1 = s0.multiply(BigDecimal.valueOf(scale));
            base = s0;
        } else if (subtotalCid != null) {
            // 页签折扣：仅对 source 页签的列和乘 scale，再重算 SUBTOTAL 公式
            s1 = ComponentDataEffectiveRows.subtotalWithDiscount(
                cdList, metaById, subtotalCid, formulaCalculator, source, scale);
            base = componentColumnSumOf(cdList, metaById, source);
        } else {
            // 没有 SUBTOTAL 组件且指定了页签折扣 → 兜底不折
            s1 = s0;
            base = BigDecimal.ZERO;
        }
        s1 = s1.setScale(4, RoundingMode.HALF_UP);

        int qty = li.annualVolume != null ? li.annualVolume : 0;
        BigDecimal q = BigDecimal.valueOf(qty);

        li.lineUnitPrice      = s0;
        li.discountBaseAmount = base.setScale(4, RoundingMode.HALF_UP);
        li.lineFinalPrice     = s1;
        li.lineDiscountAmount = s0.subtract(s1).multiply(q).setScale(4, RoundingMode.HALF_UP);
        li.lineTotalAmount    = s1.multiply(q).setScale(4, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    /**
     * 被折页签所有数值列和之和（用作折扣基数展示）。
     * 按 code 或 name 匹配元数据，找到后计算该组件 cd 的列和汇总值。
     */
    private BigDecimal componentColumnSumOf(
            List<QuotationLineComponentData> cdList,
            Map<UUID, ComponentDataEffectiveRows.Meta> metaById,
            String code) {
        for (QuotationLineComponentData cd : cdList) {
            if (cd.componentId == null) continue;
            ComponentDataEffectiveRows.Meta m = metaById.get(cd.componentId);
            if (m == null) continue;
            if (!code.equals(m.code) && !code.equals(m.name)) continue;
            Map<String, BigDecimal> sums = ComponentDataEffectiveRows.columnSums(parseRows(cd.rowData));
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal v : sums.values()) total = total.add(v);
            return total;
        }
        return BigDecimal.ZERO;
    }

    /** 从元数据中找 SUBTOTAL 类型组件的 UUID；找不到返回 null。 */
    private UUID findSubtotalComponentId(Map<UUID, ComponentDataEffectiveRows.Meta> metaById) {
        for (Map.Entry<UUID, ComponentDataEffectiveRows.Meta> e : metaById.entrySet()) {
            if ("SUBTOTAL".equals(e.getValue().componentType)) return e.getKey();
        }
        return null;
    }

    /**
     * 从 cdList 中查出每个 componentId 对应的 Component 实体，构建 Meta 映射。
     * 已查过的 componentId 不重复查（同一组件可能有多个 cd 行）。
     */
    private Map<UUID, ComponentDataEffectiveRows.Meta> loadMetas(List<QuotationLineComponentData> cdList) {
        Map<UUID, ComponentDataEffectiveRows.Meta> out = new HashMap<>();
        if (cdList == null) return out;
        for (QuotationLineComponentData cd : cdList) {
            if (cd.componentId == null || out.containsKey(cd.componentId)) continue;
            Component c = Component.findById(cd.componentId);
            if (c == null) continue;
            JsonNode formulas = null;
            if (c.formulas != null && !c.formulas.isBlank()) {
                try { formulas = MAPPER.readTree(c.formulas); } catch (Exception ignore) {}
            }
            out.put(cd.componentId,
                new ComponentDataEffectiveRows.Meta(c.code, c.name, c.componentType, formulas));
        }
        return out;
    }

    private List<Map<String, Object>> parseRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> r =
                MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
