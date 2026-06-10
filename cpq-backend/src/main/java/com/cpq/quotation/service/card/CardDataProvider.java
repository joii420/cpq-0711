package com.cpq.quotation.service.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * 卡片数据提供器：按"页签实例 key"读该料号某页签的行集 / 小计。
 * 无状态、无 DB —— 调用方查好该料号的页签列表后构造本类（便于单测）。
 * 页签 key 约定：componentId + ":" + sortOrder（与 refs 生成端一致）。
 *
 * <p>匹配策略（持久化模式）：精确 byTab（componentId:sortOrder）优先；
 * 若不命中则按末段 sortOrder 回退（解决核价模板 CARD_FORMULA 引用的组件 id
 * 与卡片数据实际存储的组件 id 不一致的场景）。
 *
 * <p>已解析有效行模式（fromEffectiveRows）：精确 tabKey 命中，不做 sortOrder 回退。
 */
public final class CardDataProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 持久化模式字段（去掉 final 以允许无参私有构造器）
    private Map<String, QuotationLineComponentData> byTab = new HashMap<>();
    private Map<Integer, QuotationLineComponentData> bySort = new HashMap<>();

    // 已解析有效行模式字段（fromEffectiveRows 专用）
    private Map<String, List<Map<String, Object>>> effRows;
    private Map<String, BigDecimal> effSubtotal;
    private Map<String, Map<String, BigDecimal>> effSubtotalByColumn; // Plan 2c

    /** 供 fromEffectiveRows 使用的私有无参构造器。 */
    private CardDataProvider() {}

    public CardDataProvider(List<QuotationLineComponentData> tabs) {
        if (tabs != null) {
            for (QuotationLineComponentData d : tabs) {
                byTab.put(keyOf(d), d);
                if (d.sortOrder != null) bySort.putIfAbsent(d.sortOrder, d);
            }
        }
    }

    /** 从 CardEffectiveRows 解析结果构造：精确 tabKey 命中，不做 sortOrder 回退。 */
    public static CardDataProvider fromEffectiveRows(
            Map<String, CardEffectiveRows.TabRows> eff) {
        CardDataProvider p = new CardDataProvider();
        p.effRows = new HashMap<>();
        p.effSubtotal = new HashMap<>();
        p.effSubtotalByColumn = new HashMap<>();
        if (eff != null) {
            for (var e : eff.entrySet()) {
                p.effRows.put(e.getKey(), e.getValue().rows != null ? e.getValue().rows : List.of());
                p.effSubtotal.put(e.getKey(), e.getValue().subtotal);
                p.effSubtotalByColumn.put(e.getKey(),
                    e.getValue().subtotalByColumn != null ? e.getValue().subtotalByColumn : Map.of());
            }
        }
        return p;
    }

    /** 与 refs 生成端必须一致的 key 规则。 */
    public static String keyOf(QuotationLineComponentData d) {
        return d.componentId + ":" + (d.sortOrder == null ? 0 : d.sortOrder);
    }

    /**
     * tabKey 形如 "componentId:sortOrder"；精确 byTab 命中优先，否则按末段 sortOrder 回退。
     */
    private QuotationLineComponentData resolve(String tabKey) {
        QuotationLineComponentData d = byTab.get(tabKey);
        if (d != null) return d;
        int idx = tabKey.lastIndexOf(':');
        if (idx >= 0) {
            try {
                return bySort.get(Integer.parseInt(tabKey.substring(idx + 1).trim()));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    public List<Map<String, Object>> rowsOf(String tabKey) {
        if (effRows != null) return effRows.getOrDefault(tabKey, List.of());
        QuotationLineComponentData d = resolve(tabKey);
        if (d == null || d.rowData == null || d.rowData.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows =
                MAPPER.readValue(d.rowData, new TypeReference<List<Map<String, Object>>>() {});
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public BigDecimal subtotalOf(String tabKey) {
        if (effSubtotal != null) return effSubtotal.get(tabKey);
        QuotationLineComponentData d = resolve(tabKey);
        return d == null ? null : d.subtotal;
    }

    /** Plan 2c：某页签某小计列的总计；无 per-column 数据（持久化路径/未命中）→ null。 */
    public BigDecimal subtotalOfColumn(String tabKey, String column) {
        if (effSubtotalByColumn == null) return null;
        Map<String, BigDecimal> m = effSubtotalByColumn.get(tabKey);
        return m == null ? null : m.get(column);
    }

    public boolean hasTab(String tabKey) {
        if (effRows != null) return effRows.containsKey(tabKey);
        return resolve(tabKey) != null;
    }
}
