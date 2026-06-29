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

    // ====================== 埋点：每类 DB 操作的次数 + 耗时（ThreadLocal，按 sheet 边界 reset/dump） ======================
    /**
     * 写入器分段计时器。每个 SheetHandler 在同一线程内串行调用本写入器，编排器在每个 sheet 边界
     * {@link #reset()} → 跑 handle → 读 {@link #summary()}，即可定位「逐 group N+1」的 DB 往返开销分布。
     * 纯计数/计时,不改变任何写入行为。
     */
    public static final class Profile {
        public int groups;                       // 版本化写入入口被调次数（≈ group 数）
        public int lockN, loadN, verN, flipN, insN;   // 各类 DB 往返次数
        public long lockNs, loadNs, verNs, flipNs, insNs;  // 各类累计耗时(ns)
        public void reset() {
            groups = lockN = loadN = verN = flipN = insN = 0;
            lockNs = loadNs = verNs = flipNs = insNs = 0;
        }
        public int dbCalls() { return lockN + loadN + verN + flipN + insN; }
        public String summary() {
            return String.format(
                "groups=%d dbCalls=%d | lock=%dx/%.0fms load=%dx/%.0fms ver=%dx/%.0fms flip=%dx/%.0fms ins=%dx/%.0fms",
                groups, dbCalls(),
                lockN, lockNs / 1e6, loadN, loadNs / 1e6, verN, verNs / 1e6,
                flipN, flipNs / 1e6, insN, insNs / 1e6);
        }
    }
    private static final ThreadLocal<Profile> PROFILE = ThreadLocal.withInitial(Profile::new);
    /** 取当前线程的写入器计时器（编排器在 sheet 边界 reset + 读 summary）。 */
    public static Profile profile() { return PROFILE.get(); }

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
        PROFILE.get().groups++;
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
            List<Map<String, Object>> toInsert = new ArrayList<>();
            for (Map<String, Object> row : spec.newRows) {
                Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
                all.putAll(row);
                all.put(spec.versionColumn, cur);                  // 复用旧版本号
                all.put("is_current", true);
                toInsert.add(all);
            }
            insertRowsBatched(spec.tableName, toInsert);
            return cur;
        }

        // 4) 触发列变化(或首次写入) → 升版
        String newVersion = nextVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
        if (!existing.isEmpty()) {
            flip(spec.tableName, spec.groupKeyColumns);
        }
        List<Map<String, Object>> toInsert = new ArrayList<>();
        for (Map<String, Object> row : spec.newRows) {
            Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
            all.putAll(row);
            all.put(spec.versionColumn, newVersion);
            all.put("is_current", true);
            toInsert.add(all);
        }
        insertRowsBatched(spec.tableName, toInsert);
        return newVersion;
    }

    // ====================== 批量单表行集版本化（集合化，零 N+1） ======================
    /**
     * 批量版本化写入：整 sheet 所有 group 一次提交。DB 往返与 group 数无关（常数 ~6 次），
     * 逐位等价于「对每个 group 依次调用 {@link #writeVersionedGroup}」。
     *
     * @param groups 保持插入顺序的 groupKey→newRows；各 groupKey 的列集必须一致。
     * @return 每个 groupKey → 本组生效版本号（与逐组返回值一致）。
     */
    public Map<Map<String, Object>, String> writeVersionedGroups(
            String tableName, String versionColumn,
            List<String> contentColumns, List<String> versionTriggerColumns,
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups) {

        if (!ALLOWED_TABLES.contains(tableName)) throw new IllegalArgumentException("表未登记白名单: " + tableName);
        safeIdent(tableName); safeIdent(versionColumn);
        contentColumns.forEach(VersionedV6Writer::safeIdent);
        if (contentColumns.isEmpty()) throw new IllegalArgumentException("contentColumns 不能为空");
        List<String> triggerCols = (versionTriggerColumns == null) ? contentColumns : versionTriggerColumns;
        if (!new HashSet<>(contentColumns).containsAll(triggerCols))
            throw new IllegalArgumentException("versionTriggerColumns 必须是 contentColumns 子集: " + triggerCols);

        Map<Map<String, Object>, String> versionOut = new LinkedHashMap<>();
        if (groups.isEmpty()) return versionOut;

        List<String> gkCols = new ArrayList<>(groups.keySet().iterator().next().keySet());
        gkCols.forEach(VersionedV6Writer::safeIdent);
        for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> e : groups.entrySet()) {
            Map<String, Object> gk = e.getKey();
            if (!new ArrayList<>(gk.keySet()).equals(gkCols))
                throw new IllegalArgumentException("同批 group 的 groupKey 列集必须一致: " + gk.keySet() + " vs " + gkCols);
            requireSystemType(tableName, gk);
            Set<String> overlap = new HashSet<>(gk.keySet()); overlap.retainAll(new HashSet<>(contentColumns));
            if (!overlap.isEmpty()) throw new IllegalArgumentException("contentColumns 与 groupKeyColumns 列名重叠: " + overlap);
            if (e.getValue().isEmpty()) throw new IllegalArgumentException("newRows 为空;整组下线请用专门 API: " + gk);
        }

        Map<String, Object> constPrefix = constantColumns(groups.keySet(), gkCols);
        // 锁/加载前缀必须非空：V6 导入 handler 的 groupKey 恒含常量 system_type(QUOTE/PRICING)，
        // QUOTE 侧还恒含 customer_no，故单把前缀锁对「同组并发写」的串行保证不弱于逐组 advisoryLock。
        // 空前缀会退化为全表加载 + 表级锁，属配置错误，直接拒绝。
        if (constPrefix.isEmpty())
            throw new IllegalStateException(
                "批量写入要求至少一个跨组恒定的 groupKey 列(如 system_type)作锁/加载前缀: " + gkCols);
        advisoryLockPrefix(tableName, constPrefix);                                                  // 1 RT
        Map<List<String>, List<Map<String, Object>>> curByGk =
            loadCurrentByPrefix(tableName, versionColumn, constPrefix, gkCols, contentColumns);      // 1 RT
        Map<List<String>, Integer> maxVerByGk =
            maxVersionByPrefix(tableName, versionColumn, constPrefix, gkCols);                       // 1 RT

        List<UUID> flipIds = new ArrayList<>();
        List<UUID> deleteIds = new ArrayList<>();
        List<Map<String, Object>> toInsert = new ArrayList<>();
        for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> e : groups.entrySet()) {
            Map<String, Object> gk = e.getKey();
            List<Map<String, Object>> newRows = e.getValue();
            List<String> key = gkKey(gk, gkCols);
            List<Map<String, Object>> existing = curByGk.getOrDefault(key, List.of());

            boolean triggerSame = multisetEqual(existing, newRows, triggerCols);
            boolean contentSame = multisetEqual(existing, newRows, contentColumns);

            if (triggerSame && contentSame) {                                  // (a)
                versionOut.put(gk, currentVersionFrom(existing, versionColumn));
                continue;
            }
            if (triggerSame) {                                                 // (b) 原地更新
                String cur = currentVersionFrom(existing, versionColumn);
                for (Map<String, Object> r : existing) deleteIds.add(asUuid(r.get("__id")));
                for (Map<String, Object> row : newRows) toInsert.add(assembleRow(gk, row, versionColumn, cur));
                versionOut.put(gk, cur);
                continue;
            }
            Integer mx = maxVerByGk.get(key);                                  // (c) 升版
            String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
            if (!existing.isEmpty()) for (Map<String, Object> r : existing) flipIds.add(asUuid(r.get("__id")));
            for (Map<String, Object> row : newRows) toInsert.add(assembleRow(gk, row, versionColumn, newVersion));
            versionOut.put(gk, newVersion);
        }

        if (!flipIds.isEmpty())   flipByIds(tableName, flipIds);               // 1 RT
        if (!deleteIds.isEmpty()) deleteByIds(tableName, deleteIds);          // 1 RT
        insertRowsBatched(tableName, toInsert);                                // ~1 RT
        return versionOut;
    }

    /** 本批所有 group 取值恒定的 groupKey 列（用 null 安全 toString 判定）。 */
    private static Map<String, Object> constantColumns(Set<Map<String, Object>> gks, List<String> gkCols) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String col : gkCols) {
            String first = null; boolean init = false, constant = true;
            for (Map<String, Object> gk : gks) {
                String v = textKey(gk.get(col));
                if (!init) { first = v; init = true; }
                else if (!java.util.Objects.equals(first, v)) { constant = false; break; }
            }
            if (constant) out.put(col, gks.iterator().next().get(col));
        }
        return out;
    }

    /** null 安全文本键（匹配 SQL IS NOT DISTINCT FROM 的文本相等；不做数字归一）。 */
    private static String textKey(Object v) { return v == null ? null : v.toString(); }
    private static List<String> gkKey(Map<String, Object> gk, List<String> gkCols) {
        List<String> k = new ArrayList<>(gkCols.size());
        for (String c : gkCols) k.add(textKey(gk.get(c)));
        return k;
    }

    /** 单把 advisory 锁，键 = 表 + 常量前缀值（覆盖本批所有 group）。 */
    private void advisoryLockPrefix(String table, Map<String, Object> constPrefix) {
        long t0 = System.nanoTime();
        String lockKey = table + "|" + constPrefix.values().stream()
            .map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
          .setParameter("k", lockKey).getSingleResult();
        Profile p = PROFILE.get(); p.lockN++; p.lockNs += System.nanoTime() - t0;
    }

    /** NULL 安全等值 WHERE（前缀列）：col IS NOT DISTINCT FROM :p_col AND ...；空前缀返 "TRUE"。 */
    private static String prefixWhere(Map<String, Object> constPrefix) {
        if (constPrefix.isEmpty()) return "TRUE";
        StringBuilder sb = new StringBuilder(); int i = 0;
        for (String col : constPrefix.keySet()) {
            if (i++ > 0) sb.append(" AND ");
            sb.append(col).append(" IS NOT DISTINCT FROM :p_").append(col);
        }
        return sb.toString();
    }
    private Query bindPrefix(Query q, Map<String, Object> constPrefix) {
        for (Map.Entry<String, Object> e : constPrefix.entrySet()) q.setParameter("p_" + e.getKey(), e.getValue());
        return q;
    }

    /** 一次加载常量前缀下所有当前生效行，按完整 groupKey 分桶；每行附 __id + versionColumn 值。 */
    @SuppressWarnings("unchecked")
    private Map<List<String>, List<Map<String, Object>>> loadCurrentByPrefix(
            String table, String versionColumn, Map<String, Object> constPrefix,
            List<String> gkCols, List<String> contentColumns) {
        long t0 = System.nanoTime();
        LinkedHashSet<String> sel = new LinkedHashSet<>();
        sel.add("id"); sel.add(versionColumn); sel.addAll(gkCols); sel.addAll(contentColumns);
        List<String> selCols = new ArrayList<>(sel);
        Query q = em.createNativeQuery("SELECT " + String.join(", ", selCols) + " FROM " + table
            + " WHERE " + prefixWhere(constPrefix) + " AND is_current = TRUE");
        bindPrefix(q, constPrefix);
        List<Object> raw = q.getResultList();
        Map<List<String>, List<Map<String, Object>>> out = new HashMap<>();
        for (Object r : raw) {
            Object[] arr = (selCols.size() == 1) ? new Object[]{r} : (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < selCols.size(); i++) m.put(selCols.get(i), arr[i]);
            m.put("__id", m.get("id"));
            List<String> key = new ArrayList<>(gkCols.size());
            for (String c : gkCols) key.add(textKey(m.get(c)));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        Profile p = PROFILE.get(); p.loadN++; p.loadNs += System.nanoTime() - t0;
        return out;
    }

    /** 一次取常量前缀下每组历史 MAX(数字版本)。 */
    @SuppressWarnings("unchecked")
    private Map<List<String>, Integer> maxVersionByPrefix(
            String table, String versionColumn, Map<String, Object> constPrefix, List<String> gkCols) {
        long t0 = System.nanoTime();
        String gkSel = String.join(", ", gkCols);
        Query q = em.createNativeQuery("SELECT " + gkSel + ", MAX(CASE WHEN " + versionColumn
            + " ~ '^[0-9]+$' THEN " + versionColumn + "::int END) FROM " + table
            + " WHERE " + prefixWhere(constPrefix) + " GROUP BY " + gkSel);
        bindPrefix(q, constPrefix);
        List<Object> raw = q.getResultList();
        Map<List<String>, Integer> out = new HashMap<>();
        for (Object r : raw) {
            Object[] arr = (Object[]) r;                       // gkCols.size() >= 1 → 必为数组
            List<String> key = new ArrayList<>(gkCols.size());
            for (int i = 0; i < gkCols.size(); i++) key.add(textKey(arr[i]));
            Object mx = arr[gkCols.size()];
            out.put(key, mx == null ? null : ((Number) mx).intValue());
        }
        Profile p = PROFILE.get(); p.verN++; p.verNs += System.nanoTime() - t0;
        return out;
    }

    /** 防御式取 uuid：原生结果可能回 UUID 或 String，统一成 UUID（与 CardSnapshotService/ComponentDriverService 约定一致）。 */
    private static UUID asUuid(Object v) {
        return (v instanceof UUID u) ? u : UUID.fromString(String.valueOf(v));
    }
    private static String currentVersionFrom(List<Map<String, Object>> existing, String versionColumn) {
        if (existing.isEmpty()) return "2000";
        return String.valueOf(existing.get(0).get(versionColumn));
    }
    private static Map<String, Object> assembleRow(Map<String, Object> gk, Map<String, Object> row,
                                                   String versionColumn, String version) {
        Map<String, Object> all = new LinkedHashMap<>(gk);
        all.putAll(row);
        all.put(versionColumn, version);
        all.put("is_current", true);
        return all;
    }
    private void flipByIds(String table, List<UUID> ids) {
        long t0 = System.nanoTime();
        for (int s = 0; s < ids.size(); s += 1000) {
            List<UUID> chunk = ids.subList(s, Math.min(s + 1000, ids.size()));
            em.createNativeQuery("UPDATE " + table + " SET is_current = FALSE WHERE id IN (:ids)")
              .setParameter("ids", chunk).executeUpdate();
        }
        Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
    }
    private void deleteByIds(String table, List<UUID> ids) {
        long t0 = System.nanoTime();
        for (int s = 0; s < ids.size(); s += 1000) {
            List<UUID> chunk = ids.subList(s, Math.min(s + 1000, ids.size()));
            em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (:ids)")
              .setParameter("ids", chunk).executeUpdate();
        }
        Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
    }

    // ====================== 批量 BOM 主从版本化 ======================
    /**
     * 批量主从版本化：整 sheet 所有 BOM group 一次提交。逐位等价于逐组
     * {@link #writeVersionedMasterDetail}。仅支持 childVersionColumn != null（多版本保留）路径，
     * 现役全部 BOM handler 均属此类。
     *
     * @param items 每项 = {masterGroupKey, childGroupKey, childRows}；masterFixedColumns 整批恒定。
     * @return masterGroupKey → 版本号。
     */
    public Map<Map<String, Object>, String> writeVersionedMasterDetails(
            String masterTable, String masterVersionColumn, Map<String, ?> masterFixedColumns,
            String childTable, String childVersionColumn, List<String> childContentColumns,
            List<MasterDetailItem> items) {

        if (!ALLOWED_TABLES.contains(masterTable) || !ALLOWED_TABLES.contains(childTable))
            throw new IllegalArgumentException("表未登记白名单: " + masterTable + " / " + childTable);
        if (childVersionColumn == null)
            throw new IllegalArgumentException("批量主从仅支持 childVersionColumn != null（多版本保留）");
        safeIdent(masterTable); safeIdent(masterVersionColumn); safeIdent(childTable); safeIdent(childVersionColumn);
        childContentColumns.forEach(VersionedV6Writer::safeIdent);
        if (masterFixedColumns != null) masterFixedColumns.keySet().forEach(VersionedV6Writer::safeIdent);
        if (childContentColumns.isEmpty()) throw new IllegalArgumentException("childContentColumns 不能为空");

        Map<Map<String, Object>, String> versionOut = new LinkedHashMap<>();
        if (items.isEmpty()) return versionOut;

        List<String> mGkCols = new ArrayList<>(items.get(0).masterGroupKey.keySet());
        List<String> cGkCols = new ArrayList<>(items.get(0).childGroupKey.keySet());
        mGkCols.forEach(VersionedV6Writer::safeIdent);
        cGkCols.forEach(VersionedV6Writer::safeIdent);
        LinkedHashMap<Map<String, Object>, Object> masterKeys = new LinkedHashMap<>();
        LinkedHashMap<Map<String, Object>, Object> childKeys = new LinkedHashMap<>();
        for (MasterDetailItem it : items) {
            requireSystemType(masterTable, it.masterGroupKey);
            requireSystemType(childTable, it.childGroupKey);
            if (it.childRows.isEmpty()) throw new IllegalArgumentException("childRows 为空: " + it.masterGroupKey);
            masterKeys.put(it.masterGroupKey, null); childKeys.put(it.childGroupKey, null);
        }

        Map<String, Object> mPrefix = constantColumns(masterKeys.keySet(), mGkCols);
        Map<String, Object> cPrefix = constantColumns(childKeys.keySet(), cGkCols);
        // 锁/加载前缀必须非空(同 writeVersionedGroups)：BOM handler 的 master/child groupKey 恒含常量 system_type，
        // 故前缀锁对「同组并发写」的串行不弱于逐组 advisoryLock；空前缀会退化为全表加载+表级锁，属配置错误，直接拒绝。
        if (mPrefix.isEmpty())
            throw new IllegalStateException(
                "批量主从写入要求 masterGroupKey 至少一个跨组恒定列(如 system_type)作锁/加载前缀: " + mGkCols);
        if (cPrefix.isEmpty())
            throw new IllegalStateException(
                "批量主从写入要求 childGroupKey 至少一个跨组恒定列(如 system_type)作锁/加载前缀: " + cGkCols);

        advisoryLockPrefix(masterTable, mPrefix);                                                       // 1 RT
        Map<List<String>, List<Map<String, Object>>> curMaster =
            loadCurrentByPrefix(masterTable, masterVersionColumn, mPrefix, mGkCols, List.of());          // 1 RT（content 空→只取 id+ver+gk）
        Map<List<String>, List<Map<String, Object>>> curChild =
            loadCurrentByPrefix(childTable, childVersionColumn, cPrefix, cGkCols, childContentColumns);   // 1 RT
        Map<List<String>, Integer> maxVer =
            maxVersionByPrefix(masterTable, masterVersionColumn, mPrefix, mGkCols);                       // 1 RT

        List<UUID> masterFlip = new ArrayList<>(), childFlip = new ArrayList<>();
        List<Map<String, Object>> masterInsert = new ArrayList<>(), childInsert = new ArrayList<>();
        for (MasterDetailItem it : items) {
            List<String> cKey = gkKey(it.childGroupKey, cGkCols);
            List<String> mKey = gkKey(it.masterGroupKey, mGkCols);
            List<Map<String, Object>> existingChild = curChild.getOrDefault(cKey, List.of());
            if (multisetEqual(existingChild, it.childRows, childContentColumns)) {                        // 不写
                List<Map<String, Object>> em0 = curMaster.getOrDefault(mKey, List.of());
                versionOut.put(it.masterGroupKey, currentVersionFrom(em0, masterVersionColumn));
                continue;
            }
            Integer mx = maxVer.get(mKey);
            String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
            for (Map<String, Object> r : curMaster.getOrDefault(mKey, List.of())) masterFlip.add(asUuid(r.get("__id")));
            for (Map<String, Object> r : existingChild) childFlip.add(asUuid(r.get("__id")));
            Map<String, Object> master = new LinkedHashMap<>(it.masterGroupKey);
            if (masterFixedColumns != null) master.putAll(masterFixedColumns);
            master.put(masterVersionColumn, newVersion); master.put("is_current", true);
            masterInsert.add(master);
            for (Map<String, Object> row : it.childRows) {
                Map<String, Object> all = new LinkedHashMap<>(it.childGroupKey);
                all.putAll(row); all.put(childVersionColumn, newVersion); all.put("is_current", true);
                childInsert.add(all);
            }
            versionOut.put(it.masterGroupKey, newVersion);
        }

        if (!masterFlip.isEmpty()) flipByIds(masterTable, masterFlip);     // 1 RT
        if (!childFlip.isEmpty())  flipByIds(childTable, childFlip);       // 1 RT
        insertRowsBatched(masterTable, masterInsert);                       // ~1 RT（原逐行 insertRowGeneric → 合并）
        insertRowsBatched(childTable, childInsert);                        // ~1 RT
        return versionOut;
    }

    /** 批量主从单项入参。 */
    public static final class MasterDetailItem {
        public final Map<String, Object> masterGroupKey;
        public final Map<String, Object> childGroupKey;
        public final List<Map<String, Object>> childRows;
        public MasterDetailItem(Map<String, Object> masterGroupKey, Map<String, Object> childGroupKey,
                                List<Map<String, Object>> childRows) {
            this.masterGroupKey = masterGroupKey; this.childGroupKey = childGroupKey; this.childRows = childRows;
        }
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

        PROFILE.get().groups++;
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

        // 5. 写子表行集（childVersionColumn!=null 路径批量插入；null-path 保留逐行 upsert）
        List<Map<String, Object>> childToInsert = new ArrayList<>();
        for (Map<String, Object> row : childRows) {
            Map<String, Object> all = new LinkedHashMap<>(childGroupKey);
            all.putAll(row);
            all.put("is_current", true);
            if (childVersionColumn != null) {
                all.put(childVersionColumn, newVersion);   // element_bom_item / material_bom_item(V293+)：写版本列 → 多版本保留
                childToInsert.add(all);
            } else {
                upsertChildRow(childTable, all);            // null-path 子表 upsert（保留备用；V293 后无实际调用方）
            }
        }
        insertRowsBatched(childTable, childToInsert);

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
        long t0 = System.nanoTime();
        String lockKey = table + "|" + groupKey.values().stream()
            .map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
          .setParameter("k", lockKey).getSingleResult();
        Profile p = PROFILE.get(); p.lockN++; p.lockNs += System.nanoTime() - t0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCurrentGroup(
            String table, Map<String, Object> groupKey, List<String> contentColumns) {
        long t0 = System.nanoTime();
        String cols = String.join(", ", contentColumns);
        Query q = em.createNativeQuery(
            "SELECT " + cols + " FROM " + table
                + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        List<Object> raw = q.getResultList();
        Profile pf = PROFILE.get(); pf.loadN++; pf.loadNs += System.nanoTime() - t0;
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
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "SELECT " + versionColumn + " FROM " + table
                + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE LIMIT 1");
        bindWhere(q, groupKey);
        List<?> r = q.getResultList();
        Profile p = PROFILE.get(); p.verN++; p.verNs += System.nanoTime() - t0;
        return r.isEmpty() ? "2000" : String.valueOf(r.get(0));
    }

    /** max(数字版本)+1；无数字版本则 "2000"。非数字版本值（'V_DEFAULT'/'V1' 等）被正则过滤。 */
    private String nextVersionOf(String table, String versionColumn, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + versionColumn + " ~ '^[0-9]+$' THEN "
                + versionColumn + "::int END) FROM " + table
                + " WHERE " + whereClause(groupKey));
        bindWhere(q, groupKey);
        Object max = q.getSingleResult();
        Profile p = PROFILE.get(); p.verN++; p.verNs += System.nanoTime() - t0;
        return (max == null) ? "2000" : String.valueOf(((Number) max).intValue() + 1);
    }

    /** 旧组整体下线：UPDATE ... SET is_current=FALSE WHERE <groupKey> AND is_current=TRUE。 */
    private void flip(String table, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "UPDATE " + table + " SET is_current = FALSE WHERE "
                + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        q.executeUpdate();
        Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
    }

    /** 删除该 groupKey 下仍 is_current=FALSE 的行（仅无版本列子表用，清残留；V293 后 material_bom_item 已版本化、暂无调用方）。 */
    private void deleteNonCurrent(String table, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "DELETE FROM " + table + " WHERE "
                + whereClause(groupKey) + " AND is_current = FALSE");
        bindWhere(q, groupKey);
        q.executeUpdate();
        Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
    }

    /** 删除该 groupKey 下当前生效(is_current=TRUE)的行（原地更新前清当前组，避免同版本号 uq 冲突）。 */
    private void deleteCurrent(String table, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "DELETE FROM " + table + " WHERE "
                + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        q.executeUpdate();
        Profile p = PROFILE.get(); p.flipN++; p.flipNs += System.nanoTime() - t0;
    }

    /** 通用整行 INSERT（列名走 safeIdent，值命名参数绑定）。 */
    private void insertRowGeneric(String table, Map<String, Object> all) {
        long t0 = System.nanoTime();
        List<String> cols = new ArrayList<>(all.keySet());
        cols.forEach(VersionedV6Writer::safeIdent);
        String colSql = String.join(", ", cols);
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) ph.append(", "); ph.append(":v").append(i); }
        Query q = em.createNativeQuery(
            "INSERT INTO " + table + " (" + colSql + ") VALUES (" + ph + ")");
        for (int i = 0; i < cols.size(); i++) q.setParameter("v" + i, all.get(cols.get(i)));
        q.executeUpdate();
        Profile p = PROFILE.get(); p.insN++; p.insNs += System.nanoTime() - t0;
    }

    /**
     * 批量整行 INSERT（等价于逐行 {@link #insertRowGeneric}，仅减少 DB 往返）。
     * <p>语义保持：每行只写入自己拥有的列（其余列保持 DB 默认/NULL）——故按"列名有序列表"签名分组，
     * 同签名行合并为一条多值 INSERT；不同签名行各发一条，与逐行行为逐位一致。
     * <p>分块上限 {@link #INSERT_BATCH_ROWS} 防 PostgreSQL 单语句 65535 绑定参数上限。
     */
    private void insertRowsBatched(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        long t0 = System.nanoTime();
        Map<List<String>, List<Map<String, Object>>> bySig = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            bySig.computeIfAbsent(new ArrayList<>(r.keySet()), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<List<String>, List<Map<String, Object>>> e : bySig.entrySet()) {
            List<String> cols = e.getKey();
            cols.forEach(VersionedV6Writer::safeIdent);
            String colSql = String.join(", ", cols);
            List<Map<String, Object>> group = e.getValue();
            for (int start = 0; start < group.size(); start += INSERT_BATCH_ROWS) {
                List<Map<String, Object>> chunk = group.subList(
                    start, Math.min(start + INSERT_BATCH_ROWS, group.size()));
                StringBuilder vals = new StringBuilder();
                int p = 0;
                for (int ri = 0; ri < chunk.size(); ri++) {
                    if (ri > 0) vals.append(", ");
                    vals.append("(");
                    for (int ci = 0; ci < cols.size(); ci++) {
                        if (ci > 0) vals.append(", ");
                        vals.append(":v").append(p++);
                    }
                    vals.append(")");
                }
                Query q = em.createNativeQuery(
                    "INSERT INTO " + table + " (" + colSql + ") VALUES " + vals);
                p = 0;
                for (Map<String, Object> r : chunk) {
                    for (String c : cols) q.setParameter("v" + (p++), r.get(c));
                }
                q.executeUpdate();
            }
        }
        Profile pf = PROFILE.get(); pf.insN++; pf.insNs += System.nanoTime() - t0;
    }

    /** 单条多值 INSERT 的最大行数（cols × rows 需远低于 PG 65535 参数上限；按最宽 ~30 列留足余量）。 */
    private static final int INSERT_BATCH_ROWS = 500;

    /** 子表无版本维度时的 upsert：INSERT ... ON CONFLICT (表达式) DO UPDATE SET 非键列=EXCLUDED。 */
    private void upsertChildRow(String table, Map<String, Object> all) {
        long t0 = System.nanoTime();
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
        Profile p = PROFILE.get(); p.insN++; p.insNs += System.nanoTime() - t0;
    }
}
