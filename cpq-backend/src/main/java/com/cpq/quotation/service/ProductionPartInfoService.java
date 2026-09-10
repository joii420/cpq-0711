package com.cpq.quotation.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * task-260910 · B-2 / B-4：销售料号 → 生产料号详情的**唯一**取数入口。
 *
 * <p>链路（两条 SQL，条数与料号个数 N 无关）：
 * <pre>
 *   ① ds_quote_material  (customer_no, material_no) → production_no
 *      唯一键 (customer_no, material_no) —— 🚨 customer 过滤不可省，
 *      同一销售料号在不同客户下各存一行，漏过滤即跨客户串号。
 *   ② ds_cost_basic_material ∪ ds_cost_detail_material  by production_no
 *      两表唯一键均为 production_no，无 customer 维度。
 *      合并口径：**basic 优先 + 逐列取非空**（basic 某列为空/空白则取 detail 同列）。
 * </pre>
 *
 * <p>为什么抽成一个服务：报价单卡片（{@code QuotationService.loadLineItems}）与选品候选
 * （{@code CustomerPartCandidateService}）此前各写了一套读 {@code internal_material} 的逻辑，
 * 属 AP-17「同名概念两套实现」。本类是它们此后共用的唯一实现。
 *
 * <p>旧链路（已废弃，勿复活）：{@code internal_material}（全表 0 行）→ 兜底
 * {@code material_master}（无销售料号 S0001）⇒ 结果恒 null。
 */
@ApplicationScoped
public class ProductionPartInfoService {

    @Inject
    EntityManager em;

    /** 生产料号详情——对应前端「生产料号」浮层的五行。 */
    public static final class ProductionPartInfo {
        /** 生产料号 ds_quote_material.production_no —— 🚫 不是销售料号 */
        public String productionNo;
        public String partName;
        public String specification;
        public String sizeInfo;
        public String oldMaterialNo;
    }

    /**
     * 批量取「销售料号 → 生产料号详情」。
     *
     * @param customerCode {@code customer.code}（如 CUST-0004），**不是 UUID**；为空则返回空 Map
     * @param materialNos  销售料号集合
     * @return key = 销售料号；**只含 production_no 非空的料号**。
     *         production_no 为空 ⇒ key 不存在 ⇒ 调用方应置 hfPartInfo=null（AC-9 第 1 行）。
     *         production_no 有值但两张核价表都查不到 ⇒ value 只有 productionNo 有值，
     *         其余四列为 null（AC-9 第 2 行，现网 2688/2696 属这种）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, ProductionPartInfo> loadByMaterialNos(String customerCode, Collection<String> materialNos) {
        Map<String, ProductionPartInfo> result = new HashMap<>();
        if (customerCode == null || customerCode.isBlank() || materialNos == null || materialNos.isEmpty()) {
            return result;
        }
        List<String> distinctMaterialNos = new ArrayList<>(new LinkedHashSet<>(materialNos));

        // ① 一条 IN 批量：销售料号 → 生产料号（必须带 customer_no 过滤，AC-5）
        List<Object[]> bindRows = em.createNativeQuery(
                        "SELECT m.material_no, m.production_no FROM ds_quote_material m " +
                        "WHERE m.customer_no = :cc AND m.material_no IN (:pns)")
                .setParameter("cc", customerCode)
                .setParameter("pns", distinctMaterialNos)
                .getResultList();

        Map<String, String> productionNoByMaterialNo = new HashMap<>();
        for (Object[] r : bindRows) {
            if (r == null || r[0] == null) continue;
            String productionNo = str(r[1]);
            if (productionNo == null) continue;   // production_no 为空 → 不进 Map（AC-9 场景一）
            productionNoByMaterialNo.putIfAbsent(r[0].toString(), productionNo);
        }
        if (productionNoByMaterialNo.isEmpty()) return result;

        // ② 一条 UNION ALL 批量：核价两表按 production_no 取明细，prio=1(basic) 优先于 prio=2(detail)
        List<String> productionNos = new ArrayList<>(new LinkedHashSet<>(productionNoByMaterialNo.values()));
        List<Object[]> costRows = em.createNativeQuery(
                        "SELECT production_no, material_name, specification, dimension, old_material_no, 1 AS prio " +
                        "  FROM ds_cost_basic_material WHERE production_no IN (:pns) " +
                        "UNION ALL " +
                        "SELECT production_no, material_name, specification, dimension, old_material_no, 2 AS prio " +
                        "  FROM ds_cost_detail_material WHERE production_no IN (:pns) " +
                        "ORDER BY 1, 6")
                .setParameter("pns", productionNos)
                .getResultList();

        // 纯内存合并（无查库）：按 prio 升序逐列取第一个非空值 = basic 优先 + 逐列 COALESCE（AC-11）
        Map<String, ProductionPartInfo> mergedByProductionNo = new HashMap<>();
        for (Object[] r : costRows) {
            if (r == null || r[0] == null) continue;
            ProductionPartInfo info = mergedByProductionNo.computeIfAbsent(r[0].toString(), pn -> {
                ProductionPartInfo i = new ProductionPartInfo();
                i.productionNo = pn;
                return i;
            });
            if (info.partName == null)      info.partName      = str(r[1]);
            if (info.specification == null) info.specification = str(r[2]);
            if (info.sizeInfo == null)      info.sizeInfo      = str(r[3]);
            if (info.oldMaterialNo == null) info.oldMaterialNo = str(r[4]);
        }

        // 纯内存分发（无查库）
        for (Map.Entry<String, String> e : productionNoByMaterialNo.entrySet()) {
            ProductionPartInfo merged = mergedByProductionNo.get(e.getValue());
            ProductionPartInfo info = new ProductionPartInfo();
            info.productionNo = e.getValue();
            if (merged != null) {
                info.partName = merged.partName;
                info.specification = merged.specification;
                info.sizeInfo = merged.sizeInfo;
                info.oldMaterialNo = merged.oldMaterialNo;
            }
            result.put(e.getKey(), info);
        }
        return result;
    }

    /** 解析 customer.code —— ds_* 表的 customer_no 列存的是 code，不是 UUID。 */
    public String resolveCustomerCode(java.util.UUID customerId) {
        if (customerId == null) return null;
        Object code = em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
                .setParameter("cid", customerId)
                .getResultList().stream().findFirst().orElse(null);
        return code == null ? null : code.toString();
    }

    /** 空白视同为空 —— 核价表里 specification 存的是 ''（非 NULL），逐列 COALESCE 必须把它当空。 */
    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}
