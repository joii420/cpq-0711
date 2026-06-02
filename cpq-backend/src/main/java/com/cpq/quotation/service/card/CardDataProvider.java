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
 */
public final class CardDataProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, QuotationLineComponentData> byTab = new HashMap<>();

    public CardDataProvider(List<QuotationLineComponentData> tabs) {
        if (tabs != null) for (QuotationLineComponentData d : tabs) byTab.put(keyOf(d), d);
    }

    /** 与 refs 生成端必须一致的 key 规则。 */
    public static String keyOf(QuotationLineComponentData d) {
        return d.componentId + ":" + (d.sortOrder == null ? 0 : d.sortOrder);
    }

    public List<Map<String, Object>> rowsOf(String tabKey) {
        QuotationLineComponentData d = byTab.get(tabKey);
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
        QuotationLineComponentData d = byTab.get(tabKey);
        return d == null ? null : d.subtotal;
    }

    public boolean hasTab(String tabKey) { return byTab.containsKey(tabKey); }
}
