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

        // ═══════════════════════════════════════════════════════════════════
        // task-260902 · B-16b：列表 = material_customer_map（导入来的） ∪ sel_product_no（选配来的）
        //
        // 方案甲下选配<b>不再写 mcm</b>（B-8），所以「从产品库添加」必须并上 sel_product_no，
        // 否则选配产品在列表里彻底消失。
        //
        // 🔄 source 语义随之简化（评审 P2-17）：现状 `CASE WHEN mcm.customer_product_no IS NULL
        //    THEN 'CONFIGURED' ELSE 'EXISTING' END` 会被 B-8 静默翻转（选配产品从此有编号了）——
        //    改为**按来源表判定**：来自 sel_product_no → CONFIGURED，来自 mcm → EXISTING。
        //    语义更准，且不再依赖「编号是否为空」这个易变判据。
        //
        // ⚠️ AC-12b④「该产品只出现一次（不因两行映射而重复）」：一个销售料号可以对多个客户产品编号
        //    （sel_product_no 的 quote_part_no 刻意不唯一，那正是 AC-12b 要的），
        //    但列表必须按销售料号**去重成一行** —— 故最外层用 DISTINCT ON (material_no)，
        //    取 created_at 最早的那个编号作代表。筛选谓词在去重之前生效，
        //    所以「按任一编号都能搜到该产品」与「只出现一次」两个断言同时成立。
        //    ✅ 用户裁决（2026-09-03，AC-12b⑤-b）：**去重对，少显示不对，两者不冲突** ——
        //       行按料号去重保留，同时用 customerProductNos 数组把该料号名下**全部编号**带出来。
        //       否则用第二个编号的销售打开列表看到的是别人的编号，会「认不出这是自己的产品」，
        //       那是本任务要修的问题的另一面（从**找不到**变成**认不出**）。
        //    另：编号已进 spn 的料号不再从 mcm 出一次（mcm 侧的 NOT EXISTS 谓词）。
        //
        // 🚫 保留 mcm 侧那句 `OR EXISTS (SELECT 1 FROM sel_part_signature …)` 兜底不动 ——
        //    它本是为「选配料号在 mcm 里编号为空」打的补丁，B-8 落地后可退役，
        //    但**退不退由主线裁定**（backtask B-16b 明确要求不要顺手删）。
        // ═══════════════════════════════════════════════════════════════════
        StringBuilder where = new StringBuilder(
                "mcm.system_type = 'QUOTE' AND mcm.customer_no = :customerNo " +
                "AND mcm.pending_quotation_id IS NULL " +
                "AND (mcm.customer_product_no IS NOT NULL " +
                "     OR EXISTS (SELECT 1 FROM sel_part_signature sps WHERE sps.quote_part_no = mcm.material_no AND sps.customer_no = mcm.customer_no)) " +
                // 编号已被 sel_product_no 收录的，由 spn 分支出行，避免同一 (料号, 编号) 出两行
                "AND NOT EXISTS (SELECT 1 FROM sel_product_no spn0 WHERE spn0.customer_no = mcm.customer_no " +
                "     AND spn0.quote_part_no = mcm.material_no)");
        StringBuilder spnWhere = new StringBuilder("spn.customer_no = :customerNo");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customerNo", customerNo);

        if (notBlank(customerProductNo)) {
            where.append(" AND mcm.customer_product_no ILIKE :customerProductNo");
            spnWhere.append(" AND spn.customer_product_no ILIKE :customerProductNo");
            params.put("customerProductNo", likePattern(customerProductNo));
        }
        if (notBlank(salesPartNo)) {
            where.append(" AND mcm.material_no ILIKE :salesPartNo");
            spnWhere.append(" AND spn.quote_part_no ILIKE :salesPartNo");
            params.put("salesPartNo", likePattern(salesPartNo));
        }
        if (notBlank(productName)) {
            where.append(" AND mcm.customer_material_name ILIKE :productName");
            spnWhere.append(" AND COALESCE(NULLIF(spn.customer_product_name,''), smm.material_name) ILIKE :productName");
            params.put("productName", likePattern(productName));
        }
        if (notBlank(spec)) {
            where.append(" AND COALESCE(NULLIF(mm.specification,''), mm.dimension) ILIKE :spec");
            spnWhere.append(" AND COALESCE(NULLIF(smm.specification,''), smm.dimension) ILIKE :spec");
            params.put("spec", likePattern(spec));
        }

        // 两侧共用的行构造（列顺序必须逐位对齐，UNION ALL 按位置配对）。
        String mcmSelect =
                "SELECT mcm.material_no, mcm.customer_product_no, mcm.created_at AS src_created_at, " +
                "       COALESCE(NULLIF(mcm.customer_material_name,''), mm.material_name, mm.material_type, mcm.material_no) AS product_name, " +
                "       COALESCE(NULLIF(mm.specification,''), mm.dimension) AS spec, " +
                "       (model3d.id IS NOT NULL) AS has3d, model3d.thumbnail_url, " +
                "       'EXISTING' AS source, " +
                "       (SELECT sps.product_type FROM sel_part_signature sps WHERE sps.quote_part_no = mcm.material_no AND sps.customer_no = mcm.customer_no ORDER BY sps.created_at DESC LIMIT 1) AS config_product_type " +
                "FROM material_customer_map mcm " +
                "LEFT JOIN material_master mm ON mm.material_no = mcm.material_no " +
                "LEFT JOIN model_config model3d " +
                "       ON model3d.subject_type = 'SALES_PART' " +
                "      AND model3d.subject_key = mcm.material_no " +
                "      AND model3d.is_current = true " +
                "WHERE " + where;

        String spnSelect =
                "SELECT spn.quote_part_no AS material_no, spn.customer_product_no, spn.created_at AS src_created_at, " +
                "       COALESCE(NULLIF(spn.customer_product_name,''), smm.material_name, spn.quote_part_no) AS product_name, " +
                "       COALESCE(NULLIF(smm.specification,''), smm.dimension) AS spec, " +
                "       (smodel3d.id IS NOT NULL) AS has3d, smodel3d.thumbnail_url, " +
                "       'CONFIGURED' AS source, " +
                "       (SELECT sps.product_type FROM sel_part_signature sps WHERE sps.quote_part_no = spn.quote_part_no AND sps.customer_no = spn.customer_no ORDER BY sps.created_at DESC LIMIT 1) AS config_product_type " +
                "FROM sel_product_no spn " +
                "LEFT JOIN material_master smm ON smm.material_no = spn.quote_part_no " +
                "LEFT JOIN model_config smodel3d " +
                "       ON smodel3d.subject_type = 'SALES_PART' " +
                "      AND smodel3d.subject_key = spn.quote_part_no " +
                "      AND smodel3d.is_current = true " +
                "WHERE " + spnWhere;

        String unionSql = "(" + mcmSelect + ") UNION ALL (" + spnSelect + ")";

        // AC-12b⑤-b：该 (customer_no, material_no) 名下的全部客户产品编号，按 created_at 升序。
        // 🚫 **不逐行查**（那是 backtask B-19 刚治过的 N+1）：这里是一个 GROUP BY 聚合子查询，
        //    与主查询一次 LEFT JOIN 完成，SQL 条数与行数无关。
        String aggSql =
                "SELECT a.material_no, array_agg(a.customer_product_no ORDER BY a.created_at) AS all_product_nos "
                + "FROM ( "
                + "  SELECT spn.quote_part_no AS material_no, spn.customer_product_no, spn.created_at "
                + "    FROM sel_product_no spn WHERE spn.customer_no = :customerNo "
                + "  UNION ALL "
                + "  SELECT mcm2.material_no, mcm2.customer_product_no, mcm2.created_at "
                + "    FROM material_customer_map mcm2 "
                + "   WHERE mcm2.system_type = 'QUOTE' AND mcm2.customer_no = :customerNo "
                + "     AND mcm2.customer_product_no IS NOT NULL "
                + ") a GROUP BY a.material_no";

        // DISTINCT ON 按销售料号去重（AC-12b④），代表行取 created_at 最早的那条。
        String dedupSql = "SELECT DISTINCT ON (u.material_no) u.material_no, u.customer_product_no, "
                + "u.product_name, u.spec, u.has3d, u.thumbnail_url, u.source, u.config_product_type, "
                + "agg.all_product_nos "
                + "FROM (" + unionSql + ") u "
                + "LEFT JOIN (" + aggSql + ") agg ON agg.material_no = u.material_no "
                + "ORDER BY u.material_no, u.src_created_at, u.customer_product_no";

        // ── 总数（1 条 SQL） ──
        Query countQuery = em.createNativeQuery("SELECT COUNT(*) FROM (" + dedupSql + ") d");
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // ── 分页数据（1 条 SQL，LEFT JOIN 一次带出规格 + 3D，禁逐行查） ──
        Query dataQuery = em.createNativeQuery(
                "SELECT * FROM (" + dedupSql + ") d ORDER BY d.material_no");
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
            dto.customerProductNos = toStringList(r[8]);   // AC-12b⑤-b：全部编号
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

    /**
     * PG {@code text[]} → {@code List<String>}。JDBC 驱动可能给回 {@link java.sql.Array}
     * 或已转好的 {@code Object[]}，两种都要接住；null/空一律返回空 List（🚫 不返 null，
     * 前端 {@code .map()} 直接用）。
     */
    private List<String> toStringList(Object arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        try {
            Object[] items = (arr instanceof java.sql.Array a) ? (Object[]) a.getArray()
                           : (arr instanceof Object[] o) ? o : null;
            if (items == null) return out;
            for (Object it : items) {
                if (it != null) out.add(it.toString());
            }
        } catch (java.sql.SQLException e) {
            // 取数组失败不该让整个列表 500：降级成空数组，代表编号(customerProductNo)仍在。
            return out;
        }
        return out;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String likePattern(String s) {
        return "%" + s.trim() + "%";
    }
}
