package com.cpq.product.dataset;

import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「客户产品」列表查询服务。
 *
 * <p>父任务 {@code task-260903-产品管理页重做} · B-1 建立（服务其 AC-2 / AC-3 / AC-14）；
 * 子任务 {@code task-260903-产品维护能力增强} 增补：
 * <ul>
 *   <li><b>B-1</b> —— {@link #list} 新增可选 {@code customerNo} 过滤（服务 AC-1 ~ AC-4、AC-14）</li>
 *   <li><b>B-2</b> —— 新增 {@link #listCustomers} 客户候选来源（服务 AC-5）</li>
 * </ul>
 *
 * <p><b>只读</b>：本类不含任何 {@code INSERT/UPDATE/DELETE}，不引用 {@code VersionedGroupWriter}
 * 或任何升版路径。
 *
 * <p>✅ <b>N+1 硬指标</b>：
 * <ul>
 *   <li>{@link #list} 一次请求恒为 <b>2 条 SQL</b>（{@code COUNT} 一条 + 分页数据一条），与返回行数无关</li>
 *   <li>{@link #listCustomers} 一次请求恒为 <b>1 条 SQL</b>（单条 {@code GROUP BY}），与客户数无关</li>
 * </ul>
 * 两者的 {@code customerName} 都由 {@code LEFT JOIN customer} 一次带出，
 * 🚫 禁止逐行查 {@code customer}；🚫 禁止「先查客户再逐个 count」。
 * 列表分页在 SQL 层做（{@code setFirstResult/setMaxResults}），
 * 🚫 禁止全量捞进内存再分页（{@code ds_quote_customer_part} 未来量级与 {@code material_customer_map}
 * 同级，现网 mcm 已 1877 行）。
 *
 * <p>🔧 {@code task-260902} 的 {@code com.cpq.dataset} 包合并后本类应迁入该包（见 {@link DsCustomerParts}）。
 */
@ApplicationScoped
public class DatasetCustomerPartService {

    @Inject
    EntityManager em;

    /**
     * 列元数据（唯一真源在 {@code task-260902} 的 Registry {@code SheetDef.columns}；
     * 该包尚未合并，此处按 {@code V405__ds_quote_tables.sql} 的列注释逐字对齐）。
     * 顺序即 AC-2 断言的列顺序。
     */
    private static final List<DsCustomerParts.Column> COLUMNS = List.of(
            new DsCustomerParts.Column("customerNo",        "客户编号",     "STRING"),
            new DsCustomerParts.Column("customerName",      "客户名称",     "STRING"),
            new DsCustomerParts.Column("customerPartName",  "客户料号名称", "STRING"),
            new DsCustomerParts.Column("customerProductNo", "客户产品编号", "STRING"),
            new DsCustomerParts.Column("customerDrawingNo", "客户图号",     "STRING"),
            new DsCustomerParts.Column("materialNo",        "销售料号",     "STRING")
    );

    /**
     * {@code dataset} → 客户料号物理表名。
     *
     * <p>⚠️ 实测 84 张 {@code ds_*} 表里<b>只有 {@code ds_quote_customer_part} 一张客户料号表</b>：
     * {@code cost-basic} / {@code cost-detail} 的轴本身就是生产料号，没有客户维度 ⇒
     * 传这两个数据集一律 400，🚫 不要为了"通用"去猜一个不存在的表名。
     */
    private static final Map<String, String> TABLE_BY_DATASET = Map.of(
            "quote", "ds_quote_customer_part"
    );

    /**
     * {@code sortBy}（DTO 字段名）→ SQL 表达式白名单。
     * 🔒 白名单是防 SQL 注入的唯一手段：{@code sortBy} 直接拼进 SQL，<b>不在白名单里的一律拒绝</b>。
     */
    private static final Map<String, String> SORTABLE = new LinkedHashMap<>();
    static {
        SORTABLE.put("customerNo",        "t.customer_no");
        SORTABLE.put("customerName",      "c.name");
        SORTABLE.put("customerPartName",  "t.customer_part_name");
        SORTABLE.put("customerProductNo", "t.customer_product_no");
        SORTABLE.put("customerDrawingNo", "t.customer_drawing_no");
        SORTABLE.put("materialNo",        "t.material_no");
    }

    /**
     * @param dataset    数据集，当前仅 {@code quote}
     * @param page       <b>0-based</b>（与主源 {@code GET parts} 一致）
     * @param size       每页行数，{@code <=0} 时按 20
     * @param keyword    模糊匹配 {@code customer_no} / {@code customer_product_no} / {@code material_no}
     *                   三列（父任务 AC-14：搜 {@code CUST-0004} 得 11 行）
     * @param customerNo <b>可选</b>客户过滤（子任务 B-1，服务 AC-1 ~ AC-4、AC-14）。
     *                   {@code null} / 空白 = <b>不过滤</b>（AC-1「所有客户」= 17 行、AC-4 切回还原）；
     *                   <b>精确等值</b>匹配（候选项就来自本列的 {@code GROUP BY}，见 {@link #listCustomers}）；
     *                   与 {@code keyword} 是 <b>AND</b>（AC-3：选 {@code CUST-0004} 再搜
     *                   {@code 0028-2609000001} 得 1 行）；
     *                   🚫 传库里不存在的值 <b>返 {@code total:0} + 空 items，不是 404</b>（AC-14）
     */
    @SuppressWarnings("unchecked")
    public DsCustomerParts list(String dataset, int page, int size, String keyword,
                                String customerNo, String sortBy, String sortDir) {
        String table = requireTable(dataset);

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 500);

        Map<String, Object> params = new LinkedHashMap<>();

        // ── WHERE：customerNo 精确 AND keyword 三列模糊 ─────────────────────
        // 🚨 必须 LEFT JOIN：INNER JOIN 会把未建档客户的行静默丢掉（实测 17 → 14），父任务 AC-2 断言 17。
        String from = "FROM " + table + " t LEFT JOIN customer c ON c.code = t.customer_no ";
        List<String> conds = new ArrayList<>();

        // customerNo：参数绑定（非拼接），空白视为「所有客户」⇒ 不加条件 ⇒ 父任务 17 行的形状不变。
        String cust = customerNo == null ? "" : customerNo.trim();
        if (!cust.isEmpty()) {
            params.put("customerNo", cust);
            conds.add("t.customer_no = :customerNo");
        }

        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            // LIKE 通配符转义：用户输入的 % / _ / \ 必须当字面量，否则「搜 100% 命中全表」。
            String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.put("kw", "%" + escaped + "%");
            conds.add("(t.customer_no ILIKE :kw ESCAPE '\\' "
                    + "   OR t.customer_product_no ILIKE :kw ESCAPE '\\' "
                    + "   OR t.material_no ILIKE :kw ESCAPE '\\')");
        }

        // 多条件之间是 AND（AC-3）；一条都没有时 where 为空串 ⇒ 与父任务原查询逐字等价。
        String where = conds.isEmpty() ? "" : "WHERE " + String.join(" AND ", conds) + " ";

        // ── 总数（第 1 条 SQL） ─────────────────────────────────────────────
        Query countQuery = em.createNativeQuery("SELECT COUNT(*) " + from + where);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // ── ORDER BY：白名单 + 主键兜底 ─────────────────────────────────────
        // 默认按 t.id（= 导入/写入顺序，与用户手上的 Excel 行序一致）；t.id 是主键 ⇒ 分页稳定不跳行。
        String orderCol = (sortBy == null || sortBy.isBlank()) ? null : SORTABLE.get(sortBy.trim());
        if (sortBy != null && !sortBy.isBlank() && orderCol == null) {
            throw new BusinessException(400, "不支持的排序字段: " + sortBy
                    + "（可选：" + String.join(" / ", SORTABLE.keySet()) + "）");
        }
        String dir = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        String orderBy = orderCol == null
                ? "ORDER BY t.id ASC "
                : "ORDER BY " + orderCol + " " + dir + " NULLS LAST, t.id ASC ";

        // ── 分页数据（第 2 条 SQL；LEFT JOIN 一次带出客户名，禁逐行查） ──────
        Query dataQuery = em.createNativeQuery(
                "SELECT t.customer_no, c.name, t.customer_part_name, "
              + "       t.customer_product_no, t.customer_drawing_no, t.material_no "
              + from + where + orderBy);
        params.forEach(dataQuery::setParameter);
        dataQuery.setFirstResult(safePage * safeSize);
        dataQuery.setMaxResults(safeSize);
        List<Object[]> rows = dataQuery.getResultList();

        List<DsCustomerParts.Item> items = new ArrayList<>(rows.size());
        // ✅ N+1 自检：本循环是纯内存装配，无 repository 调用、无懒加载 getter、无 SQL。
        for (Object[] r : rows) {
            DsCustomerParts.Item it = new DsCustomerParts.Item();
            it.customerNo        = str(r[0]);
            it.customerName      = str(r[1]); // JOIN 不到 → null → 前端 —
            it.customerPartName  = str(r[2]);
            it.customerProductNo = str(r[3]);
            it.customerDrawingNo = str(r[4]);
            it.materialNo        = str(r[5]);
            items.add(it);
        }
        return new DsCustomerParts(total, COLUMNS, items);
    }

    /**
     * 客户过滤器的<b>候选来源</b>（子任务 B-2，服务 <b>AC-5</b>）。
     *
     * <p>🚨 <b>候选取自 {@code ds_quote_customer_part} 里实际出现过的 {@code customer_no}
     * （{@code GROUP BY}），不是 {@code customer} 主数据表。</b>
     * 父任务实证：{@code Q13CUST0617}（2 行）与 {@code C1}（1 行）未在 {@code customer} 建档 ——
     * 若候选从 {@code customer} 取，这 3 行<b>看得见却永远筛不出来</b>。AC-5 断言 5 个候选全部出现。
     *
     * <p>⚠️ {@code customer} 表<b>没有 {@code customer_no} 列</b>，键是 {@code code}；
     * 且必须 <b>LEFT</b> JOIN（{@code INNER} 实测把 5 个候选砍成 3 个 / 17 行砍成 14 行）。
     * JOIN 不到时 {@code customerName} 回 {@code null}，前端渲染 {@code （未建档）} 后缀。
     *
     * <p>✅ <b>N+1</b>：恒 <b>1 条 SQL</b>，与客户数无关。
     * {@code MIN(c.name)} + 单列 {@code GROUP BY} 保证「一个 {@code customer_no} 恰好一行」；
     * {@code customer.code} 有唯一约束（{@code customer_code_key}）⇒ LEFT JOIN 是 1:1 ⇒
     * {@code COUNT(*)} 不被 JOIN 放大。
     *
     * <p>🚫 不接受 {@code keyword} —— 候选是<b>全集</b>，不随搜索框收窄
     * （AC-3 要求「过滤器不被搜索清空重置」，候选随搜索变化会让下拉在输入时抖动）。
     *
     * @param dataset 数据集，当前仅 {@code quote}（其余返 400，与 {@link #list} 同口径）
     */
    @SuppressWarnings("unchecked")
    public DsCustomerOptions listCustomers(String dataset) {
        String table = requireTable(dataset);

        Query q = em.createNativeQuery(
                "SELECT t.customer_no, MIN(c.name) AS customer_name, COUNT(*) AS cnt "
              + "FROM " + table + " t LEFT JOIN customer c ON c.code = t.customer_no "
              + "GROUP BY t.customer_no "
              // 已建档在前、未建档在后，组内按编号升序 ⇒ 候选顺序稳定，不随写入顺序漂移。
              + "ORDER BY (MIN(c.name) IS NULL), t.customer_no ASC");
        List<Object[]> rows = q.getResultList();

        List<DsCustomerOptions.Item> items = new ArrayList<>(rows.size());
        // ✅ N+1 自检：本循环是纯内存装配，无 repository 调用、无懒加载 getter、无 SQL。
        for (Object[] r : rows) {
            items.add(new DsCustomerOptions.Item(
                    str(r[0]),
                    str(r[1]),                      // JOIN 不到 → null → 前端「（未建档）」
                    ((Number) r[2]).longValue()));
        }
        return new DsCustomerOptions(items);
    }

    /** {@code dataset} → 物理表名，不支持的数据集统一 400（{@link #list} 与 {@link #listCustomers} 共用一份口径）。 */
    private static String requireTable(String dataset) {
        String table = TABLE_BY_DATASET.get(dataset == null ? "" : dataset.trim());
        if (table == null) {
            throw new BusinessException(400, "数据集不支持客户料号查询: " + dataset
                    + "（仅 quote 有客户维度，cost-basic / cost-detail 的轴是生产料号）");
        }
        return table;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
