package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpq.common.DecimalJacksonCustomizer;

import java.math.BigDecimal;

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
// task-0729 B0（2026-08-01）：类与 extractUnitSubtotal 可见性由包私有升级为 public
// （仅可见性，逻辑不变），供 com.cpq.priceadjust.service.MaterialVersionUpgradeService
// 的 S0（L3 口径守卫）与 S6（写回行金额）跨包复用，不新写第二份提取逻辑（backtask B0 S6 明确指示）。
public final class CostingSubtotalUtil {

    private static final ObjectMapper MAPPER = DecimalJacksonCustomizer.newMapper();

    private CostingSubtotalUtil() {}

    /**
     * 单件核价成本（未乘年用量）。解析失败/无 SUBTOTAL tab 一律返回 ZERO，不抛异常。
     *
     * <p>方向 3（2026-08-06）：实现下沉到 {@link #extractUnitSubtotalOrNull}，本方法退化为
     * 「取不到 → ZERO」的薄包装。<b>逐位行为与改造前完全一致</b>（原方法的四种 ZERO 分支
     * ——json 空 / 解析异常 / 无 SUBTOTAL tab / SUBTOTAL tab 无 subtotal 字段——在
     * OrNull 版里全部返回 null，经本包装后仍是 ZERO）。**提取逻辑全工程只此一份。**
     */
    public static BigDecimal extractUnitSubtotal(String costingCardValuesJson) {
        BigDecimal v = extractUnitSubtotalOrNull(costingCardValuesJson);
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * 方向 3 · 总价单一来源（2026-08-06）：{@link #extractUnitSubtotal} 的「区分不出值」版本 ——
     * 取不到返回 {@code null} 而不是 ZERO。
     *
     * <p><b>为什么必须区分</b>：覆盖 {@code quotation_line_item.subtotal} 时，「模板没有 SUBTOTAL
     * 组件」「卡片值解析失败」和「小计真的算出 0」是三件不同的事。若沿用返 ZERO 的口径，前两种
     * 情况会把保存时写入的<b>兜底值直接抹成 0</b>——那正是本次改造要消灭的「总价被写坏」的另一种
     * 形态。返 null ⇒ 调用方原样保留兜底值（不覆盖）。
     *
     * <p>🔒 这不是「第二套提取逻辑」：唯一的解析实现就在本方法体内，
     * {@link #extractUnitSubtotal} 只是它的 ZERO 兜底包装。设计点 2 的「口径必须唯一」由此保证。
     *
     * @return SUBTOTAL 页签的 {@code subtotal} 值；json 空 / 解析失败 / 无 SUBTOTAL 页签 /
     *         该页签未算出 subtotal → {@code null}
     */
    public static BigDecimal extractUnitSubtotalOrNull(String costingCardValuesJson) {
        if (costingCardValuesJson == null || costingCardValuesJson.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(costingCardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                if ("SUBTOTAL".equals(tab.path("componentType").asText(null))) {
                    JsonNode sub = tab.path("subtotal");
                    if (sub.isMissingNode() || sub.isNull()) return null;
                    return decimalNodeValue(sub);
                }
            }
        } catch (Exception ignore) {
            // 解析失败按「取不到」处理，不阻断整单总价计算（调用方保留兜底值）
        }
        return null;
    }

    /**
     * 方向 3 · 总价单一来源（2026-08-06）：按 {@code componentId} 提取<b>各页签小计</b>，
     * 供覆盖 {@code quotation_line_component_data.subtotal}（§二.2 同构缺陷：页签小计同样由
     * 前端提交、同样会因归位改价变脏，但没有守卫检查它 → 脏了不报错）。
     *
     * <p>与 {@link #extractUnitSubtotalOrNull} <b>同一份 tabs 遍历约定</b>（同一个
     * {@code tabs[].subtotal} 字段，由 {@code CardSnapshotService#buildTabNode} 写入），
     * 只是换了个索引键，不是另起炉灶的算法。
     *
     * <p>只收录 {@code componentId} 非空 <b>且</b> {@code subtotal} 字段存在的页签：缺 subtotal
     * 的页签不进 map ⇒ 调用方不覆盖 ⇒ 保留兜底值（同上条的「区分不出值」纪律）。
     *
     * @return {@code componentId(String) → subtotal}；输入不可解析时返回空 map（不抛异常）
     */
    public static java.util.Map<String, BigDecimal> extractTabSubtotalsByComponentId(String cardValuesJson) {
        java.util.Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        if (cardValuesJson == null || cardValuesJson.isBlank()) return out;
        try {
            JsonNode root = MAPPER.readTree(cardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                String cid = tab.path("componentId").asText(null);
                if (cid == null || cid.isBlank()) continue;
                JsonNode sub = tab.path("subtotal");
                if (sub.isMissingNode() || sub.isNull()) continue;
                BigDecimal subtotal = decimalNodeValue(sub);
                if (subtotal != null) out.putIfAbsent(cid, subtotal);
            }
        } catch (Exception ignore) {
            // 解析失败 → 空 map，调用方一个页签都不覆盖（保留全部兜底值）
        }
        return out;
    }

    /**
     * 单件核价成本 × 年用量（链路二起点：产品小计 × 年用量，可冲到亿级）。
     * task-0810：不再经浮点或低位截断，全精度返回，调用方在计算落点规整到 12 位。
     */
    static BigDecimal lineCostingAmount(String costingCardValuesJson, Integer annualVolume) {
        BigDecimal unit = extractUnitSubtotal(costingCardValuesJson);
        int qty = annualVolume != null ? annualVolume : 0;
        return unit.multiply(BigDecimal.valueOf(qty));
    }

    private static BigDecimal decimalNodeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isNumber() && !node.isTextual()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
