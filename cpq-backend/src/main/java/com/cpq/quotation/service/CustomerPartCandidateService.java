package com.cpq.quotation.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 报价单 Step2 "批量从基础数据导入产品" 候选料号服务。
 *
 * <p>逻辑(对应 PRD v5 设计):
 * <pre>
 * 1. 优先返回 mat_customer_part_mapping(customer_id = X)中关联的料号(客户专属)
 * 2. 同时返回 mat_part 全局料号(没和客户专属去重 — 前端可加切换 Tab)
 * 3. 跨表 LEFT JOIN 拿一次,前端按 customerSpecific 标志区分
 * </pre>
 */
@ApplicationScoped
public class CustomerPartCandidateService {

    private static final Logger LOG = Logger.getLogger(CustomerPartCandidateService.class);

    @Inject
    EntityManager em;

    /** task-260910 · B-4：与报价单卡片共用的生产料号取数入口（🚫 不许另写一套，AP-17） */
    @Inject
    ProductionPartInfoService productionPartInfoService;

    /**
     * 列出该客户可选的所有料号候选(客户专属 + 全局),按客户专属优先排序。
     *
     * @param customerId 必填,客户 ID
     * @param importRecordId 可选,若传入则只列该导入批次涉及的料号(精确"这次导入"语义);
     *                       null 时列该客户历史所有基础数据涉及的料号
     */
    @SuppressWarnings("unchecked")
    public List<CustomerPartCandidateDTO> listCandidates(UUID customerId, UUID importRecordId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId 不能为空");
        }
        // V6 优先路径: 若 importRecordId 对应 V6 ImportRecord (metadata.v6=true),
        // 直接从 metadata.hfPairs 拿 hf 集合 — 覆盖 NO_BUMP 场景
        // (NO_BUMP 不写 mat_process/fee/plating_fee 的 import_record_id, V5 旧路径会查到空)
        if (importRecordId != null) {
            List<CustomerPartCandidateDTO> v6Result = listCandidatesV6(customerId, importRecordId);
            if (v6Result != null) return v6Result;
        }
        // V6 回退路径 (importRecordId 为空 = 手工「+ 添加产品」, 或非 V6 ImportRecord):
        //   (AP-53) V44 表 mat_part / mat_customer_part_mapping / mat_process / mat_fee /
        //   mat_plating_fee / plating_fee 已全面禁用, 一律改查 V6 表。
        //   候选 = material_master 中"有客户映射的产品"(即出现在 material_customer_map),
        //   以此自动排除 BOM 子件原料(原料无 customer_product_no 映射)。
        //   customer_no 匹配本客户 → customer_specific=true; 其余产品作为全局候选(false)。
        //   按批次过滤(importRecordId)已由上面的 listCandidatesV6 经 metadata.hfPairs 实现,
        //   且 V6 表无 import_record_id 列, 故此回退路径不再按导入批次过滤。
        //   生产料号详情由 ProductionPartInfoService 批量补齐(task-260910 B-4)。
        String sql =
            "SELECT DISTINCT p.material_no, p.material_name, p.unit_weight, p.standard_unit, " +
            "       m.customer_product_no, m.customer_material_name, m.customer_drawing_no, " +
            "       m.base_currency, m.quote_currency, " +
            "       (m.id IS NOT NULL) AS customer_specific, " +
            "       NULL::int AS current_version " +
            "FROM material_master p " +
            "LEFT JOIN material_customer_map m " +
            "       ON m.material_no = p.material_no " +
            "      AND m.customer_no = (SELECT code FROM customer WHERE id = :customerId) " +
            "      AND m.system_type = 'QUOTE' " +
            "      AND m.pending_quotation_id IS NULL " +
            // task-260910 · B-4：删 internal_material JOIN（全表 0 行的死链路）。
            // 生产料号详情改由 ProductionPartInfoService 在下面一次性批量补齐。
            // task-0721 B7 闸门：未审核报价单占用的料号不出现在候选里（单表谓词，零 N+1，AC-4）。
            "WHERE p.material_no IN (SELECT material_no FROM material_customer_map " +
            "                        WHERE system_type = 'QUOTE' AND customer_product_no IS NOT NULL " +
            "                          AND pending_quotation_id IS NULL) " +
            "ORDER BY (m.id IS NOT NULL) DESC, p.material_no ASC";

