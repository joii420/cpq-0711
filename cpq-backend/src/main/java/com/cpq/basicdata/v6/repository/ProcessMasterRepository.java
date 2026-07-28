package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V6 工序主数据只读仓储。
 */
@ApplicationScoped
public class ProcessMasterRepository implements PanacheRepositoryBase<ProcessMaster, UUID> {

    /**
     * 排序白名单（task-0728 · api.md A2）：{@code sortBy} → **实体属性名**（由 Hibernate 翻成列名）。
     *
     * <p><b>纪律</b>：{@code sortBy} 原串永不进 {@link Sort} —— 一律 Map 查表，
     * 未命中回退默认序 {@link #DEFAULT_SORT_FIELD} ASC（不报错，见 api.md §6）。
     */
    private static final Map<String, String> SORT_WHITELIST = Map.ofEntries(
        Map.entry("processNo",         "processNo"),
        Map.entry("processName",       "processName"),
        Map.entry("processCategory",   "processCategory"),
        Map.entry("isOutsource",       "isOutsource"),
        Map.entry("standardCurrency",  "standardCurrency"),
        Map.entry("standardUnit",      "standardUnit"),
        Map.entry("defaultDefectRate", "defaultDefectRate"),
        Map.entry("updatedAt",         "updatedAt"));

    /** 默认序 / 稳定次序键：process_no 升序（现状口径，task-0728 未改）。 */
    private static final String DEFAULT_SORT_FIELD = "processNo";

    /** 关键字条件：**裸 OR**，与其它条件 AND 连接时必须整体加括号（见 {@link #andJoin}）。 */
    private static final String KEYWORD_PREDICATE =
        "LOWER(processNo) LIKE :kw OR LOWER(processName) LIKE :kw";

    /**
     * 按关键字模糊搜索（processNo 或 processName），返回分页查询对象。
     * 调用方用 .page(...).list() 分页。
     *
     * <p>兼容重载：等价于不传任何排序 / 过滤参数。
     *
     * @param keyword 可为 null / 空，表示不过滤
     */
    public PanacheQuery<ProcessMaster> search(String keyword) {
        return search(keyword, null, null, null, null);
    }

    /**
     * 按关键字模糊搜索 + 排序 + 过滤（task-0728 · api.md A2），返回分页查询对象。
     *
     * <p>count 与分页共用同一个 {@link PanacheQuery}，过滤条件天然同步，不存在
     * 「共 N 条与实际行数对不上」的两条 SQL 不同步问题。
     *
     * @param keyword         可为 null / 空 → 不过滤
     * @param sortBy          见 {@link #SORT_WHITELIST}；null / 非白名单 → 默认序
     * @param sortOrder       {@code asc}（默认） / {@code desc}，大小写不敏感
     * @param isOutsource     null → 不过滤；true=外协、false=自制。
     *                        <b>用 {@code = :isOutsource} 而非 IS NOT TRUE</b>：
     *                        {@code is_outsource IS NULL} 的行两侧都不出现（api.md A2 语义细则）
     * @param processCategory null / 空白 → 不过滤；否则**精确匹配**（非 ILIKE）
     */
    public PanacheQuery<ProcessMaster> search(String keyword, String sortBy, String sortOrder,
                                              Boolean isOutsource, String processCategory) {
        Sort sort = buildSort(sortBy, sortOrder);
        String cat = (processCategory == null || processCategory.isBlank()) ? null : processCategory.trim();

        List<String> preds = new ArrayList<>(3);
        Map<String, Object> params = new HashMap<>();
        if (keyword != null && !keyword.isBlank()) {
            preds.add(KEYWORD_PREDICATE);
            params.put("kw", "%" + keyword.toLowerCase() + "%");
        }
        if (isOutsource != null) {
            preds.add("isOutsource = :isOutsource");
            params.put("isOutsource", isOutsource);
        }
        if (cat != null) {
            preds.add("processCategory = :cat");
            params.put("cat", cat);
        }
        if (preds.isEmpty()) {
            return findAll(sort);
        }
        return find(andJoin(preds), sort, params);
    }

    /** 工序分类去重列表（task-0728 · api.md A3）。空表返回 {@code []}（不是 null）。 */
    public List<String> listDistinctCategories() {
        return getEntityManager().createQuery(
                "SELECT DISTINCT p.processCategory FROM ProcessMaster p"
                + " WHERE p.processCategory IS NOT NULL AND p.processCategory <> ''"
                + " ORDER BY p.processCategory", String.class)
            .getResultList();
    }

    /**
     * 排序白名单查表 → {@link Sort}。命中时一律 {@code NULLS LAST}（升降序都把空值放最后，
     * api.md A2），并尾部固定追加 process_no 升序作稳定次序键；未命中回退默认序。
     */
    private static Sort buildSort(String sortBy, String sortOrder) {
        String field = (sortBy == null) ? null : SORT_WHITELIST.get(sortBy);
        if (field == null) {
            return Sort.by(DEFAULT_SORT_FIELD).ascending();   // 与改造前逐字一致
        }
        Sort.Direction dir = "desc".equalsIgnoreCase(sortOrder)
            ? Sort.Direction.Descending : Sort.Direction.Ascending;
        Sort sort = Sort.by(field, dir, Sort.NullPrecedence.NULLS_LAST);
        if (!DEFAULT_SORT_FIELD.equals(field)) {
            sort = sort.and(DEFAULT_SORT_FIELD, Sort.Direction.Ascending);
        }
        return sort;
    }

    /**
     * predicate 列表拼 JPQL where 片段：1 个原样（无 AND ⇒ 不需括号，且与改造前逐字相同）；
     * ≥2 个则**每个各自加括号**再 AND 连接 —— 关键字条件是裸 OR，不加括号会被
     * {@code A OR B AND C} 的优先级解析成 {@code A OR (B AND C)}，过滤静默失效。
     */
    private static String andJoin(List<String> preds) {
        if (preds.size() == 1) return preds.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < preds.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append('(').append(preds.get(i)).append(')');
        }
        return sb.toString();
    }

    /** 按工序名称精确取第一条（process_no 升序）。供导入工序回填用（决策 #5）。 */
    public Optional<ProcessMaster> findFirstByProcessName(String name) {
        return find("processName = ?1 ORDER BY processNo ASC", name).firstResultOptional();
    }
}
