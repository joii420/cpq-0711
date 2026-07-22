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
        "material_bom", "material_bom_item",
        "labor_rate", "production_energy", "auxiliary_energy", "tooling_cost", "exchange_rate_v6");

    /** 必须按 system_type 维度隔离的表：groupKey 缺 system_type 会导致 flip/版本号跨 QUOTE/PRICING 污染。 */
    private static final Set<String> SYSTEM_TYPE_SCOPED = Set.of(
        "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme",
        "labor_rate", "production_energy", "auxiliary_energy", "tooling_cost");

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

        // 2) 触发列与全内容都未变 → 完全相同，复用版本，不写（pending/正式两模式共用：
        //    没有任何差异就没有必要造一个 pending 影子组，直接可见当前正式组即代表本单状态）
        if (triggerSame && contentSame) {
            return currentVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
        }

        // task-0721 B2：pending 模式 —— 任何差异（触发列或非触发列）都必须走"新版本号 + 不动现有行"，
        // 不能像正式模式分支 3 那样删当前组原地复用版本号：pending 行不可物理删除/翻转别人正在读的
        // 官方 current 组（他单隔离），且 uq_* 唯一键普遍把版本列纳入键（如 uq_unit_price 含
        // version_no），复用同一版本号会与仍然健在的官方 current 行撞唯一键。
        if (spec.pendingQuotationId != null) {
            String newVersion = nextVersionOf(spec.tableName, spec.versionColumn, spec.groupKeyColumns);
            String supersedes = uuidArrayLiteral(existingIds(existing));
            List<Map<String, Object>> toInsert = new ArrayList<>();
            for (Map<String, Object> row : spec.newRows) {
                toInsert.add(assembleRowPending(spec.groupKeyColumns, row, spec.versionColumn,
                    newVersion, spec.pendingQuotationId, supersedes));
            }
            insertRowsBatched(spec.tableName, toInsert);
            return newVersion;
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
        return writeVersionedGroups(tableName, versionColumn, contentColumns,
            versionTriggerColumns, List.of(), groups, null);
    }

    /**
     * 批量单表行集版本化，含"描述列"（写入但不参与升版比对）。
     * 描述列语义：handler 把描述列键值一并塞进 {@code row}（各 group 的行 Map），写入时随 {@link #assembleRow}
     * 的 {@code all.putAll(row)} 天然落库；比对（{@link #multisetEqual}）只用 contentColumns/triggerCols，
     * 不含描述列 → 达成"写入但不比对"。descriptorColumns 参数本身仅用于入参校验（safeIdent + 与
     * contentColumns/groupKey 不重叠），不改变其余逻辑（与 5 参版本逐字一致）。
     *
     * @param descriptorColumns 只写入、不参与版本比对的列（如 production_no）；无则传 {@code List.of()}。
     */
    public Map<Map<String, Object>, String> writeVersionedGroups(
            String tableName, String versionColumn,
            List<String> contentColumns, List<String> versionTriggerColumns,
            List<String> descriptorColumns,
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups) {
        return writeVersionedGroups(tableName, versionColumn, contentColumns,
            versionTriggerColumns, descriptorColumns, groups, null);
    }

    /**
     * task-0721 B2：pending 模式重载 —— {@code pendingQuotationId} 非 null 时，本批**任何有差异的组**
     * （触发列或非触发列变化）一律新版本号 + {@code is_current=false} + {@code pending_quotation_id}
     * 落库，**不 flip、不 delete** 任何现有正式 current 行（他单隔离；uq_* 唯一键含版本列，pending 行
     * 不能复用现有版本号，见 {@link #writeVersionedGroup} 同款分支注释）。{@code pending_supersedes}
     * = 本组现有 current 行 id 集合，供 B3 视图改写"遮蔽"用。
     *
     * @param pendingQuotationId null=现状正式写入（本方法逐字等价于 6 参版本）；非 null=pending 模式。
     */
    public Map<Map<String, Object>, String> writeVersionedGroups(
            String tableName, String versionColumn,
            List<String> contentColumns, List<String> versionTriggerColumns,
            List<String> descriptorColumns,
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups,
            UUID pendingQuotationId) {

        if (!ALLOWED_TABLES.contains(tableName)) throw new IllegalArgumentException("表未登记白名单: " + tableName);
        safeIdent(tableName); safeIdent(versionColumn);
        contentColumns.forEach(VersionedV6Writer::safeIdent);
        descriptorColumns.forEach(VersionedV6Writer::safeIdent);
        if (contentColumns.isEmpty()) throw new IllegalArgumentException("contentColumns 不能为空");
        List<String> triggerCols = (versionTriggerColumns == null) ? contentColumns : versionTriggerColumns;
        if (!new HashSet<>(contentColumns).containsAll(triggerCols))
            throw new IllegalArgumentException("versionTriggerColumns 必须是 contentColumns 子集: " + triggerCols);
        Set<String> dOverlap = new HashSet<>(descriptorColumns);
        dOverlap.retainAll(new HashSet<>(contentColumns));
        if (!dOverlap.isEmpty()) throw new IllegalArgumentException("descriptorColumns 与 contentColumns 重叠: " + dOverlap);

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
            Set<String> dgOverlap = new HashSet<>(gk.keySet()); dgOverlap.retainAll(new HashSet<>(descriptorColumns));
            if (!dgOverlap.isEmpty()) throw new IllegalArgumentException("descriptorColumns 与 groupKeyColumns 重叠: " + dgOverlap);
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
        // 同时加载 descriptor 列：升版/原地更新时用于「以上一版为底，未提供的 descriptor(如 production_no)
        // 从上一版继承」——production_no 是料号级属性，用户只改工序/单价时不该因文件某格空而丢生产料号。
        List<String> loadCols = new ArrayList<>(contentColumns);
        for (String d : descriptorColumns) if (!loadCols.contains(d)) loadCols.add(d);
        Map<List<String>, List<Map<String, Object>>> curByGk =
            loadCurrentByPrefix(tableName, versionColumn, constPrefix, gkCols, loadCols);            // 1 RT
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

            // (a) 真正完全相同（含无描述列场景）→ 复用版本，不写。
            // 有描述列时，即便 trigger/content 全同，描述列新值也必须落库（写入但不比对），
            // 故降级走 (b) 原地更新（同版本号覆盖），不允许静默跳过写入。
            if (triggerSame && contentSame && descriptorColumns.isEmpty()) {
                versionOut.put(gk, currentVersionFrom(existing, versionColumn));
                continue;
            }
            // 上一版同组各 descriptor 首个非空值：文件新行该 descriptor 为空时据此继承（文件给了非空=用户改动优先）。
            Map<String, Object> prevDesc = carryOverDescriptors(existing, descriptorColumns);

            // task-0721 B2：pending 模式 —— 任何差异（含仅 descriptor 变化）都不能走下面的 (b) 原地更新
            // （物理删除现有 current 行，破坏他单隔离 + 版本号复用会撞 uq_* 唯一键），统一按"升版 + 不
            // 触碰现有行"写 pending 影子组。
            if (pendingQuotationId != null) {
                Integer mx = maxVerByGk.get(key);
                String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
                String supersedes = uuidArrayLiteral(existingIds(existing));
                for (Map<String, Object> row : newRows)
                    toInsert.add(assembleRowPending(gk, applyDescriptorCarryOver(row, descriptorColumns, prevDesc),
                        versionColumn, newVersion, pendingQuotationId, supersedes));
                versionOut.put(gk, newVersion);
                continue;
            }

            if (triggerSame) {                                                 // (b) 原地更新
                String cur = currentVersionFrom(existing, versionColumn);
                for (Map<String, Object> r : existing) deleteIds.add(asUuid(r.get("__id")));
                for (Map<String, Object> row : newRows)
                    toInsert.add(assembleRow(gk, applyDescriptorCarryOver(row, descriptorColumns, prevDesc), versionColumn, cur));
                versionOut.put(gk, cur);
                continue;
            }
            Integer mx = maxVerByGk.get(key);                                  // (c) 升版
            String newVersion = (mx == null) ? "2000" : String.valueOf(mx + 1);
            if (!existing.isEmpty()) for (Map<String, Object> r : existing) flipIds.add(asUuid(r.get("__id")));
            for (Map<String, Object> row : newRows)
                toInsert.add(assembleRow(gk, applyDescriptorCarryOver(row, descriptorColumns, prevDesc), versionColumn, newVersion));
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

    /** 上一版本同组各 descriptor 列的"首个非空"值（供升版/原地更新继承未改动的 descriptor，如 production_no）。 */
    private static Map<String, Object> carryOverDescriptors(List<Map<String, Object>> existing, List<String> descriptorColumns) {
        Map<String, Object> out = new HashMap<>();
        if (descriptorColumns.isEmpty() || existing.isEmpty()) return out;
        for (String d : descriptorColumns) {
            for (Map<String, Object> r : existing) {
                Object v = r.get(d);
                if (v != null && !String.valueOf(v).isBlank()) { out.put(d, v); break; }
            }
        }
        return out;
    }

    /**
     * "复制上一版本 + 仅更新变更字段"：文件新行某 descriptor 列为空/空白时，从上一版同组值 {@code prev} 继承；
     * 文件给了非空值则原样保留（用户改动优先）。仅作用于 descriptor 列，内容列不受影响（内容列以导入为准、驱动升版判定）。
     */
    private static Map<String, Object> applyDescriptorCarryOver(
            Map<String, Object> row, List<String> descriptorColumns, Map<String, Object> prev) {
        if (descriptorColumns.isEmpty() || prev.isEmpty()) return row;
        Map<String, Object> merged = null;
        for (String d : descriptorColumns) {
            Object v = row.get(d);
            if ((v == null || String.valueOf(v).isBlank()) && prev.get(d) != null) {
                if (merged == null) merged = new LinkedHashMap<>(row);
                merged.put(d, prev.get(d));
            }
        }
        return (merged == null) ? row : merged;
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
        return writeVersionedMasterDetails(masterTable, masterVersionColumn, masterFixedColumns,
            childTable, childVersionColumn, childContentColumns, items, null);
    }

    /**
     * task-0721 B2：pending 模式重载 —— {@code pendingQuotationId} 非 null 时，本批任一 item 若子表内容
     * 有差异，主 + 子行一律新版本号 + {@code is_current=false} + {@code pending_quotation_id} 落库，
     * **不 flip** 现有正式 current 主/子行（他单隔离）。{@code pending_supersedes} 分别 = 本组现有主表
     * 行 id 集合 / 现有子表行 id 集合。
     *
     * @param pendingQuotationId null=现状正式写入（本方法逐字等价于 7 参版本）；非 null=pending 模式。
     */
    public Map<Map<String, Object>, String> writeVersionedMasterDetails(
            String masterTable, String masterVersionColumn, Map<String, ?> masterFixedColumns,
            String childTable, String childVersionColumn, List<String> childContentColumns,
            List<MasterDetailItem> items, UUID pendingQuotationId) {

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
        // 主/子版本号一同升级：升版号取「主表 MAX 与 子表 MAX 的较大值」+1（不只看主表）。
        // 背景：正常导入主子总是一起写、版本同步，但外部裸 SQL(如测试数据迁移)可能只升子表不升主表，
        // 导致主表 max 落后于子表已有版本；若只按主表 max+1 会算出一个子表已占用的版本号 → 插子表撞
        // uq_material_bom_item。取两表 max 保证新版本严格高于两边任一历史版本，主子同步升到同一新版。
        Map<List<String>, Integer> childMaxVer =
            maxVersionByPrefix(childTable, childVersionColumn, cPrefix, cGkCols);                         // 1 RT

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
            Integer effMax = maxNullable(maxVer.get(mKey), childMaxVer.get(cKey));
            String newVersion = (effMax == null) ? "2000" : String.valueOf(effMax + 1);
            List<Map<String, Object>> existingMaster = curMaster.getOrDefault(mKey, List.of());

            if (pendingQuotationId != null) {
                // task-0721 B2：pending 模式 —— 不 flip，master/child 均落 pending 影子行。
                String masterSupersedes = uuidArrayLiteral(existingIds(existingMaster));
                String childSupersedes = uuidArrayLiteral(existingIds(existingChild));
                Map<String, Object> masterRow = new LinkedHashMap<>();
                if (masterFixedColumns != null) masterRow.putAll(masterFixedColumns);
                if (it.masterContent != null) masterRow.putAll(it.masterContent);
                masterInsert.add(assembleRowPending(it.masterGroupKey, masterRow, masterVersionColumn,
                    newVersion, pendingQuotationId, masterSupersedes));
                for (Map<String, Object> row : it.childRows) {
                    childInsert.add(assembleRowPending(it.childGroupKey, row, childVersionColumn,
                        newVersion, pendingQuotationId, childSupersedes));
                }
                versionOut.put(it.masterGroupKey, newVersion);
                continue;
            }

            for (Map<String, Object> r : existingMaster) masterFlip.add(asUuid(r.get("__id")));
            for (Map<String, Object> r : existingChild) childFlip.add(asUuid(r.get("__id")));
            Map<String, Object> master = new LinkedHashMap<>(it.masterGroupKey);
            if (masterFixedColumns != null) master.putAll(masterFixedColumns);
            if (it.masterContent != null) master.putAll(it.masterContent);   // 每-item 主表描述列(不进版本比较, 如 production_no)
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

    /** 批量主从单项入参。masterContent = 每-item 主表描述列(可空; 不进 groupKey/版本比较, 如 production_no)。 */
    public static final class MasterDetailItem {
        public final Map<String, Object> masterGroupKey;
        public final Map<String, Object> childGroupKey;
        public final List<Map<String, Object>> childRows;
        public final Map<String, Object> masterContent;
        public MasterDetailItem(Map<String, Object> masterGroupKey, Map<String, Object> childGroupKey,
                                List<Map<String, Object>> childRows) {
            this(masterGroupKey, childGroupKey, childRows, null);
        }
        public MasterDetailItem(Map<String, Object> masterGroupKey, Map<String, Object> childGroupKey,
                                List<Map<String, Object>> childRows, Map<String, Object> masterContent) {
            this.masterGroupKey = masterGroupKey; this.childGroupKey = childGroupKey;
            this.childRows = childRows; this.masterContent = masterContent;
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
        return writeVersionedMasterDetail(masterTable, masterVersionColumn, masterGroupKey, masterFixedColumns,
            childTable, childVersionColumn, childGroupKey, childContentColumns, childRows, null);
    }

    /**
     * task-0721 B2：pending 模式重载 —— {@code pendingQuotationId} 非 null 时子表内容有差异即新版本号 +
     * {@code is_current=false} + {@code pending_quotation_id} 落主/子行，**不 flip**（他单隔离）。仅支持
     * {@code childVersionColumn != null}（与批量版 {@link #writeVersionedMasterDetails} 同限制）。
     */
    public String writeVersionedMasterDetail(
            String masterTable, String masterVersionColumn,
            Map<String, Object> masterGroupKey, Map<String, ?> masterFixedColumns,
            String childTable, String childVersionColumn,
            Map<String, Object> childGroupKey, List<String> childContentColumns,
            List<Map<String, Object>> childRows, UUID pendingQuotationId) {

        if (pendingQuotationId != null && childVersionColumn == null) {
            throw new IllegalArgumentException("pending 模式仅支持 childVersionColumn != null（多版本保留）: " + childTable);
        }
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

        // 2. 升版号 = 「主表 max 与 子表 max 的较大值」+1（主/子一同升级，防主表版本落后于子表已有
        //    版本时算出子表已占用的版本号 → 插子表 uq 冲突；V1 等非数字被忽略，首版 2000）。
        //    childVersionColumn==null（无版本列子表 upsert 路径，现无调用方）时子表无版本可比，仅取主表。
        Integer childMax = (childVersionColumn != null)
            ? maxNumericVersion(childTable, childVersionColumn, childGroupKey) : null;
        Integer effMax = maxNullable(
            maxNumericVersion(masterTable, masterVersionColumn, masterGroupKey), childMax);
        String newVersion = (effMax == null) ? "2000" : String.valueOf(effMax + 1);

        // task-0721 B2：pending 模式 —— 不 flip 现有 current 主/子行（他单隔离），主/子新行落
        // is_current=false + pending_quotation_id + pending_supersedes（现有 current 行 id 集合）。
        if (pendingQuotationId != null) {
            String masterSupersedes = uuidArrayLiteral(loadCurrentIds(masterTable, masterGroupKey));
            String childSupersedes = uuidArrayLiteral(loadCurrentIds(childTable, childGroupKey));
            Map<String, Object> masterContent = new LinkedHashMap<>();
            if (masterFixedColumns != null) masterContent.putAll(masterFixedColumns);
            insertRowGeneric(masterTable, assembleRowPending(masterGroupKey, masterContent, masterVersionColumn,
                newVersion, pendingQuotationId, masterSupersedes));
            List<Map<String, Object>> childToInsert = new ArrayList<>();
            for (Map<String, Object> row : childRows) {
                childToInsert.add(assembleRowPending(childGroupKey, row, childVersionColumn,
                    newVersion, pendingQuotationId, childSupersedes));
            }
            insertRowsBatched(childTable, childToInsert);
            return newVersion;
        }

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

    /**
     * 加载当前生效组（is_current=TRUE，天然等价于 pending_quotation_id IS NULL —— pending 行恒
     * is_current=false，见类头不变量）。附带 {@code __id}（task-0721 B2：pending 模式据此算
     * {@code pending_supersedes}）；既有调用方（{@link #multisetEqual}/{@link #tally}）只按
     * {@code contentColumns} 显式取值，多出的 {@code __id} 键不影响比对语义。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCurrentGroup(
            String table, Map<String, Object> groupKey, List<String> contentColumns) {
        long t0 = System.nanoTime();
        LinkedHashSet<String> selSet = new LinkedHashSet<>();
        selSet.add("id"); selSet.addAll(contentColumns);
        List<String> selCols = new ArrayList<>(selSet);
        String cols = String.join(", ", selCols);
        Query q = em.createNativeQuery(
            "SELECT " + cols + " FROM " + table
                + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        List<Object> raw = q.getResultList();
        Profile pf = PROFILE.get(); pf.loadN++; pf.loadNs += System.nanoTime() - t0;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : raw) {
            Object[] arr = (selCols.size() == 1) ? new Object[]{r} : (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < selCols.size(); i++) {
                String c = selCols.get(i);
                m.put(c.equals("id") ? "__id" : c, arr[i]);
            }
            out.add(m);
        }
        return out;
    }

    /** 该 groupKey 下现有 current 行的 id 集合（供单条 {@link #writeVersionedMasterDetail} pending 分支
     *  组装 pending_supersedes；{@link #loadCurrentGroup}/{@link #loadCurrentByPrefix} 批量路径已各自
     *  内联携带 __id，不需要这个独立查询）。 */
    @SuppressWarnings("unchecked")
    private List<UUID> loadCurrentIds(String table, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "SELECT id FROM " + table + " WHERE " + whereClause(groupKey) + " AND is_current = TRUE");
        bindWhere(q, groupKey);
        List<Object> raw = q.getResultList();
        Profile pf = PROFILE.get(); pf.loadN++; pf.loadNs += System.nanoTime() - t0;
        List<UUID> out = new ArrayList<>(raw.size());
        for (Object r : raw) out.add(asUuid(r));
        return out;
    }

    /** Postgres uuid[] 数组字面量（{@code "{u1,u2}"}），配合插入 SQL 里的 {@code CAST(:vN AS uuid[])}
     *  绑定为纯文本参数（见 {@link #insertRowGeneric}/{@link #insertRowsBatched} 的 PENDING_ARRAY_COLUMNS
     *  特判）——避免依赖 Hibernate 对 {@code UUID[]} 的隐式 JDBC 数组绑定（本类全程走
     *  {@code EntityManager.createNativeQuery} 动态列插入，无法安全拿到 java.sql.Connection 走
     *  {@code createArrayOf}）。ids 为空/null 时返回 null（不写 supersedes，即"纯新增，不遮蔽任何行"）。 */
    private static String uuidArrayLiteral(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    /** 从 loadCurrentGroup/loadCurrentByPrefix 的行集合里取 {@code __id} 列表（供 pending_supersedes）。 */
    private static List<UUID> existingIds(List<Map<String, Object>> existing) {
        List<UUID> ids = new ArrayList<>(existing.size());
        for (Map<String, Object> r : existing) {
            Object v = r.get("__id");
            if (v != null) ids.add(asUuid(v));
        }
        return ids;
    }

    /** 组装 pending 行：is_current=false + pending_quotation_id=pq (+ pending_supersedes，若有)。
     *  与 {@link #assembleRow}（正式模式）对称，供批量/单组写入共用。 */
    private static Map<String, Object> assembleRowPending(
            Map<String, Object> gk, Map<String, Object> row, String versionColumn, String version,
            UUID pendingQuotationId, String supersedesLiteral) {
        Map<String, Object> all = new LinkedHashMap<>(gk);
        all.putAll(row);
        all.put(versionColumn, version);
        all.put("is_current", false);
        all.put("pending_quotation_id", pendingQuotationId);
        if (supersedesLiteral != null) all.put("pending_supersedes", supersedesLiteral);
        return all;
    }

    /** 需要 {@code CAST(:vN AS uuid[])} 而非裸 {@code :vN} 绑定的列（见 {@link #uuidArrayLiteral}）。 */
    private static final Set<String> UUID_ARRAY_COLUMNS = Set.of("pending_supersedes");

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

    /**
     * 规范化：数字用 stripTrailingZeros，其余 toString；null→"" （防 '12' vs '12.0' / null 误判）。
     *
     * <p><b>已知限制（tesk-0709 Task 11 E2E 真文件自检发现，2026-07-11）</b>：本方法对 BigDecimal 不做
     * scale 归一化。若某数值列的 Excel 来源值（公式格 cached 结果，或用户直接输入的高精度小数字面量）
     * 精度超过其 DB 列声明的 {@code numeric(p,s)} scale，则"本次新解析值(全精度)"与"重导时从库里
     * load 出来的 existing(已按列 scale 截断)"在此比较字符串上不相等 → 同一份文件重导会被误判"内容
     * 变化"而错误升版（违反"重导不升版"不变量）。此处**不**在通用层加统一 scale 舍入：本 writer
     * 横跨约 20 张表、每列 scale 不一（多数 numeric(18,6)，tooling_cost.tooling_unit_price /
     * exchange_rate_v6.rate 等为 numeric(18,8)），单一全局 scale 常数会对低精度列舍入不足（仍误判）
     * 或对高精度列舍入过度（掩盖真实变化、导致该丢的版本没升）。**正确修法是各 handler 在构造 content
     * 前按自己实际写入列的 DB scale 显式 {@code setScale(scale, RoundingMode.HALF_UP)}**（P12
     * ToolingCostHandler 已按此模式修复 tooling_unit_price→scale 8；P09/P10 已修复 unit_price→scale 6）。
     * 建议后续对全部 20 个 handler 做一次系统性审计（grep 每个 formula/高精度小数字段 vs 目标列 scale），
     * 详见 tesk-0709 backtask.md Task 11 交付报告。
     */
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

    /** 该 groupKey 下版本列的数字 MAX（非数字版本值如 'V_DEFAULT'/'V1' 被正则忽略）；无数字版本返 null。 */
    private Integer maxNumericVersion(String table, String versionColumn, Map<String, Object> groupKey) {
        long t0 = System.nanoTime();
        Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + versionColumn + " ~ '^[0-9]+$' THEN "
                + versionColumn + "::int END) FROM " + table
                + " WHERE " + whereClause(groupKey));
        bindWhere(q, groupKey);
        Object max = q.getSingleResult();
        Profile p = PROFILE.get(); p.verN++; p.verNs += System.nanoTime() - t0;
        return (max == null) ? null : ((Number) max).intValue();
    }

    /** max(数字版本)+1；无数字版本则 "2000"。非数字版本值（'V_DEFAULT'/'V1' 等）被正则过滤。 */
    private String nextVersionOf(String table, String versionColumn, Map<String, Object> groupKey) {
        Integer max = maxNumericVersion(table, versionColumn, groupKey);
        return (max == null) ? "2000" : String.valueOf(max + 1);
    }

    /** 两个可空数字版本号的较大值（null 视为"无版本"）；两者均 null 返 null。用于"主/子一同升级"取两表 max。 */
    private static Integer maxNullable(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
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
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) ph.append(", ");
            ph.append(":v").append(i);
            if (UUID_ARRAY_COLUMNS.contains(cols.get(i))) ph.append("::uuid[]");
        }
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
                        if (UUID_ARRAY_COLUMNS.contains(cols.get(ci))) vals.append("::uuid[]");
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
