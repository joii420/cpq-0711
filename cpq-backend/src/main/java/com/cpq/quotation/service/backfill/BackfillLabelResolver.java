package com.cpq.quotation.service.backfill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * repair-0727 B4（修 D5 预览失真的「无语义」半——D4 如实性已由 B3 patch 语义保证）—— 回填预览的
 * 业务语义化：表 → 业务类别中文名 / 物理列 → 中文标签（三级取名）/ 行业务身份短语 / 料号-客户号
 * 批量名称解析。只读、无副作用；批量 SQL，禁 N+1（AC-R8）。
 */
@ApplicationScoped
public class BackfillLabelResolver {

    @Inject EntityManager em;

    /** 表 → 业务类别中文名（backtask B4 §3）。 */
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
        "material_bom_item", "BOM 组成",
        "element_bom_item", "材质元素构成",
        "unit_price", "单价",
        "capacity", "工时产能",
        "plating_scheme", "电镀方案"
    );

    public String categoryLabel(String table) {
        return CATEGORY_LABELS.getOrDefault(table, table);
    }

    /** 物理列 → 中文名兜底字典（backtask B4 §4 二级；覆盖 5 张受管表的常用列）。 */
    private static final Map<String, String> COLUMN_LABELS = buildColumnLabels();

    private static Map<String, String> buildColumnLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        // 通用轴 / BOM
        m.put("customer_no", "客户");
        m.put("material_no", "产品料号");
        m.put("finished_material_no", "成品料号");
        m.put("code", "代码");
        m.put("component_no", "料件");
        m.put("characteristic", "BOM 特征");
        m.put("composition_qty", "组成数量");
        m.put("base_qty", "基本用量");
        m.put("issue_unit", "发料单位");
        m.put("scrap_rate", "损耗率");
        m.put("seq_no", "项次");
        m.put("item_seq", "序次");
        m.put("net_weight", "净重");
        m.put("rough_weight", "毛重");
        m.put("weight_unit", "重量单位");
        m.put("part_no", "料号");
        m.put("operation_no", "工序代码");
        m.put("operation_seq", "工序序号");
        m.put("is_optional", "是否可选");
        m.put("defect_rate", "不良率");
        m.put("production_no", "生产料号");
        // 材质元素
        m.put("content", "含量");
        m.put("material_part_no", "材质料号");
        m.put("hf_part_no", "料号");
        // 单价
        m.put("pricing_price", "单价");
        m.put("currency", "币种");
        m.put("unit", "单位");
        m.put("supplier_no", "供应商");
        m.put("supplier_name", "供应商名称");
        m.put("cost_type", "费用类型");
        m.put("price_type", "价格类型");
        m.put("effective_date", "生效日期");
        m.put("expire_date", "失效日期");
        m.put("recovery_discount", "回收折扣");
        m.put("recovery_currency", "回收币种");
        m.put("recovery_unit", "回收单位");
        // 工时产能
        m.put("process_no", "工艺代码");
        m.put("process_name", "工艺名称");
        m.put("resource_group_no", "资源组代码");
        m.put("resource_group_name", "资源组名称");
        m.put("fixed_lead_time", "固定工时");
        m.put("variable_time", "变动工时");
        m.put("capacity_unit", "产能单位");
        // 电镀
        m.put("scheme_no", "方案编号");
        m.put("plating_element", "电镀元素");
        m.put("plating_method", "电镀方式");
        m.put("surface_area", "表面积");
        m.put("plating_area", "电镀面积");
        m.put("plating_thickness", "电镀厚度");
        m.put("plating_requirement", "电镀要求");
        m.put("element_usage", "元素用量");
        return Collections.unmodifiableMap(m);
    }

    /**
     * 三级取名（backtask B4 §4）：一级 {@code groupColumnAliases}（组触达页签的 colToBase 反查，
     * 已去前导 {@code _}，是用户自己配的中文列名）→ 二级静态字典 → 三级物理列名原样（兜底，绝不空）。
     */
    public String columnLabel(String physicalColumn, Map<String, String> groupColumnAliases) {
        if (groupColumnAliases != null) {
            String alias = groupColumnAliases.get(physicalColumn);
            if (alias != null && !alias.isBlank()) return alias;
        }
        return COLUMN_LABELS.getOrDefault(physicalColumn, physicalColumn);
    }

    /**
     * repair-0727 验收「财务读不懂」二轮修复 · 项②：{@code unit_price.price_type} 枚举中文化字典。
     * 覆盖真实数据里出现的 5 个值；未登记的值原样返回（三级取名同款兜底，绝不空）。
     */
    private static final Map<String, String> PRICE_TYPE_LABELS = Map.of(
        "INCOMING_MATERIAL_PROCESS", "来料加工",
        "PROCESS", "自制加工",
        "PLATING", "电镀",
        "FINISHED_MATERIAL_OTHER", "成品其他费用",
        "COMPONENT_OTHER", "组件其他费用"
    );

    public String priceTypeLabel(String priceType) {
        if (priceType == null) return null;
        return PRICE_TYPE_LABELS.getOrDefault(priceType, priceType);
    }

    /**
     * 项②去重判据：翻译后的 price_type 与同组 cost_type 语义重复（如"来料加工"⊂"来料加工费"）时，
     * 调用方应丢弃 price_type 这一条轴标签，只留 cost_type——双向子串判定，覆盖"翻译词是 cost_type
     * 前缀"和"cost_type 是翻译词子串"两种情形。
     */
    public static boolean isRedundantWithCostType(String translatedPriceType, String costType) {
        if (translatedPriceType == null || costType == null) return false;
        return costType.contains(translatedPriceType) || translatedPriceType.contains(costType);
    }

    /** 行业务身份短语（backtask B4 §6）：表 + 关键列拼装，如「组成件 W-1001 外购接线端子（外购件）」。 */
    public String rowLabel(String table, Map<String, Object> row, Map<String, String> materialNames) {
        switch (table) {
            case "material_bom_item": {
                String no = str(row.get("component_no"));
                String name = no == null || materialNames == null ? null : materialNames.get(no);
                String kind = characteristicLabel(str(row.get("characteristic")));
                StringBuilder sb = new StringBuilder("组成件 ").append(no == null ? "?" : no);
                if (name != null) sb.append(' ').append(name);
                if (kind != null) sb.append('（').append(kind).append('）');
                return sb.toString();
            }
            case "element_bom_item": {
                String elem = str(row.get("component_no"));
                return "元素 " + (elem == null ? "?" : elem);
            }
            case "unit_price": {
                String code = str(row.get("code"));
                String costType = str(row.get("cost_type"));
                String priceType = str(row.get("price_type"));
                String kind = costType != null ? costType : (priceType != null ? priceType : "单价");
                return code != null ? kind + "（" + code + "）" : kind;
            }
            case "capacity": {
                String proc = str(row.get("process_name"));
                if (proc == null) proc = str(row.get("process_no"));
                return "工艺 " + (proc == null ? "?" : proc);
            }
            case "plating_scheme": {
                String elem = str(row.get("plating_element"));
                return "电镀元素 " + (elem == null ? "?" : elem);
            }
            default:
                return categoryLabel(table);
        }
    }

    private static String characteristicLabel(String characteristic) {
        if (characteristic == null) return null;
        return switch (characteristic) {
            case "RECIPE" -> "材质配方";
            case "ASSEMBLY" -> "组装件";
            case "OUTSOURCED" -> "外购件";
            default -> characteristic;
        };
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    /**
     * 料号/材质码/元素码 → 名称（批量，最多 2 条 SQL，禁 N+1，backtask B4 §5）。
     *
     * <p>repair-0727 验收「财务读不懂」二轮修复 · 项④：{@code unit_price.code} 这类轴列指向的不
     * 一定是成品/半成品（在 {@code material_master} 里），也可能是材质/元素码（只在
     * {@code material_recipe} 里，如 "00137"→"H65"）——按 material_master 优先、
     * material_recipe 兜底的顺序批量解析，而不是"查不到就露裸码"。第二条 SQL 只对第一条没解出的
     * 编号发起，不是无条件两条。
     */
    public Map<String, String> resolveMaterialNames(Collection<String> materialNos) {
        Set<String> nos = new LinkedHashSet<>();
        for (String no : materialNos) if (no != null && !no.isBlank()) nos.add(no);
        if (nos.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<Object[]> masterRows = em.createNativeQuery(
                "SELECT material_no, material_name FROM material_master WHERE material_no IN (:nos)")
                .setParameter("nos", new ArrayList<>(nos))
                .getResultList();
        QuoteBackfillCollector.profile().labelResolveQueries++;
        for (Object[] r : masterRows) if (r[1] != null) out.put((String) r[0], (String) r[1]);

        Set<String> unresolved = new LinkedHashSet<>(nos);
        unresolved.removeAll(out.keySet());
        if (!unresolved.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> recipeRows = em.createNativeQuery(
                    "SELECT code, name FROM material_recipe WHERE code IN (:nos)")
                    .setParameter("nos", new ArrayList<>(unresolved))
                    .getResultList();
            QuoteBackfillCollector.profile().labelResolveQueries++;
            for (Object[] r : recipeRows) if (r[1] != null) out.put((String) r[0], (String) r[1]);
        }
        return out;
    }

    /**
     * repair-0727 验收「财务读不懂」二轮修复 · 项④：{@code capacity.resource_group_no} → 资源组名
     * （批量，1 条 SQL，禁 N+1）。解析不到时调用方回退到裸代码 + {@link #columnLabel} 已经登记的
     * "资源组代码"标签，不会不知道这是什么。
     */
    public Map<String, String> resolveResourceGroupNames(Collection<String> resourceGroupNos) {
        Set<String> nos = new LinkedHashSet<>();
        for (String no : resourceGroupNos) if (no != null && !no.isBlank()) nos.add(no);
        if (nos.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT group_no, group_name FROM resource_group WHERE group_no IN (:nos)")
                .setParameter("nos", new ArrayList<>(nos))
                .getResultList();
        QuoteBackfillCollector.profile().labelResolveQueries++;
        Map<String, String> out = new LinkedHashMap<>();
        for (Object[] r : rows) if (r[1] != null) out.put((String) r[0], (String) r[1]);
        return out;
    }

    /** 客户号(code) → 客户名（批量，1 条 SQL，禁 N+1，backtask B4 §5）。 */
    public Map<String, String> resolveCustomerNames(Collection<String> customerCodes) {
        Set<String> codes = new LinkedHashSet<>();
        for (String c : customerCodes) if (c != null && !c.isBlank()) codes.add(c);
        if (codes.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name FROM customer WHERE code IN (:codes)")
                .setParameter("codes", new ArrayList<>(codes))
                .getResultList();
        QuoteBackfillCollector.profile().labelResolveQueries++;
        Map<String, String> out = new LinkedHashMap<>();
        for (Object[] r : rows) if (r[1] != null) out.put((String) r[0], (String) r[1]);
        return out;
    }
}
