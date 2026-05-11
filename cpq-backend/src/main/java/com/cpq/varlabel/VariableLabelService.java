package com.cpq.varlabel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.*;

/**
 * V149 变量标签服务.
 *
 * <p>提供:
 * <ul>
 *   <li>{@link #listAll()} — 全部 ACTIVE 行 (前端选择器一次拉取)</li>
 *   <li>{@link #findByPath(String)} — 单条查询</li>
 *   <li>{@link #groupByCategory()} — 按 category 分组 (前端做"业务域树"渲染)</li>
 *   <li>{@link #quickName(String, String, String, String, String, String)} — 渐进式起名 (用户配置时即时入库)</li>
 * </ul>
 *
 * <p>设计: 不走 Panache, 用 native SQL + 手工字段映射, 与 V104 GlobalVariableService 一致.
 * 这样表结构变更不需要改实体, 但代价是字段需要在 mapRow 里手动列举.
 */
@ApplicationScoped
public class VariableLabelService {

    @Inject
    EntityManager em;

    /**
     * 拉取所有 ACTIVE 行, 按 category + display_name 排序.
     */
    @SuppressWarnings("unchecked")
    public List<VariableLabel> listAll() {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, variable_path, display_name, category, data_type, unit, " +
                "       description, example_value, source_type, status, created_at, updated_at " +
                "FROM variable_label WHERE status = 'ACTIVE' " +
                "ORDER BY category, display_name"
        ).getResultList();
        List<VariableLabel> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(mapRow(r));
        return out;
    }

    /**
     * 按 variable_path 精确查找. 大小写敏感.
     */
    @SuppressWarnings("unchecked")
    public Optional<VariableLabel> findByPath(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, variable_path, display_name, category, data_type, unit, " +
                "       description, example_value, source_type, status, created_at, updated_at " +
                "FROM variable_label WHERE variable_path = :p"
        ).setParameter("p", path).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(mapRow(rows.get(0)));
    }

    /**
     * 按 category 分组. LinkedHashMap 保持稳定顺序 (前端渲染避免抖动).
     */
    public LinkedHashMap<String, List<VariableLabel>> groupByCategory() {
        LinkedHashMap<String, List<VariableLabel>> grouped = new LinkedHashMap<>();
        for (VariableLabel v : listAll()) {
            grouped.computeIfAbsent(v.category, k -> new ArrayList<>()).add(v);
        }
        return grouped;
    }

    /**
     * 渐进式命名: 用户在编辑器里发现未注册字段时弹窗起名, 调此接口入库.
     * - path 已存在 → 视为重命名, 更新 display_name / category.
     * - path 不存在 → 插入新行.
     * - category 必填; data_type/unit 可空 (空时不覆盖已有值).
     */
    @Transactional
    public VariableLabel quickName(String path, String displayName, String category,
                                    String dataType, String unit, String description) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("variable_path 不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name 不能为空");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category 不能为空");
        }
        em.createNativeQuery(
                "INSERT INTO variable_label " +
                "(variable_path, display_name, category, data_type, unit, description) " +
                "VALUES (:p, :n, :c, :dt, :u, :d) " +
                "ON CONFLICT (variable_path) DO UPDATE SET " +
                "  display_name = EXCLUDED.display_name, " +
                "  category = EXCLUDED.category, " +
                "  data_type = COALESCE(EXCLUDED.data_type, variable_label.data_type), " +
                "  unit = COALESCE(EXCLUDED.unit, variable_label.unit), " +
                "  description = COALESCE(EXCLUDED.description, variable_label.description), " +
                "  updated_at = now()"
        )
        .setParameter("p", path)
        .setParameter("n", displayName)
        .setParameter("c", category)
        .setParameter("dt", dataType)
        .setParameter("u", unit)
        .setParameter("d", description)
        .executeUpdate();
        return findByPath(path).orElseThrow();
    }

    /**
     * V149 Phase 2: 样本求值 — 按 hf_part_no 取该 variable_path 当前值.
     *
     * <p>安全约束:
     * <ul>
     *   <li>path 必须形如 {@code v_xxx.col_name} (正则校验, 防注入)</li>
     *   <li>path 必须已注册在 variable_label 表 ACTIVE 状态 (白名单)</li>
     *   <li>view 名必须以 {@code v_} 起头 (双重保险)</li>
     *   <li>hf_part_no 参数化绑定</li>
     * </ul>
     *
     * @param path     例 'v_c_summary_agg.packaging_fee'
     * @param hfPartNo 例 '3120012574'; null/空时不加谓词, 返回视图首行
     * @return 单值 (Number/String/null), 无匹配行返 null
     */
    @SuppressWarnings("unchecked")
    public Object evalAt(String path, String hfPartNo) {
        if (path == null || !path.matches("^v_[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("非法 path 格式: " + path);
        }
        if (findByPath(path).isEmpty()) {
            throw new IllegalArgumentException("path 未在注册表登记: " + path);
        }
        int dot = path.indexOf('.');
        String view = path.substring(0, dot);
        String col = path.substring(dot + 1);
        if (!view.startsWith("v_")) {
            throw new IllegalArgumentException("视图名必须以 v_ 起头: " + view);
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(col).append(" FROM ").append(view);
        boolean hasFilter = hfPartNo != null && !hfPartNo.isBlank();
        if (hasFilter) sql.append(" WHERE hf_part_no = :p");
        sql.append(" LIMIT 1");
        var query = em.createNativeQuery(sql.toString());
        if (hasFilter) query.setParameter("p", hfPartNo);
        List<Object> rows = query.getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private VariableLabel mapRow(Object[] r) {
        VariableLabel v = new VariableLabel();
        v.id            = (UUID) r[0];
        v.variablePath  = (String) r[1];
        v.displayName   = (String) r[2];
        v.category      = (String) r[3];
        v.dataType      = (String) r[4];
        v.unit          = (String) r[5];
        v.description   = (String) r[6];
        v.exampleValue  = (String) r[7];
        v.sourceType    = (String) r[8];
        v.status        = (String) r[9];
        v.createdAt     = toOffsetDateTime(r[10]);
        v.updatedAt     = toOffsetDateTime(r[11]);
        return v;
    }

    /**
     * Hibernate 6 native query 对 TIMESTAMPTZ 列可能返回 java.time.Instant 也可能返回 java.sql.Timestamp,
     * 安全做类型兜底.
     */
    private static java.time.OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.Instant ins) return ins.atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.OffsetDateTime odt) return odt;
        return null;
    }
}
