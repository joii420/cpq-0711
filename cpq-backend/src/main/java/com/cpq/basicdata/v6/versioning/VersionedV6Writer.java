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
 * 必须在事务内调用；同一分组键的并发写入由方法内 pg_advisory_xact_lock 串行化。
 *
 * <p>两种入口：
 * <ul>
 *   <li>{@link #writeVersionedGroup}：单表行集版本化（unit_price / capacity / plating_scheme）。</li>
 *   <li>{@link #writeVersionedMasterDetail}：BOM 主从版本化（element_bom / material_bom + 子表）。</li>
 * </ul>
 * 组匹配统一用 {@code IS NOT DISTINCT FROM}（NULL 安全：material_bom 子表 characteristic 可为 NULL）。
 */
@ApplicationScoped
public class VersionedV6Writer {

    private final EntityManager em;

    public VersionedV6Writer(EntityManager em) { this.em = em; }

    /** 允许写入的表（白名单）。新增表在此登记。 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "unit_price", "capacity", "plating_scheme",
        "element_bom", "element_bom_item",
        "material_bom", "material_bom_item");

    /** 必须按 system_type 维度隔离的表：groupKey 缺 system_type 会导致 flip/版本号跨 QUOTE/PRICING 污染。 */
    private static final Set<String> SYSTEM_TYPE_SCOPED = Set.of(
        "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme");

    /** 护栏①：system_type 维度表的 groupKey 必须含 system_type，否则入口直接抛错（防静默跨域污染）。 */
    private static void requireSystemType(String table, Map<String, Object> groupKey) {
        if (SYSTEM_TYPE_SCOPED.contains(table) && !groupKey.containsKey("system_type")) {
            throw new IllegalArgumentException(
                "表 " + table + " 必须按 system_type 隔离，groupKey 缺 system_type: " + groupKey.keySet());
        }
    }

    /**
     * 子表无版本列时的 upsert 元数据：表达式冲突目标 + 唯一键列集。
     * V293 后 material_bom_item 已切换到 bom_version 多版本保留路径（childVersionColumn="bom_version"），
     * null-path（upsert 覆盖当前 + 删残留）现已无任何调用方，故此 Map 保持空。
     * 若未来新增无版本子表，在此登记冲突目标即可启用 null-path。
     */
    private record ChildUq(String conflictTarget, Set<String> keyCols) {}
    private static final Map<String, ChildUq> CHILD_UQ = Map.of();

    /** 列名白名单校验：只允许 [a-z_][a-z0-9_]* 标识符。 */
    private static String safeIdent(String id) {
        if (id == null || !id.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符: " + id);
        }
        return id;
    }

    // ====================== 单表行集版本化 ======================

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
        requireSystemType(spec.tableName, spec.groupKeyColumns);

        // I1: newRows 为空会静默清空整组 → 拒绝
        if (spec.newRows.isEmpty()) {
            throw new IllegalArgumentException("newRows 为空;整组下线请用专门 API,本方法不接受空写入");
        }
        // I3: contentColumns 为空导致非法 SQL / IndexOutOfBounds → 拒绝
        if (spec.contentColumns.isEmpty()) {
            throw new IllegalArgumentException("contentColumns 不能为空");
        }
        // I2: contentColumns 与 groupKeyColumns 列名重叠 → 拒绝
        Set<String> overlap = new HashSet<>(spec.groupKeyColumns.keySet());
        overlap.retainAll(new HashSet<>(spec.contentColumns));
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("contentColumns 与 groupKeyColumns 列名重叠: " + overlap);
        }

        // C1: 并发串行化（advisory lock，随事务提交/回滚释放）。
        advisoryLock(spec.tableName, spec.groupKeyColumns);

        // 触发列：未指定时退化为 contentColumns（其余表保持现状：任何内容变化即升版）
        List<String> triggerCols =
            (spec.versionTriggerColumns == null) ? spec.contentColumns : spec.versionTriggerColumns;
        // 触发列必须是写入列子集（否则无法从 existing/newRows 取到对应值比较）
        if (!new HashSet<>(spec.contentColumns).containsAll(triggerCols)) {
            throw new IllegalArgumentException(
                "versionTriggerColumns 必须是 contentColumns 子集: " + triggerCols);
        }

        // 1) 读当前生效组内容
        List<Map<String, Object>> existing = loadCurrentGroup(
            spec.tableName, spec.groupKeyColumns, spec.contentColumns);

        boolean triggerSame = multisetEqual(existing, spec.newRows, triggerCols);
        boolean contentSame = multisetEqual(existing, spec.newRows, spec.contentColumns);

        // 2) 触发列与全内容都未变 → 完全相同，复用版本，不写
        if (triggerSame && contentSame) {
            return currentVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
        }

        // 3) 仅非触发列(金额/辅助字段)变化 → 原地更新当前组值，版本号不变、不升版
        //    (existing 为空时 triggerSame 必为 false，不会进此分支)
        if (triggerSame) {
            String cur = currentVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
            deleteCurrent(spec.tableName, spec.groupKeyColumns);   // 删当前组 current 行(避免同版本号 uq 冲突)
            for (Map<String, Object> row : spec.newRows) {
                Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
                all.putAll(row);
                all.put(spec.versionColumn, cur);                  // 复用旧版本号
                all.put("is_current", true);
                insertRowGeneric(spec.tableName, all);
            }
            return cur;
        }

        // 4) 触发列变化(或首次写入) → 升版
        String newVersion = nextVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
        if (!existing.isEmpty()) {
            flip(spec.tableName, spec.groupKeyColumns);
        }
        for (Map<String, Object> row : spec.newRows) {
            Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
            all.putAll(row);
            all.put(spec.versionColumn, newVersion);
            all.put("is_current", true);
            insertRowGeneric(spec.tableName, all);
        }
        return newVersion;
    }

    // ====================== BOM 主从版本化 ======================

    /**
     * BOM 主从版本化（设计 §3.3 / §5.2 / §5.3）。
     * 子表行集决定是否升版；版本号取主表版本列 max+1；主/子 is_current 同步翻转。
     * <ul>
     *   <li>childVersionColumn != null（element_bom_item 用 "characteristic"，material_bom_item 用 "bom_version" V293 起）：
     *       子表写版本列 → 多版本子表保留（历史行 is_current=false 留存）。</li>
     *   <li>childVersionColumn == null：子表走 upsert 覆盖当前 + 删除残留下线行（§5.3），仅保留当前版本。
     *       V293 后 material_bom_item 已切走此路径；该分支现无调用方，保留作通用机制备用。</li>
     *   <li>主/子 groupKey 独立（#5：material_bom_item 无 bom_type 列）。</li>
     *   <li>masterFixedColumns：主表 NOT NULL 固定列（#4：element_bom/material_bom 的 bom_type）。</li>
     *   <li>所有组匹配 NULL 安全（#8：material_bom 子表 characteristic=NULL）。</li>
     * </ul>
     *
     * @return 本组版本号（复用时为旧版本，否则为新版本）。
     */
    public String writeVersionedMasterDetail(
            String masterTable, String masterVersionColumn,
            Map<String, Object> masterGroupKey, Map<String, ?> masterFixedColumns,
            String childTable, String childVersionColumn,
            Map<String, Object> childGroupKey, List<String> childContentColumns,
            List<Map<String, Object>> childRows) {

        if (!ALLOWED_TABLES.contains(masterTable) || !ALLOWED_TABLES.contains(childTable)) {
            throw new IllegalArgumentException("表未登记白名单: " + masterTable + " / " + childTable);
        }
        safeIdent(masterTable); safeIdent(masterVersionColumn); safeIdent(childTable);
        if (childVersionColumn != null) safeIdent(childVersionColumn);
        masterGroupKey.keySet().forEach(VersionedV6Writer::safeIdent);
        childGroupKey.keySet().forEach(VersionedV6Writer::safeIdent);
        childContentColumns.forEach(VersionedV6Writer::safeIdent);
        if (masterFixedColumns != null) masterFixedColumns.keySet().forEach(VersionedV6Writer::safeIdent);
        requireSystemType(masterTable, masterGroupKey);
        requireSystemType(childTable, childGroupKey);

        if (childRows.isEmpty()) {
            throw new IllegalArgumentException("childRows 为空;整组下线请用专门 API");
        }
        if (childContentColumns.isEmpty()) {
            throw new IllegalArgumentException("childContentColumns 不能为空");
        }
        if (childVersionColumn == null && !CHILD_UQ.containsKey(childTable)) {
            throw new IllegalArgumentException("childVersionColumn=null 的子表需在 CHILD_UQ 登记冲突目标: " + childTable);
        }

        advisoryLock(masterTable, masterGroupKey);

        // 1. 比对子表当前生效组（NULL 安全 where + multiset 比较）
        List<Map<String, Object>> existingChild =
            loadCurrentGroup(childTable, childGroupKey, childContentColumns);
        if (multisetEqual(existingChild, childRows, childContentColumns)) {
            return currentVersionOf(masterTable, masterVersionColumn, masterGroupKey);  // 完全不写库（决策①）
        }

        // 2. 升版号 = 主表版本列全历史 max+1（V1 等非数字被忽略，首版 2000）
        String newVersion = nextVersionOf(masterTable, masterVersionColumn, masterGroupKey);

        // 3. 主 + 子旧组下线（NULL 安全）
        flip(masterTable, masterGroupKey);
        flip(childTable, childGroupKey);

        // 4. 写主表一行（groupKey + 固定列 + 版本 + is_current）
        Map<String, Object> master = new LinkedHashMap<>(masterGroupKey);
        if (masterFixedColumns != null) master.putAll(masterFixedColumns);
        master.put(masterVersionColumn, newVersion);
        master.put("is_current", true);
        insertRowGeneric(masterTable, master);

        // 5. 写子表行集
        for (Map<String, Object> row : childRows) {
            Map<String, Object> all = new LinkedHashMap<>(childGroupKey);
            all.putAll(row);
            all.put("is_current", true);
            if (childVersionColumn != null) {
                all.put(childVersionColumn, newVersion);   // element_bom_item：写版本列 → 多版本保留
                insertRowGeneric(childTable, all);
            } else {
                upsertChildRow(childTable, all);            // material_bom_item：uq 无版本 → upsert 覆盖当前
            }
        }

        // 6. childVersionColumn==null：清理仍 is_current=false 的残留下线子行（仅保留当前版本明细，§5.3）。
        //    element_bom_item（childVersionColumn!=null）绝不走此步——否则删除多版本历史。
        if (childVersionColumn == null) {
            deleteNonCurrent(childTable, childGroupKey);
        }
        return newVersion;
    }

    // ====================== 通用私有方法（参数化，单表/主从共用） ======================

    /** NULL 安全分组 WHERE：col IS NOT DISTINCT FROM :g_col AND ...（不含 is_current 条件）。 */
    private static String whereClause(Map<String, Object> groupKey) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String col : groupKey.keySet()) {
            if (i++ > 0) sb.append(" AND ");
            sb.append(col).append(" IS NOT DISTINCT FROM :g_").append(col);
        }
        return sb.toString();
    }

    private Query bindWhere(Query q, Map<String, Object> groupKey) {
        for (Map.Entry<String, Object> e : groupKey.entrySet()) {
            q.setParameter("g_" + e.getKey(), e.getValue());
        }
        return q;
    }

    /** 并发串行化：同一分组键在事务内取 advisory lock，避免双 current/重复版本。 */
    private void advisoryLock(String table, Map<String, Object> groupKey) {
        String lockKey = table + "|" + groupKey.values().stream()
            .map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
          .setParameter("k", lockKey).getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCurrentGroup(
            String table, Map<String, Object> groupKey, List<String> contentColumns) {
        String cols = String.join(", ", contentColumns);
        Query q = em.createNativeQuery(
            "SELECT " + cols + " FROM " + table
                + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        List<Object> raw = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : raw) {
            Object[] arr = (contentColumns.size() == 1) ? new Object[]{r} : (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < contentColumns.size(); i++) m.put(contentColumns.get(i), arr[i]);
            out.add(m);
        }
        return out;
    }

    /** 行数 + contentColumns 规范化 multiset 全等（与顺序无关，避免单列排序在重复值下误判）。 */
    private boolean multisetEqual(List<Map<String, Object>> a, List<Map<String, Object>> b, List<String> cols) {
        if (a.size() != b.size()) return false;
        return tally(a, cols).equals(tally(b, cols));
    }

    /** 把每行 contentColumns 规范化拼成 key,按出现次数计数,得到与顺序无关的 multiset。 */
    private Map<String, Integer> tally(List<Map<String, Object>> rows, List<String> cols) {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            for (String c : cols) { sb.append(norm(row.get(c))).append(' '); }
            m.merge(sb.toString(), 1, Integer::sum);
        }
        return m;
    }

    /** 规范化：数字用 stripTrailingZeros，其余 toString；null→"" （防 '12' vs '12.0' / null 误判）。 */
    private static String norm(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }

    private String currentVersionOf(String table, String versionColumn, Map<String, Object> groupKey) {
        Query q = em.createNativeQuery(
            "SELECT " + versionColumn + " FROM " + table
                + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE LIMIT 1");
        bindWhere(q, groupKey);
        List<?> r = q.getResultList();
        return r.isEmpty() ? "2000" : String.valueOf(r.get(0));
    }

    /** max(数字版本)+1；无数字版本则 "2000"。非数字版本值（'V_DEFAULT'/'V1' 等）被正则过滤。 */
    private String nextVersionOf(String table, String versionColumn, Map<String, Object> groupKey) {
        Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + versionColumn + " ~ '^[0-9]+$' THEN "
                + versionColumn + "::int END) FROM " + table
                + " WHERE " + whereClause(groupKey));
        bindWhere(q, groupKey);
        Object max = q.getSingleResult();
        return (max == null) ? "2000" : String.valueOf(((Number) max).intValue() + 1);
    }

    /** 旧组整体下线：UPDATE ... SET is_current=FALSE WHERE <groupKey> AND is_current=TRUE。 */
    private void flip(String table, Map<String, Object> groupKey) {
        Query q = em.createNativeQuery(
            "UPDATE " + table + " SET is_current = FALSE WHERE "
                + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        q.executeUpdate();
    }

    /** 删除该 groupKey 下仍 is_current=FALSE 的行（仅 material_bom_item 这类无版本子表用，清残留）。 */
    private void deleteNonCurrent(String table, Map<String, Object> groupKey) {
        Query q = em.createNativeQuery(
            "DELETE FROM " + table + " WHERE "
                + whereClause(groupKey) + " AND is_current = FALSE");
        bindWhere(q, groupKey);
        q.executeUpdate();
    }

    /** 删除该 groupKey 下当前生效(is_current=TRUE)的行（原地更新前清当前组，避免同版本号 uq 冲突）。 */
    private void deleteCurrent(String table, Map<String, Object> groupKey) {
        Query q = em.createNativeQuery(
            "DELETE FROM " + table + " WHERE "
                + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        q.executeUpdate();
    }

    /** 通用整行 INSERT（列名走 safeIdent，值命名参数绑定）。 */
    private void insertRowGeneric(String table, Map<String, Object> all) {
        List<String> cols = new ArrayList<>(all.keySet());
        cols.forEach(VersionedV6Writer::safeIdent);
        String colSql = String.join(", ", cols);
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) ph.append(", "); ph.append(":v").append(i); }
        Query q = em.createNativeQuery(
            "INSERT INTO " + table + " (" + colSql + ") VALUES (" + ph + ")");
        for (int i = 0; i < cols.size(); i++) q.setParameter("v" + i, all.get(cols.get(i)));
        q.executeUpdate();
    }

    /** 子表无版本维度时的 upsert：INSERT ... ON CONFLICT (表达式) DO UPDATE SET 非键列=EXCLUDED。 */
    private void upsertChildRow(String table, Map<String, Object> all) {
        ChildUq uq = CHILD_UQ.get(table);
        if (uq == null) throw new IllegalArgumentException("无 upsert 冲突目标登记: " + table);
        List<String> cols = new ArrayList<>(all.keySet());
        cols.forEach(VersionedV6Writer::safeIdent);
        String colSql = String.join(", ", cols);
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) ph.append(", "); ph.append(":v").append(i); }
        StringBuilder set = new StringBuilder();
        for (String c : cols) {
            if (uq.keyCols().contains(c)) continue;   // 唯一键列不更新
            if (set.length() > 0) set.append(", ");
            set.append(c).append(" = EXCLUDED.").append(c);
        }
        String sql = "INSERT INTO " + table + " (" + colSql + ") VALUES (" + ph + ") "
            + "ON CONFLICT " + uq.conflictTarget() + " DO UPDATE SET " + set;
        Query q = em.createNativeQuery(sql);
        for (int i = 0; i < cols.size(); i++) q.setParameter("v" + i, all.get(cols.get(i)));
        q.executeUpdate();
    }
}
