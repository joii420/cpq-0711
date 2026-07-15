package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * task-0713 B5/B7 共享工具：从核价卡片值 JSON（{@link CardSnapshotService#buildCostingCardValues}
 * 的输出，形如 {@code {tabs:[{componentId,componentType,subtotal,...}]}}）提取「产品级核价成本
 * subtotal（单件，不含 Step3 折扣——核价侧本就无折扣概念）」。
 *
 * <p>取数口径：找 {@code componentType==='SUBTOTAL'} 的 tab，取其 {@code subtotal} 字段
 * （由 {@link CardSnapshotService#assembleTabsWithFormulaResults} 写入，聚合全部 NORMAL 组件的
 * {@code is_subtotal} 列）。模板没有 SUBTOTAL 组件时返回 {@link BigDecimal#ZERO}（与
 * {@code LineDiscountService#recompute} 对报价侧无 SUBTOTAL 组件时的兜底一致）。
 */
final class CostingSubtotalUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CostingSubtotalUtil() {}

    /** 单件核价成本（未乘年用量）。解析失败/无 SUBTOTAL tab 一律返回 ZERO，不抛异常。 */
    static BigDecimal extractUnitSubtotal(String costingCardValuesJson) {
        if (costingCardValuesJson == null || costingCardValuesJson.isBlank()) return BigDecimal.ZERO;
        try {
            JsonNode root = MAPPER.readTree(costingCardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                if ("SUBTOTAL".equals(tab.path("componentType").asText(null))) {
                    JsonNode sub = tab.path("subtotal");
                    if (sub.isMissingNode() || sub.isNull()) return BigDecimal.ZERO;
                    return BigDecimal.valueOf(sub.asDouble(0.0));
                }
            }
        } catch (Exception ignore) {
            // 解析失败按 0 处理，不阻断整单总价计算
        }
        return BigDecimal.ZERO;
    }

    /** 单件核价成本 × 年用量，4 位小数四舍五入。 */
    static BigDecimal lineCostingAmount(String costingCardValuesJson, Integer annualVolume) {
        BigDecimal unit = extractUnitSubtotal(costingCardValuesJson);
        int qty = annualVolume != null ? annualVolume : 0;
        return unit.multiply(BigDecimal.valueOf(qty)).setScale(4, RoundingMode.HALF_UP);
    }
}
