package com.cpq.basicdata.v6.versioning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.util.*;

/**
 * 组级版本化写入工具（设计方案 §5）。
 * 版本列为 VARCHAR 存数字字符串，起始 "2000"；max+1 时忽略非数字版本值（如导入的 'V_DEFAULT'）。
 * 表名/列名走白名单校验防注入；值用命名参数绑定。
 */
@ApplicationScoped
public class VersionedV6Writer {

    private final EntityManager em;

    public VersionedV6Writer(EntityManager em) { this.em = em; }

    /** 允许写入的表（白名单）。新增表在此登记。 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "unit_price", "capacity", "element_bom", "element_bom_item",
        "material_bom", "material_bom_item");

    /** 列名白名单校验：只允许 [a-z_][a-z0-9_]* 标识符。 */
    private static String safeIdent(String id) {
        if (id == null || !id.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符: " + id);
        }
        return id;
    }

    /**
     * 对一组行做版本化写入。
     * @return 本组当前生效使用的版本号（复用时为旧版本，否则为新版本）。
     */
    public String writeVersionedGroup(VersionedGroupSpec spec) {
        if (!ALLOWED_TABLES.contains(spec.tableName)) {
            throw new IllegalArgumentException("表未登记白名单: " + spec.tableName);
        }
        safeIdent(spec.tableName);
        safeIdent(spec.versionColumn);
        spec.groupKeyColumns.keySet().forEach(VersionedV6Writer::safeIdent);
        spec.contentColumns.forEach(VersionedV6Writer::safeIdent);

        // 1) 读当前生效组内容
        List<Map<String, Object>> existing = loadCurrentGroup(spec);

        // 2) 内容相同 → 复用现有版本，不写
        if (rowsEqual(existing, spec)) {
            return currentVersion(spec);
        }

        // 3) 计算新版本号
        String newVersion = nextVersion(spec);

        // 4) 旧组下线
        if (!existing.isEmpty()) {
            buildWhere(em.createNativeQuery(
                "UPDATE " + spec.tableName + " SET is_current = FALSE WHERE "
                    + whereClause(spec) + " AND is_current = TRUE"), spec)
                .executeUpdate();
        }

        // 5) 写新组
        for (Map<String, Object> row : spec.newRows) {
            insertRow(spec, row, newVersion);
        }
        return newVersion;
    }

    private String whereClause(VersionedGroupSpec spec) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String col : spec.groupKeyColumns.keySet()) {
            if (i++ > 0) sb.append(" AND ");
            sb.append(col).append(" = :g_").append(col);
        }
        return sb.toString();
    }

    private Query buildWhere(Query q, VersionedGroupSpec spec) {
        for (Map.Entry<String, Object> e : spec.groupKeyColumns.entrySet()) {
            q.setParameter("g_" + e.getKey(), e.getValue());
        }
        return q;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCurrentGroup(VersionedGroupSpec spec) {
        String cols = String.join(", ", spec.contentColumns);
        Query q = em.createNativeQuery(
            "SELECT " + cols + " FROM " + spec.tableName
                + " WHERE " + whereClause(spec) + " AND is_current = TRUE ORDER BY "
                + spec.contentColumns.get(0));
        buildWhere(q, spec);
        List<Object> raw = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : raw) {
            Object[] arr = (spec.contentColumns.size() == 1) ? new Object[]{r} : (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < spec.contentColumns.size(); i++) m.put(spec.contentColumns.get(i), arr[i]);
            out.add(m);
        }
        return out;
    }

    /** 行数 + 逐行 contentColumns 规范化值全等。existing 已按 contentColumns[0] 排序；newRows 同序比较。 */
    private boolean rowsEqual(List<Map<String, Object>> existing, VersionedGroupSpec spec) {
        if (existing.size() != spec.newRows.size()) return false;
        List<Map<String, Object>> sortedNew = new ArrayList<>(spec.newRows);
        sortedNew.sort(Comparator.comparing(m -> norm(m.get(spec.contentColumns.get(0)))));
        List<Map<String, Object>> sortedOld = new ArrayList<>(existing);
        sortedOld.sort(Comparator.comparing(m -> norm(m.get(spec.contentColumns.get(0)))));
        for (int i = 0; i < sortedNew.size(); i++) {
            for (String c : spec.contentColumns) {
                if (!norm(sortedNew.get(i).get(c)).equals(norm(sortedOld.get(i).get(c)))) return false;
            }
        }
        return true;
    }

    /** 规范化：数字用 stripTrailingZeros，其余 toString；null→"" （防 '12' vs '12.0' / null 误判）。 */
    private static String norm(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }

    private String currentVersion(VersionedGroupSpec spec) {
        Query q = em.createNativeQuery(
            "SELECT " + spec.versionColumn + " FROM " + spec.tableName
                + " WHERE " + whereClause(spec) + " AND is_current = TRUE LIMIT 1");
        buildWhere(q, spec);
        List<?> r = q.getResultList();
        return r.isEmpty() ? "2000" : String.valueOf(r.get(0));
    }

    /** max(数字版本)+1；无数字版本则 "2000"。非数字版本值（'V_DEFAULT' 等）被正则过滤。 */
    private String nextVersion(VersionedGroupSpec spec) {
        Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + spec.versionColumn + " ~ '^[0-9]+$' THEN "
                + spec.versionColumn + "::int END) FROM " + spec.tableName
                + " WHERE " + whereClause(spec));
        buildWhere(q, spec);
        Object max = q.getSingleResult();
        return (max == null) ? "2000" : String.valueOf(((Number) max).intValue() + 1);
    }

    private void insertRow(VersionedGroupSpec spec, Map<String, Object> contentRow, String version) {
        Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
        all.putAll(contentRow);
        all.put(spec.versionColumn, version);
        all.put("is_current", true);
        List<String> cols = new ArrayList<>(all.keySet());
        cols.forEach(VersionedV6Writer::safeIdent);
        String colSql = String.join(", ", cols);
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) ph.append(", "); ph.append(":v").append(i); }
        Query q = em.createNativeQuery(
            "INSERT INTO " + spec.tableName + " (" + colSql + ") VALUES (" + ph + ")");
        for (int i = 0; i < cols.size(); i++) q.setParameter("v" + i, all.get(cols.get(i)));
        q.executeUpdate();
    }
}
