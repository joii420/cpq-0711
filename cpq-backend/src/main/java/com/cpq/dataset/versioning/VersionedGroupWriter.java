package com.cpq.dataset.versioning;

import com.cpq.dataset.fingerprint.DatasetFingerprints;
import com.cpq.dataset.fingerprint.FpColumn;
import com.cpq.dataset.fingerprint.RowFingerprints;
import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.DatasetGroupLock;
import com.cpq.dataset.support.DatasetValues;
import com.cpq.dataset.support.SqlIdent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用版本化写入器（task-260902 · B-5 · 需求文档 R-4/R-5/R-6）。
 *
 * <p><b>三套数据集、39 张带版本表、导入（B-7）与维护端保存（B-10）共用这一个实现。</b>
 * 两条写入路径的升版语义因此天然一致 —— 🚫 严禁任何调用方自己算指纹 / 自己归档 / 自己定版本号，
 * 两套实现必然漂移（历史上核价维护端的声明式镜像与导入器 handler 双写、静默漂移，就是前车之鉴；
 * 该维护端实现已于 2026-09-07 随 task-260907 移除）。
 *
 * <h3>判定（R-4，以「表 × 轴值」为单位）</h3>
 * <pre>
 * 轴值在库中不存在                      → CREATED  ：整组 version_no = 1 插入
 * |S_db| != |S_x|                       → UPGRADED
 * 指纹多重集(S_db) != 指纹多重集(S_x)    → UPGRADED （多重集：不看行序，但计重复次数，AC-16）
 * 否则                                   → UNCHANGED：一行不写（连 updated_at 都不许动，AC-13）
 * </pre>
 *
 * <h3>UPGRADED 的三步（同一事务内按序）</h3>
 * <ol>
 *   <li>{@code INSERT INTO t_history (...) SELECT ... FROM t WHERE 轴 IN (...)} —— 整行归档，
 *       含原 {@code version_no} / {@code row_fingerprint}（AC-14）</li>
 *   <li>{@code DELETE FROM t WHERE 轴 IN (...)}</li>
 *   <li>以 {@code max(历史最大版本号, 当前版本号) + 1} 插入新行</li>
 * </ol>
 * ⚠️ 第 3 步取 <b>max 而非「当前 + 1」</b>：{@code _history} 里可能已有更大的号
 * （{@code RECORD.md}「BOM 主子表版本失步致导入撞 uq」的教训）。AC-20 的三次导入序列专门验这条。
 *
 * <h3>🚫 N+1 硬指标（B-12 / AC-44）</h3>
 * {@link #writeGroups} 是<b>整 sheet 一次处理全部轴值</b>的批量入口，SQL 条数：
 * 锁 1 + 读现状 1 + 历史最大版本 1 + 归档 1 + 删除 1 + 插入 ceil(总行数/500)，<b>与轴值（料号）数无关</b>。
 * {@link #writeGroup} 只是 size=1 的特例（维护端保存一次只动一个料号）。
 * 🚫 <b>严禁在调用方的 for 循环里逐轴值调 {@code writeGroup}</b> —— 那正是本项目反复踩过的 N+1 形态。
 *
 * <h3>增量语义（R-6 / AC-19）</h3>
 * 只碰入参里出现的轴值。库里有、本次没出现的轴值<b>一行不动</b>：不升版、不归档、不删除。
 */
@ApplicationScoped
public class VersionedGroupWriter {

    // ── 写入来源 / 归档原因（R-5）。字符串而非枚举：与维护端 B-10 的调用形态对齐。──
    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String REASON_IMPORT_UPGRADE = "IMPORT_UPGRADE";
    public static final String REASON_MANUAL_UPGRADE = "MANUAL_UPGRADE";

    // ── 判定结果三态（api.md §7 的 result）──
    public static final String CREATED = "CREATED";
    public static final String UPGRADED = "UPGRADED";
    public static final String UNCHANGED = "UNCHANGED";

    /** 多行 INSERT 的分批行数。PG 单语句参数上限 65535；最宽表约 30 列 × 500 行 = 1.5 万参数，留足余量。 */
    private static final int INSERT_CHUNK = 500;

    /** 主表系统列（{@code id} / {@code created_at} 由 DB 默认值负责，不在这里显式写）。 */
    private static final List<String> INSERT_SYS_COLUMNS =
            List.of("version_no", "row_fingerprint", "source", "created_by", "updated_at", "updated_by");

    /** 归档时逐列复制的系统列（{@code created_at} 也要原样带走，历史行必须能还原当时的状态）。 */
    private static final List<String> ARCHIVE_SYS_COLUMNS =
            List.of("version_no", "row_fingerprint", "source", "created_at", "created_by",
                    "updated_at", "updated_by");

    @Inject
    EntityManager em;

    /**
     * 单个（表, 轴值）组的写入结果。
     *
     * @param axisValue 轴值
     * @param result    {@link #CREATED} / {@link #UPGRADED} / {@link #UNCHANGED}
     * @param versionNo 写入后该组的<b>当前</b>版本号（UNCHANGED 时为原版本号）
     * @param rowCount  写入后该组的当前行数
     */
    public record Result(AxisKey axis, String result, int versionNo, int rowCount) {
        /** 原轴值（料号）。客户号见 {@link #axis()}。 */
        public String axisValue() { return axis == null ? null : axis.axisValue(); }
    }

    // ==================================================================
    // 公开 API
    // ==================================================================

    /**
     * 单组写入 —— <b>维护端保存（B-10）的入口</b>。
     *
     * @param sheet         目标 sheet（带版本；免版本表请走 {@link PlainTableWriter}）
     * @param axisValue     轴值（料号）
     * @param rows          该轴值的<b>整组全量</b>目标行，key = DB 列名。
     *                      调用方无需填 {@code version_no} / {@code row_fingerprint}（本类负责）
     * @param source        {@link #SOURCE_IMPORT} / {@link #SOURCE_MANUAL}
     * @param archiveReason {@link #REASON_IMPORT_UPGRADE} / {@link #REASON_MANUAL_UPGRADE}
     * @param operator      操作人（写 {@code created_by} / {@code updated_by} / {@code archived_by}），可为 null
     */
    public Result writeGroup(SheetDef sheet, AxisKey axis, List<Map<String, Object>> rows,
                             String source, String archiveReason, String operator) {
        Map<AxisKey, List<Map<String, Object>>> one = new LinkedHashMap<>();
        one.put(axis, rows == null ? List.of() : rows);
        return writeGroups(sheet, one, source, archiveReason, operator).get(axis);
    }

    /**
     * 批量写入整个 sheet 的多个轴值组 —— <b>导入 Phase 2（B-7）的入口</b>。SQL 条数与轴值数无关。
     *
     * @param rowsByAxis 轴值 → 该轴值的整组全量行
     * @return 轴值 → 结果（顺序与入参一致）
     */
    public Map<AxisKey, Result> writeGroups(SheetDef sheet,
                                           Map<AxisKey, List<Map<String, Object>>> rowsByAxis,
                                           String source, String archiveReason, String operator) {
        Map<AxisKey, Result> results = new LinkedHashMap<>();
        if (rowsByAxis == null || rowsByAxis.isEmpty()) return results;
        if (!sheet.versioned) {
            throw new IllegalArgumentException("免版本表不得走版本化写入器: " + sheet.tableName);
        }
        String table = SqlIdent.of(sheet.tableName);
        String axisCol = SqlIdent.of(sheet.axisColumn);
        List<AxisKey> axes = new ArrayList<>(rowsByAxis.keySet());
        // task-260907 · AC-4：轴键与本表的客户维度必须对齐。
        // 🚫 客户号为空绝不允许静默写 NULL —— 那正是「隔离看起来生效、实际每次导入都在删别人数据」的形态。
        for (AxisKey k : axes) requireAxisMatchesSheet(sheet, k);

        // ── ① 并发串行化：表级 advisory lock（事务级，提交/回滚自动释放；同事务内可重入）。
        //    key 与 DatasetGroupLock 逐字一致 —— 维护端保存先取同一把锁再读版本，才能让 AC-41 的乐观锁真正生效。
        DatasetGroupLock.acquire(em, table);

        // ── ② 一次读全部相关轴值的现状（1 条 SQL，与轴值数无关）
        //    task-260907 · B-3：谓词按【复合轴】拼；单列时逐字退化成原来的 "axisCol IN (:axes)"，
        //    核价两套的 SQL 与改动前一模一样（AC-6）。
        AxisPredicate pred = axisPredicate(sheet, axes, "a");
        String axisSelect = String.join(", ", sqlIdents(sheet.axisColumns()));
        int axisArity = sheet.axisColumns().size();
        Map<AxisKey, List<String>> dbFingerprints = new LinkedHashMap<>();
        Map<AxisKey, Integer> dbVersions = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> cur = pred.bind(em.createNativeQuery(
                        "SELECT " + axisSelect + ", version_no, row_fingerprint FROM " + table
                                + " WHERE " + pred.sql))
                .getResultList();
        for (Object[] r : cur) {
            AxisKey axis = readAxis(sheet, r, axisArity);
            dbFingerprints.computeIfAbsent(axis, k -> new ArrayList<>()).add(str(r[axisArity + 1]));
            dbVersions.merge(axis, ((Number) r[axisArity]).intValue(), Math::max);
        }

        // ── ③ 纯内存判定（🚫 循环体内无任何查询：N+1 自检点）
        List<FpColumn> fpCols = DatasetFingerprints.columnsOf(sheet);   // 列定义只解析一次
        Map<AxisKey, List<Map<String, Object>>> toInsert = new LinkedHashMap<>();
        Map<AxisKey, List<String>> newFingerprints = new LinkedHashMap<>();
        Set<AxisKey> toCreate = new LinkedHashSet<>();
        Set<AxisKey> toUpgrade = new LinkedHashSet<>();
        for (Map.Entry<AxisKey, List<Map<String, Object>>> e : rowsByAxis.entrySet()) {
            AxisKey axis = e.getKey();
            List<Map<String, Object>> rows = e.getValue() == null ? List.of() : e.getValue();
            List<String> fps = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) fps.add(RowFingerprints.compute(fpCols, row));  // 纯内存 SHA-256
            newFingerprints.put(axis, fps);

            List<String> dbFps = dbFingerprints.get(axis);
            int curVer = dbVersions.getOrDefault(axis, 0);
            if (dbFps == null) {
                if (rows.isEmpty()) {                        // 库里没有、本次也没有 → 无事可做
                    results.put(axis, new Result(axis, UNCHANGED, 0, 0));
                    continue;
                }
                toCreate.add(axis);
                toInsert.put(axis, rows);
            } else if (RowFingerprints.sameMultiset(dbFps, fps)) {
                // sameMultiset 内部先比 size，行数不同直接 false —— R-4 的「先比行数」已含在内
                results.put(axis, new Result(axis, UNCHANGED, curVer, dbFps.size()));
            } else {
                toUpgrade.add(axis);
                if (!rows.isEmpty()) toInsert.put(axis, rows);
            }
        }

        // ── ④ 归档 + 删除（各 1 条 SQL，一次覆盖全部待升版轴值）
        Map<AxisKey, Integer> newVersions = new HashMap<>();
        for (AxisKey axis : toCreate) newVersions.put(axis, 1);
        if (!toUpgrade.isEmpty()) {
            List<AxisKey> upAxes = new ArrayList<>(toUpgrade);
            AxisPredicate upPred = axisPredicate(sheet, upAxes, "u");
            @SuppressWarnings("unchecked")
            List<Object[]> hist = upPred.bind(em.createNativeQuery(
                            "SELECT " + axisSelect + ", max(version_no) FROM " + sheet.historyTable()
                                    + " WHERE " + upPred.sql + " GROUP BY " + axisSelect))
                    .getResultList();
            Map<AxisKey, Integer> histMax = new HashMap<>();
            for (Object[] r : hist) histMax.put(readAxis(sheet, r, axisArity), ((Number) r[axisArity]).intValue());
            for (AxisKey axis : upAxes) {
                // ⚠️ max(历史最大, 当前) + 1，不是「当前 + 1」
                int base = Math.max(dbVersions.getOrDefault(axis, 0), histMax.getOrDefault(axis, 0));
                newVersions.put(axis, base + 1);
            }
            archive(sheet, table, upPred, archiveReason, operator);
            // 🚨 task-260907 · B-3 本任务最要紧的一行：删除必须按【复合轴】。
            //    少一维 = 客户 A 导入料号 X 会把客户 B 的料号 X 整组删掉，不报错、不撞键、不留痕
            //    （13 张带版本表只有 PRIMARY KEY(id)，没有任何业务唯一索引兜底）。
            upPred.bind(em.createNativeQuery("DELETE FROM " + table + " WHERE " + upPred.sql))
                    .executeUpdate();
        }

        // ── ⑤ 插入（多行 VALUES 合批；条数 = ceil(总行数/500)，与轴值数无关）
        insertAll(sheet, table, toInsert, newVersions, newFingerprints, source, operator);

        for (AxisKey axis : toCreate) {
            results.put(axis, new Result(axis, CREATED, 1, toInsert.getOrDefault(axis, List.of()).size()));
        }
        for (AxisKey axis : toUpgrade) {
            results.put(axis, new Result(axis, UPGRADED, newVersions.get(axis),
                    toInsert.getOrDefault(axis, List.of()).size()));
        }
        Map<AxisKey, Result> ordered = new LinkedHashMap<>();        // 保持入参顺序
        for (AxisKey axis : axes) if (results.containsKey(axis)) ordered.put(axis, results.get(axis));
        return ordered;
    }

    /**
     * 该<b>复合轴</b>当前版本号；0 表示从未有过数据（api.md §4 的 versionNo=null）。
     * <p>写入路径（维护端保存的乐观锁）必须走这个重载 —— 它与 {@link #writeGroups} 的删除口径同源。
     */
    public int currentVersion(SheetDef sheet, AxisKey axis) {
        requireAxisMatchesSheet(sheet, axis);
        AxisPredicate pred = axisPredicate(sheet, List.of(axis), "v");
        Object v = pred.bind(em.createNativeQuery(
                        "SELECT coalesce(max(version_no), 0) FROM " + SqlIdent.of(sheet.tableName)
                                + " WHERE " + pred.sql))
                .getSingleResult();
        return v == null ? 0 : ((Number) v).intValue();
    }

    /**
     * 该轴值<b>跨全部客户</b>的当前版本号 —— 只给还没有客户上下文的<b>读</b>路径用
     * （维护端 {@code GET rows} / {@code GET versions}，客户选择器由
     * {@code task-260907-产品管理客户过滤} 承接）。
     *
     * <p>🚫 <b>写路径不许用它</b>：它跨客户取 max，与整组删除的口径不一致，
     * 拿它做乐观锁比对会在同料号跨客户时判错。写路径一律走 {@link #currentVersion(SheetDef, AxisKey)}。
     *
     * <p>SQL 与 task-260907 之前逐字相同 ⇒ 核价两套与报价读端的既有行为零变化。
     */
    public int currentVersionAnyCustomer(SheetDef sheet, String axisValue) {
        Object v = em.createNativeQuery(
                        "SELECT coalesce(max(version_no), 0) FROM " + SqlIdent.of(sheet.tableName)
                                + " WHERE " + SqlIdent.of(sheet.axisColumn) + " = :a")
                .setParameter("a", axisValue)
                .getSingleResult();
        return v == null ? 0 : ((Number) v).intValue();
    }

    // ==================================================================
    // 复合轴谓词（task-260907 · B-2 / B-3）
    // ==================================================================

    /**
     * 轴谓词 + 它的绑定参数。
     *
     * <p>单列轴退化成 {@code axis_col IN (:a_axes)}，与改动前<b>同形</b>（核价两套零行为变化，AC-6）；
     * 复合轴用 PG 的行构造器 {@code (customer_no, axis_col) IN ((:a0_0,:a0_1), ...)}，
     * 一条语句覆盖全部轴值 —— <b>SQL 条数仍与轴值数无关</b>（backend.md N+1 硬指标）。
     */
    private static final class AxisPredicate {
        final String sql;
        final Map<String, Object> params;
        AxisPredicate(String sql, Map<String, Object> params) { this.sql = sql; this.params = params; }
        Query bind(Query q) {
            for (Map.Entry<String, Object> e : params.entrySet()) q.setParameter(e.getKey(), e.getValue());
            return q;
        }
    }

    /** @param tag 参数名前缀 —— 同一条语句里可能同时出现多个谓词，前缀防撞名。 */
    private static AxisPredicate axisPredicate(SheetDef sheet, List<AxisKey> axes, String tag) {
        List<String> cols = sqlIdents(sheet.axisColumns());
        Map<String, Object> params = new LinkedHashMap<>();
        if (cols.size() == 1) {
            List<String> vals = new ArrayList<>(axes.size());
            for (AxisKey k : axes) vals.add(k.axisValue());
            params.put(tag + "_axes", vals);
            return new AxisPredicate(cols.get(0) + " IN (:" + tag + "_axes)", params);
        }
        StringBuilder sb = new StringBuilder("(").append(String.join(", ", cols)).append(") IN (");
        for (int i = 0; i < axes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(:").append(tag).append(i).append("_c, :").append(tag).append(i).append("_a)");
            params.put(tag + i + "_c", axes.get(i).customerNo());
            params.put(tag + i + "_a", axes.get(i).axisValue());
        }
        sb.append(')');
        return new AxisPredicate(sb.toString(), params);
    }

    private static List<String> sqlIdents(List<String> cols) {
        List<String> out = new ArrayList<>(cols.size());
        for (String c : cols) out.add(SqlIdent.of(c));
        return out;
    }

    /** 从结果行前 {@code arity} 列读回轴键（列顺序 = {@link SheetDef#axisColumns()}）。 */
    private static AxisKey readAxis(SheetDef sheet, Object[] row, int arity) {
        return arity == 1 ? new AxisKey(null, str(row[0])) : new AxisKey(str(row[0]), str(row[1]));
    }

    /**
     * AC-4：轴键与本表的客户维度必须对齐，对不上<b>立刻抛</b>。
     *
     * <p>🚫 报价侧客户号为空时<b>绝不允许静默写 NULL</b> —— 那正是「列建了、值恒 NULL」的失败形态：
     * 自检过、导入不报错，隔离却完全没生效。
     */
    private static void requireAxisMatchesSheet(SheetDef sheet, AxisKey axis) {
        if (axis == null) throw new IllegalArgumentException("轴键不能为 null: " + sheet.tableName);
        if (axis.axisValue() == null || axis.axisValue().isBlank()) {
            throw new IllegalArgumentException("轴值不能为空: " + sheet.tableName);
        }
        if (sheet.customerScoped()) {
            if (!axis.scoped()) {
                throw new IllegalArgumentException(
                        "报价侧写入必须提供客户编号（customer_no），本次为空: " + sheet.tableName
                                + " 轴值=" + axis.axisValue());
            }
        } else if (axis.customerNo() != null) {
            throw new IllegalArgumentException(
                    "本表无客户维度，不得传客户编号: " + sheet.tableName + " customerNo=" + axis.customerNo());
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 归档：整行复制进 {@code _history}（主表 {@code id} → {@code origin_id}），1 条 INSERT…SELECT。 */
    private void archive(SheetDef sheet, String table, AxisPredicate pred,
                         String archiveReason, String operator) {
        List<String> cols = new ArrayList<>();
        for (ColumnDef c : sheet.persistedColumns()) cols.add(SqlIdent.of(c.name));
        cols.addAll(ARCHIVE_SYS_COLUMNS);
        // 🚨 task-260907 · B-1 ④：customer_no 是静态系统列、不在 persistedColumns 里，
        //    归档时必须显式复制。漏了它 = _history 那一列恒 NULL，而这条不会报任何错。
        if (sheet.customerScoped()) cols.addAll(SheetDef.CUSTOMER_COLUMNS);
        String colList = String.join(", ", cols);
        pred.bind(em.createNativeQuery("INSERT INTO " + sheet.historyTable()
                        + " (origin_id, " + colList + ", archived_by, archive_reason)"
                        + " SELECT id, " + colList + ", :by, :reason FROM " + table
                        + " WHERE " + pred.sql))
                .setParameter("by", operator)
                .setParameter("reason", archiveReason)
                .executeUpdate();
    }

    /** 多行 VALUES 合批插入。分批只按<b>总行数</b>切，不按轴值切。 */
    private void insertAll(SheetDef sheet, String table,
                           Map<AxisKey, List<Map<String, Object>>> toInsert,
                           Map<AxisKey, Integer> newVersions,
                           Map<AxisKey, List<String>> fingerprints,
                           String source, String operator) {
        if (toInsert.isEmpty()) return;
        boolean scoped = sheet.customerScoped();
        List<ColumnDef> dbCols = sheet.persistedColumns();
        List<String> allCols = new ArrayList<>();
        for (ColumnDef c : dbCols) allCols.add(SqlIdent.of(c.name));
        allCols.addAll(INSERT_SYS_COLUMNS);
        // 🚨 task-260907 · B-1 ④：customer_no 是静态系统列、不在 persistedColumns 里，
        //    插入时必须显式带上。漏了它 = 列建好了、自检过了、导入也不报错，【值永远 NULL】——
        //    届时「NULL 行数 = 0」的验收会红，但排查方向极易跑偏到 DDL 上（「列不是加了吗？」）。
        if (scoped) allCols.addAll(SheetDef.CUSTOMER_COLUMNS);

        // 展平（🚫 循环体内无查询）
        List<Object[]> flat = new ArrayList<>();       // [rowMap, versionNo, fingerprint, customerNo]
        for (Map.Entry<AxisKey, List<Map<String, Object>>> e : toInsert.entrySet()) {
            AxisKey axis = e.getKey();
            int ver = newVersions.getOrDefault(axis, 1);
            List<String> fps = fingerprints.get(axis);
            List<Map<String, Object>> rows = e.getValue();
            for (int i = 0; i < rows.size(); i++) {
                flat.add(new Object[]{rows.get(i), ver, fps.get(i), axis.customerNo()});
            }
        }

        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        for (int start = 0; start < flat.size(); start += INSERT_CHUNK) {
            int end = Math.min(start + INSERT_CHUNK, flat.size());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                    .append(" (").append(String.join(", ", allCols)).append(") VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(", ");
                sql.append('(');
                for (int c = 0; c < allCols.size(); c++) {
                    if (c > 0) sql.append(", ");
                    sql.append(":p").append(i).append('_').append(c);
                }
                sql.append(')');
            }
            Query q = em.createNativeQuery(sql.toString());
            for (int i = start; i < end; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) flat.get(i)[0];
                int c = 0;
                for (ColumnDef col : dbCols) {
                    q.setParameter("p" + i + "_" + c, DatasetValues.coerce(col, row.get(col.name)));
                    c++;
                }
                q.setParameter("p" + i + "_" + c++, flat.get(i)[1]);      // version_no
                q.setParameter("p" + i + "_" + c++, flat.get(i)[2]);      // row_fingerprint
                q.setParameter("p" + i + "_" + c++, source);              // source
                q.setParameter("p" + i + "_" + c++, operator);            // created_by
                q.setParameter("p" + i + "_" + c++, now);                 // updated_at
                q.setParameter("p" + i + "_" + c++, operator);            // updated_by
                if (scoped) q.setParameter("p" + i + "_" + c, flat.get(i)[3]);   // customer_no
            }
            q.executeUpdate();
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