        var query = em.createNativeQuery(sql).setParameter("customerId", customerId);
        List<Object[]> rows = query.getResultList();

        List<CustomerPartCandidateDTO> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            CustomerPartCandidateDTO dto = new CustomerPartCandidateDTO();
            dto.partNo            = (String) row[0];
            dto.partName          = (String) row[1];
            dto.unitWeight        = row[2] != null ? (BigDecimal) row[2] : null;
            dto.weightUnit        = (String) row[3];
            dto.customerProductNo = (String) row[4];
            dto.customerPartName  = (String) row[5];
            dto.customerDrawingNo = (String) row[6];
            dto.baseCurrency      = (String) row[7];
            dto.quoteCurrency     = (String) row[8];
            dto.customerSpecific  = row[9] != null && (Boolean) row[9];
            // V161+ 修复: 透传 mapping.current_version → 前端 buildLineItemFromTemplate
            // 写入 LineItem.partVersionLocked, 首次自动展开就带正确版本号 → driver 注入 part_version=N
            dto.currentVersion    = row[10] != null ? ((Number) row[10]).intValue() : null;
            result.add(dto);
        }
        fillProductionPartInfo(customerId, result);
        LOG.debugf("listCandidates(customerId=%s) → %d rows", customerId, result.size());
        return result;
    }

    /**
     * task-260910 · B-4：批量补生产料号详情（AC-13）。
     *
     * <p>两条 SQL（在 {@link ProductionPartInfoService} 内），与候选行数 N 无关；
     * 本方法自身的循环是**纯内存分发，无查库**（AC-7 N+1 守恒）。
     */
    private void fillProductionPartInfo(UUID customerId, List<CustomerPartCandidateDTO> result) {
        if (result.isEmpty()) return;
        String customerCode = productionPartInfoService.resolveCustomerCode(customerId);
        List<String> materialNos = new ArrayList<>(result.size());
        for (CustomerPartCandidateDTO d : result) {
            if (d.partNo != null && !d.partNo.isBlank()) materialNos.add(d.partNo);
        }
        var infos = productionPartInfoService.loadByMaterialNos(customerCode, materialNos);
        if (infos.isEmpty()) return;
        for (CustomerPartCandidateDTO d : result) {
            ProductionPartInfoService.ProductionPartInfo pi = d.partNo == null ? null : infos.get(d.partNo);
            if (pi == null) continue;   // 未绑生产料号 → hfPartInfo 保持 null
            CustomerPartCandidateDTO.HfPartInfo info = new CustomerPartCandidateDTO.HfPartInfo();
            info.partNo = pi.productionNo;
            info.partName = pi.partName;
            info.specification = pi.specification;
            info.sizeInfo = pi.sizeInfo;
            info.oldMaterialNo = pi.oldMaterialNo;
            d.hfPartInfo = info;
        }
    }

    /**
     * V6 优先查询: import_record.metadata 含 hfPairs 时直接用,无论 BUMP/NO_BUMP/NEW 都能找到候选。
     * 返回 null 表示这不是 V6 ImportRecord (调用方应 fallback 到 V5 旧逻辑)。
     */
    @SuppressWarnings("unchecked")
    private List<CustomerPartCandidateDTO> listCandidatesV6(UUID customerId, UUID importRecordId) {
        // 1. 查 ImportRecord.metadata
        Object metaObj = em.createNativeQuery(
                "SELECT metadata::text FROM import_record WHERE id = :id")
                .setParameter("id", importRecordId)
                .getResultList().stream().findFirst().orElse(null);
        if (metaObj == null) return null;
        String metaJson = metaObj.toString();
        if (metaJson.isBlank()) return null;

        // 2. 解析 metadata，检测 v6=true 标志
        Set<String> hfSet = new LinkedHashSet<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(metaJson);
            if (!root.has("v6") || !root.path("v6").asBoolean(false)) return null;
            com.fasterxml.jackson.databind.JsonNode pairs = root.path("hfPairs");
            if (!pairs.isArray()) return null;
            for (com.fasterxml.jackson.databind.JsonNode pair : pairs) {
                String hf = pair.path("hf").asText(null);
                if (hf != null && !hf.isBlank()) hfSet.add(hf);
            }
        } catch (Exception e) {
            LOG.warnf("V6 listCandidates: 解析 metadata 失败 (%s),回退到 V5 路径: %s",
                    importRecordId, e.getMessage());
            return null;
        }
        if (hfSet.isEmpty()) {
            LOG.warnf("V6 listCandidates: import_record=%s hfPairs 为空", importRecordId);
            return java.util.Collections.emptyList();
        }

        // 3. 按 hf 集合 JOIN V6 表 material_master + material_customer_map
        //    (AP-53) V6「从基础数据导入」写入 material_master / material_customer_map(新表),
        //    旧 V44 表 mat_part / mat_customer_part_mapping 已废弃且不会被 V6 导入写入。
        //    此前查旧表 → 新导入料号查不到 → 候选返 0 → 报价单自动展开 0 个产品。
        //    列名映射: part_no→material_no, part_name→material_name, weight_unit→standard_unit,
        //    customer_part_name→customer_material_name; 客户维度用 customer_no(客户编码)而非 customer_id;
        //    material_master 无 status_code(不过滤); material_customer_map 无 current_version(置 NULL)。
        String sql =
            "SELECT DISTINCT p.material_no, p.material_name, p.unit_weight, p.standard_unit, " +
            "       m.customer_product_no, m.customer_material_name, m.customer_drawing_no, " +
            "       m.base_currency, m.quote_currency, " +
            "       (m.id IS NOT NULL) AS customer_specific, " +
            "       NULL::int AS current_version " +
            "FROM material_master p " +
            "LEFT JOIN material_customer_map m " +
            "       ON m.material_no = p.material_no " +
            "      AND m.customer_no = (SELECT code FROM customer WHERE id = :customerId) " +
            "      AND m.system_type = 'QUOTE' " +
            // task-0721 B7：customer_specific 判定不应被「同客户下其它未审核报价单」的 pending
            // 占号行误判为 true（防御性一致性；本查询本身已按 hfSet 精确框定"本批导入"，主 WHERE 不受影响）。
            "      AND m.pending_quotation_id IS NULL " +
            // task-260910 · B-4：删 internal_material JOIN，生产料号详情改走 ProductionPartInfoService
            "WHERE p.material_no IN :hfs " +
            "ORDER BY (m.id IS NOT NULL) DESC, p.material_no ASC";
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("customerId", customerId)
                .setParameter("hfs", hfSet)
                .getResultList();

        List<CustomerPartCandidateDTO> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            CustomerPartCandidateDTO dto = new CustomerPartCandidateDTO();
            dto.partNo            = (String) row[0];
            dto.partName          = (String) row[1];
            dto.unitWeight        = row[2] != null ? (BigDecimal) row[2] : null;
            dto.weightUnit        = (String) row[3];
            dto.customerProductNo = (String) row[4];
            dto.customerPartName  = (String) row[5];
            dto.customerDrawingNo = (String) row[6];
            dto.baseCurrency      = (String) row[7];
            dto.quoteCurrency     = (String) row[8];
            dto.customerSpecific  = row[9] != null && (Boolean) row[9];
            // V161+ 修复: V6 路径同样透传 current_version
            dto.currentVersion    = row[10] != null ? ((Number) row[10]).intValue() : null;
            result.add(dto);
        }
        fillProductionPartInfo(customerId, result);
        LOG.infof("V6 listCandidates(customerId=%s, importRecordId=%s) → %d 行 (hfPairs=%d)",
                customerId, importRecordId, result.size(), hfSet.size());
        return result;
    }
}
