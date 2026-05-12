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
        // V5 旧路径 (回退): 按 import_record_id 过滤 mat_process / mat_fee / plating_fee
        // mat_customer_part_mapping 表本身没有 import_record_id 字段, 不能用作料号集合源
        // (否则它会拉客户全部历史 mapping → 污染本次候选, 见 AP-23 / 2026-05 案例)
        // mapping 仅作为 LEFT JOIN 装饰提供 customer_part_name / drawing_no 等信息.
        String importFilter = importRecordId != null ? " AND import_record_id = :importRecordId " : "";

        // 同步带上 internal_material 视角（生产料号管理页维护）；
        // 让前端 buildLineItemFromTemplate 直接把 hfPartInfo 装进 LineItem，
        // 这样从基础数据导入跳到编辑页第一时间就能展示 popover 详情，无需 save+refresh。
        String sql =
            "SELECT DISTINCT p.part_no, p.part_name, p.unit_weight, p.weight_unit, " +
            "       m.customer_product_no, m.customer_part_name, m.customer_drawing_no, " +
            "       m.base_currency, m.quote_currency, " +
            "       (m.id IS NOT NULL) AS customer_specific, " +
            "       im.name AS im_name, im.specification AS im_spec, " +
            "       im.size AS im_size, im.status_code AS im_status " +
            "FROM mat_part p " +
            "LEFT JOIN mat_customer_part_mapping m " +
            "       ON m.hf_part_no = p.part_no AND m.customer_id = :customerId " +
            "LEFT JOIN internal_material im " +
            "       ON im.material_no = p.part_no " +
            "WHERE p.status_code = 'Y' " +
            "  AND p.part_no IN ( " +
            "        SELECT hf_part_no FROM mat_process       WHERE customer_id = :customerId " + importFilter +
            "        UNION " +
            "        SELECT hf_part_no FROM mat_fee           WHERE customer_id = :customerId " + importFilter +
            "        UNION " +
            "        SELECT hf_part_no FROM mat_plating_fee   WHERE customer_id = :customerId " + importFilter +
            "        UNION " +
            // V125: plating_fee 已弃用; 保留 UNION 项兼容历史导入产生的料号候选,
            //       直到 V128+ 旧表标 ARCHIVED 后可移除.
            "        SELECT hf_part_no FROM plating_fee       WHERE customer_id = :customerId " + importFilter +
            "      ) " +
            "ORDER BY (m.id IS NOT NULL) DESC, p.part_no ASC";

        var query = em.createNativeQuery(sql).setParameter("customerId", customerId);
        if (importRecordId != null) {
            query.setParameter("importRecordId", importRecordId);
        }
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
            // internal_material（生产料号管理）视角；缺失时退回 mat_part 的 partName，规格/尺寸留空
            CustomerPartCandidateDTO.HfPartInfo info = new CustomerPartCandidateDTO.HfPartInfo();
            info.partNo = dto.partNo;
            info.partName = row[10] != null ? (String) row[10] : dto.partName;
            info.specification = row[11] != null ? (String) row[11] : null;
            info.sizeInfo = row[12] != null ? (String) row[12] : null;
            info.statusCode = row[13] != null ? (String) row[13] : null;
            dto.hfPartInfo = info;
            result.add(dto);
        }
        LOG.debugf("listCandidates(customerId=%s) → %d rows", customerId, result.size());
        return result;
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

        // 3. 按 hf 集合 JOIN mat_part + mat_customer_part_mapping + internal_material
        String sql =
            "SELECT DISTINCT p.part_no, p.part_name, p.unit_weight, p.weight_unit, " +
            "       m.customer_product_no, m.customer_part_name, m.customer_drawing_no, " +
            "       m.base_currency, m.quote_currency, " +
            "       (m.id IS NOT NULL) AS customer_specific, " +
            "       im.name AS im_name, im.specification AS im_spec, " +
            "       im.size AS im_size, im.status_code AS im_status " +
            "FROM mat_part p " +
            "LEFT JOIN mat_customer_part_mapping m " +
            "       ON m.hf_part_no = p.part_no AND m.customer_id = :customerId " +
            "LEFT JOIN internal_material im " +
            "       ON im.material_no = p.part_no " +
            "WHERE p.status_code = 'Y' AND p.part_no IN :hfs " +
            "ORDER BY (m.id IS NOT NULL) DESC, p.part_no ASC";
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
            CustomerPartCandidateDTO.HfPartInfo info = new CustomerPartCandidateDTO.HfPartInfo();
            info.partNo = dto.partNo;
            info.partName = row[10] != null ? (String) row[10] : dto.partName;
            info.specification = row[11] != null ? (String) row[11] : null;
            info.sizeInfo = row[12] != null ? (String) row[12] : null;
            info.statusCode = row[13] != null ? (String) row[13] : null;
            dto.hfPartInfo = info;
            result.add(dto);
        }
        LOG.infof("V6 listCandidates(customerId=%s, importRecordId=%s) → %d 行 (hfPairs=%d)",
                customerId, importRecordId, result.size(), hfSet.size());
        return result;
    }
}
