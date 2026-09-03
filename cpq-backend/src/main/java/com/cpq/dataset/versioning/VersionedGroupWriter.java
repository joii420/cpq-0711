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
 * 两套实现必然漂移（{@code PricingSheetRegistry} 类注释自陈的「双写漂移」就是前车之鉴）。
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
    public record Result(String axisValue, String result, int versionNo, int rowCount) {}

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
    public Result writeGroup(SheetDef sheet, String axisValue, List<Map<String, Object>> rows,
                             String source, String archiveReason, String operator) {
        Map<String, List<Map<String, Object>>> one = new LinkedHashMap<>();
        one.put(axisValue, rows == null ? List.of() : rows);
        return writeGroups(sheet, one, source, archiveReason, operator).get(axisValue);
    }

    /**
     * 批量写入整个 sheet 的多个轴值组 —— <b>导入 Phase 2（B-7）的入口</b>。SQL 条数与轴值数无关。
     *
     * @param rowsByAxis 轴值 → 该轴值的整组全量行
     * @return 轴值 → 结果（顺序与入参一致）
     */
    public Map<String, Result> writeGroups(SheetDef sheet,
                                           Map<String, List<Map<String, Object>>> rowsByAxis,
                                           String source, String archiveReason, String operator) {
        Map<String, Result> results = new LinkedHashMap<>();
        if (rowsByAxis == null || rowsByAxis.isEmpty()) return results;
        if (!sheet.versioned) {
            throw new IllegalArgumentException("免版本表不得走版本化写入器: " + sheet.tableName);
        }
        String table = SqlIdent.of(sheet.tableName);
        String axisCol = SqlIdent.of(sheet.axisColumn);
        List<String> axes = new ArrayList<>(rowsByAxis.keySet());

        // ── ① 并发串行化：表级 advisory lock（事务级，提交/回滚自动释放；同事务内可重入）。
        //    key 与 DatasetGroupLock 逐字一致 —— 维护端保存先取同一把锁再读版本，才能让 AC-41 的乐观锁真正生效。
        DatasetGroupLock.acquire(em, table);

        // ── ② 一次读全部相关轴值的现状（1 条 SQL，与轴值数无关）
        Map<String, List<String>> dbFingerprints = new LinkedHashMap<>();
        Map<String, Integer> dbVersions = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> cur = em.createNativeQuery(
                        "SELECT " + axisCol + ", version_no, row_fingerprint FROM " + table
                                + " WHERE " + axisCol + " IN (:axes)")
                .setParameter("axes", axes)
                .getResultList();
        for (Object[] r : cur) {
            String axis = str(r[0]);
            dbFingerprints.computeIfAbsent(axis, k -> new ArrayList<>()).add(str(r[2]));
            dbVersions.merge(axis, ((Number) r[1]).intValue(), Math::max);
        }

        // ── ③ 纯内存判定（🚫 循环体内无任何查询：N+1 自检点）
        List<FpColumn> fpCols = DatasetFingerprints.columnsOf(sheet);   // 列定义只解析一次
        Map<String, List<Map<String, Object>>> toInsert = new LinkedHashMap<>();
        Map<String, List<String>> newFingerprints = new LinkedHashMap<>();
        Set<String> toCreate = new LinkedHashSet<>();
        Set<String> toUpgrade = new LinkedHashSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : rowsByAxis.entrySet()) {
            String axis = e.getKey();
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
        Map<String, Integer> newVersions = new HashMap<>();
        for (String axis : toCreate) newVersions.put(axis, 1);
        if (!toUpgrade.isEmpty()) {
            List<String> upAxes = new ArrayList<>(toUpgrade);
            @SuppressWarnings("unchecked")
            List<Object[]> hist = em.createNativeQuery(
                            "SELECT " + axisCol + ", max(version_no) FROM " + sheet.historyTable()
                                    + " WHERE " + axisCol + " IN (:axes) GROUP BY " + axisCol)
                    .setParameter("axes", upAxes)
                    .getResultList();
            Map<String, Integer> histMax = new HashMap<>();
            for (Object[] r : hist) histMax.put(str(r[0]), ((Number) r[1]).intValue());
            for (String axis : upAxes) {
                // ⚠️ max(历史最大, 当前) + 1，不是「当前 + 1」
                int base = Math.max(dbVersions.getOrDefault(axis, 0), histMax.getOrDefault(axis, 0));
                newVersions.put(axis, base + 1);
            }
            archive(sheet, table, axisCol, upAxes, archiveReason, operator);
            em.createNativeQuery("DELETE FROM " + table + " WHERE " + axisCol + " IN (:axes)")
                    .setParameter("axes", upAxes)
                    .executeUpdate();
        }

        // ── ⑤ 插入（多行 VALUES 合批；条数 = ceil(总行数/500)，与轴值数无关）
        insertAll(sheet, table, toInsert, newVersions, newFingerprints, source, operator);

        for (String axis : toCreate) {
            results.put(axis, new Result(axis, CREATED, 1, toInsert.getOrDefault(axis, List.of()).size()));
        }
        for (String axis : toUpgrade) {
            results.put(axis, new Result(axis, UPGRADED, newVersions.get(axis),
                    toInsert.getOrDefault(axis, List.of()).size()));
        }
        Map<String, Result> ordered = new LinkedHashMap<>();        // 保持入参顺序
        for (String axis : axes) if (results.containsKey(axis)) ordered.put(axis, results.get(axis));
        return ordered;
    }

    /** 该轴值当前版本号；0 表示该轴值在本 sheet 中<b>从未有过数据</b>（api.md §4 的 versionNo=null）。 */
    public int currentVersion(SheetDef sheet, String axisValue) {
        Object v = em.createNativeQuery(
                        "SELECT coalesce(max(version_no), 0) FROM " + SqlIdent.of(sheet.tableName)
                                + " WHERE " + SqlIdent.of(sheet.axisColumn) + " = :a")
                .setParameter("a", axisValue)
                .getSingleResult();
        return v == null ? 0 : ((Number) v).intValue();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 归档：整行复制进 {@code _history}（主表 {@code id} → {@code origin_id}），1 条 INSERT…SELECT。 */
    private void archive(SheetDef sheet, String table, String axisCol, List<String> axes,
                         String archiveReason, String operator) {
        List<String> cols = new ArrayList<>();
        for (ColumnDef c : sheet.persistedColumns()) cols.add(SqlIdent.of(c.name));
        cols.addAll(ARCHIVE_SYS_COLUMNS);
        String colList = String.join(", ", cols);
        em.createNativeQuery("INSERT INTO " + sheet.historyTable()
                        + " (origin_id, " + colList + ", archived_by, archive_reason)"
                        + " SELECT id, " + colList + ", :by, :reason FROM " + table
                        + " WHERE " + axisCol + " IN (:axes)")
                .setParameter("by", operator)
                .setParameter("reason", archiveReason)
                .setParameter("axes", axes)
                .executeUpdate();
    }

    /** 多行 VALUES 合批插入。分批只按<b>总行数</b>切，不按轴值切。 */
    private void insertAll(SheetDef sheet, String table,
                           Map<String, List<Map<String, Object>>> toInsert,
                           Map<String, Integer> newVersions,
                           Map<String, List<String>> fingerprints,
                           String source, String operator) {
        if (toInsert.isEmpty()) return;
        List<ColumnDef> dbCols = sheet.persistedColumns();
        List<String> allCols = new ArrayList<>();
        for (ColumnDef c : dbCols) allCols.add(SqlIdent.of(c.name));
        allCols.addAll(INSERT_SYS_COLUMNS);

        // 展平（🚫 循环体内无查询）
        List<Object[]> flat = new ArrayList<>();       // [rowMap, versionNo, fingerprint]
        for (Map.Entry<String, List<Map<String, Object>>> e : toInsert.entrySet()) {
            int ver = newVersions.getOrDefault(e.getKey(), 1);
            List<String> fps = fingerprints.get(e.getKey());
            List<Map<String, Object>> rows = e.getValue();
            for (int i = 0; i < rows.size(); i++) flat.add(new Object[]{rows.get(i), ver, fps.get(i)});
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
                q.setParameter("p" + i + "_" + c, operator);              // updated_by
            }
            q.executeUpdate();
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
