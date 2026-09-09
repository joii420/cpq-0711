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
 * 报价单「从已有产品添加」列表服务（task-260909 B-1，api.md §1）。
 *
 * <p><b>数据源 = 单表 {@code ds_quote_customer_part}</b>（按本报价单客户过滤）。
 * 服务端从 {@code quotation.customer_id} 派生 {@code customer.code}，前端不传客户。
 *
 * <p><b>为什么是单表</b>（task-260909 收敛，D-1）：该列表原读三支 {@code UNION ALL}
 * （{@code material_customer_map} ∪ {@code sel_product_no} ∪ {@code ds_quote_customer_part}），
 * 是选配产出落点被换过三次的沉积，且第三支带一句 {@code source <> 'IMPORT'}，
 * 把导入进来的 2662 行客户产品整批挡在列表外。今天的写入侧只有一条路径：
 * <ul>
 *   <li>导入 {@code POST /api/cpq/dataset/quote/quotation-import} → {@code source='IMPORT'}；</li>
 *   <li>选配 {@code SelDsQuoteWriter.insertCustomerPart} → {@code source='MANUAL'}。</li>
 * </ul>
 * 两者都落 {@code ds_quote_customer_part}，故读侧收敛为单表。{@code source} 从「过滤器」
 * 降级为「标签」：{@code IMPORT→EXISTING}（已有）/ 其余→{@code CONFIGURED}（选配）。
 *
 * <p>🚨 {@code LEFT JOIN v_compat_material_master} <b>刻意保留、不许直连 {@code ds_quote_material}</b>
 * （D-3 裁决）：该视图不是普通 UNION ALL，第二支带 {@code NOT EXISTS} ⇒ <b>老表遮蔽新表</b>。
 * 实测直连新表会让 42 个料号改走新表值（3 个单元格显示值变化）、6 个老表独有料号整个消失 ——
 * 全部落在品名/规格列，且在本次 AC 覆盖范围之外。它是「老表退役」任务的未来切换点。
 *
 * <p><b>N+1 硬指标</b>：单次请求恒为 3 条 SQL（1 条 resolveCustomerNo + 1 条 COUNT + 1 条分页数据），
 * 与返回行数无关。全部编号（{@link ExistingProductDTO#customerProductNos}）走
 * {@code array_agg} 聚合子查询一次 LEFT JOIN 带出，🚫 禁逐行查（backtask B-19 治过的 N+1）。
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

        // 显示值表达式：过滤谓词与 SELECT 列必须用同一个表达式，否则「搜得到的和看到的」对不上。
        // 🚫 productName 的兜底链里不许出现 customer_part_name（AC-5b）——
        //    那会让「品名」与「客户物料名」两列在客户名有值时又变回相同，等于 AC-5 白修。
        final String productNameExpr = "COALESCE(NULLIF(v.material_name,''), d.material_no)";
        final String specExpr = "COALESCE(NULLIF(v.specification,''), v.dimension)";

        StringBuilder where = new StringBuilder("d.customer_no = :customerNo");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customerNo", customerNo);

        // ⚠️ 四个过滤谓词在 DISTINCT ON 去重**之前**生效（都在内层 WHERE 上），
        //    保证「按任一客户产品编号都能搜到该料号」(AC-14) 与「该料号只出现一行」(AC-13) 同时成立。
        if (notBlank(customerProductNo)) {
            where.append(" AND d.customer_product_no ILIKE :customerProductNo");
            params.put("customerProductNo", likePattern(customerProductNo));
        }
        if (notBlank(salesPartNo)) {
            where.append(" AND d.material_no ILIKE :salesPartNo");
            params.put("salesPartNo", likePattern(salesPartNo));
        }
        if (notBlank(productName)) {
            where.append(" AND ").append(productNameExpr).append(" ILIKE :productName");
            params.put("productName", likePattern(productName));
        }
        if (notBlank(spec)) {
            where.append(" AND ").append(specExpr).append(" ILIKE :spec");
            params.put("spec", likePattern(spec));
        }

        // ❗ 只放 JOIN，不拼 WHERE —— dedupSql 还要在它后面再接一个 agg 的 LEFT JOIN，
        //    WHERE 必须排在所有 JOIN 之后（拼反了是语法错，不是静默失效）。
        String joinSql =
                "FROM ds_quote_customer_part d "
                // 🚨 D-3：保留兼容视图，🚫 不许直连 ds_quote_material（理由见类 javadoc）。
                + "LEFT JOIN v_compat_material_master v ON v.material_no = d.material_no ";

        // AC-13：该 (customer_no, material_no) 名下的**全部**客户产品编号，按 created_at 升序。
        // 🚫 不逐行查（N+1）：GROUP BY 聚合子查询，与主查询一次 LEFT JOIN 完成，SQL 条数与行数无关。
        //
        // ⚠️ 排序键必须与 dedupSql 的 ORDER BY **逐位一致**（created_at, customer_product_no）：
        //    只按 created_at 排会在「同料号多编号且 created_at 相同」时退化为不确定序
        //    —— 实测正泰 T260907-M1 两行 created_at 完全相同，漏掉第二排序键会让数组回来是
        //    ["T260907-CP2","T260907-CP1"]，代表编号(CP1)反而排在后面，与 AC-4b 的「整行同源、
        //    代表行优先」直接打架，且每次执行结果还可能不同（假绿/假红两头跳）。
        String aggSql =
                "SELECT a.material_no, array_agg(a.customer_product_no ORDER BY a.created_at, a.customer_product_no) AS all_product_nos "
                + "FROM ds_quote_customer_part a "
                + "WHERE a.customer_no = :customerNo "
                + "GROUP BY a.material_no";

        // DISTINCT ON 按销售料号去重（AC-13），代表行取 created_at 最早的那条编号。
        String dedupSql =
                "SELECT DISTINCT ON (d.material_no) "
                + "       d.material_no, d.customer_product_no, d.customer_drawing_no, d.customer_part_name, "
                + "       " + productNameExpr + " AS product_name, "
                + "       " + specExpr + " AS spec, "
                // AC-6：按来源列判定，不再按「客户产品编号是否为空」这个易变判据。
                + "       CASE WHEN d.source = 'IMPORT' THEN 'EXISTING' ELSE 'CONFIGURED' END AS source, "
                + "       (SELECT sps.product_type FROM sel_part_signature sps "
                + "          WHERE sps.quote_part_no = d.material_no AND sps.customer_no = d.customer_no "
                + "          ORDER BY sps.created_at DESC LIMIT 1) AS config_product_type, "
                + "       agg.all_product_nos "
                + joinSql
                + "LEFT JOIN (" + aggSql + ") agg ON agg.material_no = d.material_no "
                + "WHERE " + where + " "
                + "ORDER BY d.material_no, d.created_at, d.customer_product_no";

        // ── 总数（1 条 SQL）──
        // 与 dedupSql 同口径：DISTINCT 料号数。agg / sel_part_signature 是每料号唯一的
        // LEFT JOIN，不影响行数，故 COUNT 侧省掉，少一次聚合扫描。
        Query countQuery = em.createNativeQuery(
                "SELECT COUNT(*) FROM (SELECT DISTINCT d.material_no " + joinSql
                + "WHERE " + where + ") t");
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // ── 分页数据（1 条 SQL；服务端分页，🚫 不全量拉回内存切）──
        Query dataQuery = em.createNativeQuery(
                "SELECT * FROM (" + dedupSql + ") d ORDER BY d.material_no");
        params.forEach(dataQuery::setParameter);
        dataQuery.setFirstResult(safePage * safeSize);
        dataQuery.setMaxResults(safeSize);
        List<Object[]> rows = dataQuery.getResultList();

        // ⚠️ 纯内存映射，循环体内无任何查询（N+1 自检点）。
        List<ExistingProductDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ExistingProductDTO dto = new ExistingProductDTO();
            dto.materialNo = (String) r[0];
            dto.customerProductNo = (String) r[1];
            dto.customerDrawingNo = (String) r[2];      // AC-4
            dto.customerMaterialName = (String) r[3];   // AC-5：客户怎么叫这个件
            dto.productName = (String) r[4];            // AC-5：主数据品名，空则回退销售料号（AC-5b）
            dto.spec = (String) r[5];
            dto.source = (String) r[6];                 // EXISTING(导入) | CONFIGURED(选配)
            dto.configProductType = (String) r[7];      // SIMPLE | COMPOSITE(仅选配产品)，非选配为 null
            dto.customerProductNos = toStringList(r[8]); // AC-13：全部编号
            content.add(dto);
        }
        return new PageResult<>(content, safePage, safeSize, total);
    }

    /** quotationId → customer.code（ds_quote_customer_part.customer_no 用编码字符串，非 UUID）。 */
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
