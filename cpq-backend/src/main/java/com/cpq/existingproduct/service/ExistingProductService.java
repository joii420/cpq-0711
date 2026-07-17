package com.cpq.existingproduct.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.existingproduct.dto.ExistingProductDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报价单「从已有产品添加」列表服务（task-0712 B3，backtask.md B3 / api.md §2.1）。
 *
 * <p>服务端从 {@code quotation.customer_id} 派生 {@code customer.code}（前端不传客户），
 * 查该客户在 {@code material_customer_map} 下的产品，两条 {@code LEFT JOIN}（{@code material_master}
 * 取规格、{@code model_config} 取 3D）一次带出，<b>单条 SQL，禁逐行查</b>（N+1 硬指标）。
 *
 * <p><b>F005（P0）</b>：{@code QuoteMaterialNoAllocator.mintAndRegister} 每次选配发号会往
 * {@code material_customer_map} 插 {@code customer_product_no=NULL} 的占位组件行 —— 本查询强制
 * {@code WHERE customer_product_no IS NOT NULL}，防止选配副作用污染"已有产品"列表。
 */
@ApplicationScoped
public class ExistingProductService {

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    public PageResult<ExistingProductDTO> list(UUID quotationId, String customerProductNo, String salesPartNo,
                                                String productName, String spec, int page, int size) {
        String customerNo = resolveCustomerNo(quotationId);

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : size;

        // A 方案(2026-07-16):列表也纳入选配发号产品(customer_product_no 尚为 NULL,但已在 sel_part_signature 登记),
        // 前端按 source='CONFIGURED' 标「选配」。仍排除既无客户产品号又非选配登记的纯占位行(旧 F005 只保真产品,
        // 现放宽为「真产品 OR 选配登记」)。
        StringBuilder where = new StringBuilder(
                "mcm.system_type = 'QUOTE' AND mcm.customer_no = :customerNo " +
                "AND (mcm.customer_product_no IS NOT NULL " +
                "     OR EXISTS (SELECT 1 FROM sel_part_signature sps WHERE sps.quote_part_no = mcm.material_no AND sps.customer_no = mcm.customer_no))");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customerNo", customerNo);

        if (notBlank(customerProductNo)) {
            where.append(" AND mcm.customer_product_no ILIKE :customerProductNo");
            params.put("customerProductNo", likePattern(customerProductNo));
        }
        if (notBlank(salesPartNo)) {
            where.append(" AND mcm.material_no ILIKE :salesPartNo");
            params.put("salesPartNo", likePattern(salesPartNo));
        }
        if (notBlank(productName)) {
            where.append(" AND mcm.customer_material_name ILIKE :productName");
            params.put("productName", likePattern(productName));
        }
        if (notBlank(spec)) {
            where.append(" AND COALESCE(NULLIF(mm.specification,''), mm.dimension) ILIKE :spec");
            params.put("spec", likePattern(spec));
        }

        // ── 总数（1 条 SQL） ──
        Query countQuery = em.createNativeQuery(
                "SELECT COUNT(*) FROM material_customer_map mcm " +
                "LEFT JOIN material_master mm ON mm.material_no = mcm.material_no " +
                "WHERE " + where);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // ── 分页数据（1 条 SQL，两 LEFT JOIN 一次带出规格 + 3D，禁逐行查） ──
        Query dataQuery = em.createNativeQuery(
                "SELECT mcm.material_no, mcm.customer_product_no, " +
                "       COALESCE(NULLIF(mcm.customer_material_name,''), mm.material_name, mm.material_type, mcm.material_no) AS product_name, " +
                "       COALESCE(NULLIF(mm.specification,''), mm.dimension) AS spec, " +
                "       (model3d.id IS NOT NULL) AS has3d, model3d.thumbnail_url, " +
                "       CASE WHEN mcm.customer_product_no IS NULL THEN 'CONFIGURED' ELSE 'EXISTING' END AS source, " +
                "       (SELECT sps.product_type FROM sel_part_signature sps WHERE sps.quote_part_no = mcm.material_no AND sps.customer_no = mcm.customer_no ORDER BY sps.created_at DESC LIMIT 1) AS config_product_type " +
                "FROM material_customer_map mcm " +
                "LEFT JOIN material_master mm ON mm.material_no = mcm.material_no " +
                "LEFT JOIN model_config model3d " +
                "       ON model3d.subject_type = 'SALES_PART' " +
                "      AND model3d.subject_key = mcm.material_no " +
                "      AND model3d.is_current = true " +
                "WHERE " + where +
                " ORDER BY mcm.material_no");
        params.forEach(dataQuery::setParameter);
        dataQuery.setFirstResult(safePage * safeSize);
        dataQuery.setMaxResults(safeSize);
        List<Object[]> rows = dataQuery.getResultList();

        List<ExistingProductDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ExistingProductDTO dto = new ExistingProductDTO();
            dto.materialNo = (String) r[0];
            dto.customerProductNo = (String) r[1];
            dto.productName = (String) r[2]; // COALESCE 兜底名(客户物料名→材质名→材质类型→料号),选配产品无客户名时也有可读名
            dto.customerMaterialName = (String) r[2];
            dto.spec = (String) r[3];
            dto.has3d = r[4] != null && (Boolean) r[4];
            dto.thumbnailUrl = (String) r[5];
            dto.source = (String) r[6];            // EXISTING(真·已有,有客户产品号) | CONFIGURED(选配发号)
            dto.configProductType = (String) r[7]; // SIMPLE | COMPOSITE(仅选配产品), 非选配为 null
            content.add(dto);
        }
        return new PageResult<>(content, safePage, safeSize, total);
    }

    /** quotationId → customer.code（material_customer_map.customer_no 用编码字符串，非 UUID）。 */
    @SuppressWarnings("unchecked")
    private String resolveCustomerNo(UUID quotationId) {
        List<Object> rows = em.createNativeQuery(
                        "SELECT c.code FROM quotation q JOIN customer c ON c.id = q.customer_id WHERE q.id = :q")
                .setParameter("q", quotationId)
                .getResultList();
        if (!rows.isEmpty() && rows.get(0) != null) {
            return rows.get(0).toString();
        }
        boolean quotationExists = !em.createNativeQuery("SELECT 1 FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList().isEmpty();
        if (!quotationExists) {
            throw new BusinessException(404, "报价单不存在: " + quotationId);
        }
        throw new BusinessException(400, "报价单未绑定客户，无法查询已有产品: " + quotationId);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String likePattern(String s) {
        return "%" + s.trim() + "%";
    }
}
