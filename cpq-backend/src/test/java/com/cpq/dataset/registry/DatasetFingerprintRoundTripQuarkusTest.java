package com.cpq.dataset.registry;

import com.cpq.dataset.fingerprint.DatasetFingerprints;
import com.cpq.dataset.fingerprint.RowFingerprints;
import com.cpq.dataset.versioning.AxisKey;
import com.cpq.dataset.versioning.VersionedGroupWriter;
import com.cpq.quotation.service.dsrecord.DsMainTableReader;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-47 真库端到端：<b>导入写的那一版，预览必须判 UNCHANGED</b>。
 *
 * <h3>为什么必须走真库而不是纯内存</h3>
 * D-47 的分叉发生在<b>「Excel 串写进去」与「JDBC 读回来」之间</b> ——
 * 纯内存用例得自己扮演 JDBC 的返回类型（那正是最容易假设错的一环）。
 * 本用例让真的 PG 列决定读回来的是什么，因此：
 * <ul>
 *   <li>写入走 {@link VersionedGroupWriter#writeGroup}（= 导入 Phase 2 的同一条路径）；</li>
 *   <li>读回走 {@link DsMainTableReader#readGroups}（= 回填预览取基底的同一条路径）；</li>
 *   <li>判定逐字复刻 {@code DsBackfillCollector} 的
 *       {@code sameMultiset(dbFps, DatasetFingerprints.computeAll(sheet, resultRows))}。</li>
 * </ul>
 *
 * <h3>🚫 零残留</h3>
 * 全部方法带 {@link TestTransaction} —— 写进共享 dev 库（{@code cpq_db_0724}）的夹具行
 * 在方法结束时<b>整事务回滚</b>，一行都不留。{@code VersionedGroupWriter} 取的是
 * <b>事务级</b> advisory lock，回滚即释放，不会挂住别的会话。
 * <p>🚫 本用例不做任何 DELETE / TRUNCATE / DDL（§3.2 红线）。
 *
 * <h3>双侧还原</h3>
 * <pre>
 *   QuoteRegistry: follow_material_price type=BOOLEAN（修复在） → UNCHANGED，绿
 *   改回 type=STRING（原缺陷）                                  → UPGRADED，红（原形复现）
 * </pre>
 */
@QuarkusTest
class DatasetFingerprintRoundTripQuarkusTest {

    /** 夹具轴值前缀 —— 全部在事务里回滚，不会出现在库里；万一出现即可按此前缀定位。 */
    private static final String AXIS = "T260907D47-FF01";
    private static final String CUST = "T260907D47C";

    @Inject QuoteRegistry quote;
    @Inject VersionedGroupWriter writer;
    @Inject DsMainTableReader reader;
    @Inject EntityManager em;

    private SheetDef sheet() {
        return quote.sheets().stream()
                .filter(s -> "INCOMING_FIXED_FEE".equals(s.sheetKey)).findFirst().orElseThrow();
    }

    // ── E2E-1：导入写一版 → 读回重算 → 必须 UNCHANGED ───────────────────────────
    @Test
    @TestTransaction
    @DisplayName("E2E-1 Excel「是」导入落库后读回重算，预览判定必须是 UNCHANGED（D-47 靶子）")
    void importedGroupPreviewsAsUnchanged() {
        SheetDef s = sheet();
        AxisKey axis = AxisKey.of(s, CUST, AXIS);

        // ── ① 导入侧：Excel 串（ParsedRow.asRowMap 给的就是这个形状）
        List<Map<String, Object>> excelRows = List.of(excelRow("是"), excelRow("否"));
        VersionedGroupWriter.Result created = writer.writeGroup(
                s, axis, excelRows, VersionedGroupWriter.SOURCE_IMPORT,
                VersionedGroupWriter.REASON_IMPORT_UPGRADE, "d47-test");
        assertEquals(VersionedGroupWriter.CREATED, created.result(), "夹具轴值应当是全新的组");
        assertEquals(1, created.versionNo());
        assertEquals(2, created.rowCount());

        // ── ② 读回侧：回填预览取基底的同一条路径
        Map<String, DsMainTableReader.BaseGroup> base = reader.readGroups(s, List.of(AXIS), CUST);
        DsMainTableReader.BaseGroup g = base.get(AXIS);
        assertNotNull(g, "读不回夹具组，后面的断言全部失去意义");

        // 🚨 非空守卫：真有行，且这一列真的非空、真的被 JDBC 读成 Boolean
        assertEquals(2, g.rows.size(), "0 行的话「判 UNCHANGED」恒真");
        List<Object> flags = new ArrayList<>();
        for (DsMainTableReader.BaseRow r : g.rows) {
            Object v = r.values.get("follow_material_price");
            assertNotNull(v, "夹具这一列必须非空 —— NULL 会让两侧都归一成空串，缺陷被掩盖");
            assertInstanceOf(Boolean.class, v, "PG boolean 列必须被 JDBC 读成 Boolean（D-47 的读回侧前提）");
            flags.add(v);
        }
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), flags, "「是」→true、「否」→false");

        // ── ③ 判定：逐字复刻 DsBackfillCollector 的 UNCHANGED 判据
        List<String> dbFps = new ArrayList<>();
        List<Map<String, Object>> resultRows = new ArrayList<>();
        for (DsMainTableReader.BaseRow r : g.rows) {      // 🚫 纯内存，无查询
            dbFps.add(r.rowFingerprint);
            resultRows.add(r.values);
        }
        List<String> newFps = DatasetFingerprints.computeAll(s, resultRows);
        System.out.println("[D47][E2E-1] 库中指纹  = " + trunc(dbFps));
        System.out.println("[D47][E2E-1] 重算指纹  = " + trunc(newFps));
        assertTrue(RowFingerprints.sameMultiset(dbFps, newFps),
                "值一个字节没变，重算指纹却与库中不同 ⇒ 预览会判 UPGRADED（D-47 原形）");

        // ── ④ 再走一次真正的写入判定（不是我自己重算的口径）
        VersionedGroupWriter.Result again = writer.writeGroup(
                s, axis, resultRows, VersionedGroupWriter.SOURCE_IMPORT,
                VersionedGroupWriter.REASON_IMPORT_UPGRADE, "d47-test");
        System.out.println("[D47][E2E-1] 二次写入判定 = " + again.result() + " v" + again.versionNo());
        assertEquals(VersionedGroupWriter.UNCHANGED, again.result(),
                "读回原值再写一次必须是 UNCHANGED（AC-14：version_no 不变、updated_at 不许动）");
        assertEquals(1, again.versionNo(), "UNCHANGED 不许升版");
    }

    // ── E2E-2：反向证据 —— 其余 12 张带版本表的往返判定一字未变 ──────────────────
    @Test
    @TestTransaction
    @DisplayName("E2E-2 报价侧全部带版本 sheet：导入写一版 → 读回 → 必须全部 UNCHANGED（改动边界）")
    void everyVersionedSheetRoundTripsUnchanged() {
        List<String> verdicts = new ArrayList<>();
        int sheets = 0;
        for (SheetDef s : quote.sheets()) {
            if (!s.versioned) continue;
            sheets++;
            String axisValue = AXIS + "-" + s.sheetKey;
            AxisKey axis = AxisKey.of(s, CUST, axisValue);
            Map<String, Object> row = syntheticRow(s, axisValue);

            VersionedGroupWriter.Result c = writer.writeGroup(
                    s, axis, List.of(row), VersionedGroupWriter.SOURCE_IMPORT,
                    VersionedGroupWriter.REASON_IMPORT_UPGRADE, "d47-test");
            assertEquals(VersionedGroupWriter.CREATED, c.result(), s.tableName + " 夹具组应为全新");

            Map<String, DsMainTableReader.BaseGroup> base = reader.readGroups(s, List.of(axisValue), CUST);
            DsMainTableReader.BaseGroup g = base.get(axisValue);
            assertNotNull(g, s.tableName + " 读不回夹具组");
            assertEquals(1, g.rows.size(), s.tableName + " 夹具行数");   // 非空守卫

            List<Map<String, Object>> back = new ArrayList<>();
            for (DsMainTableReader.BaseRow r : g.rows) back.add(r.values);
            VersionedGroupWriter.Result again = writer.writeGroup(
                    s, axis, back, VersionedGroupWriter.SOURCE_IMPORT,
                    VersionedGroupWriter.REASON_IMPORT_UPGRADE, "d47-test");
            verdicts.add(s.tableName + "=" + again.result() + "/v" + again.versionNo());
        }
        for (String v : verdicts) System.out.println("[D47][E2E-2] " + v);
        assertEquals(13, sheets, "报价侧带版本 sheet 数（变了说明 Registry 被动过）");
        List<String> bad = verdicts.stream().filter(v -> !v.endsWith("=UNCHANGED/v1")).toList();
        assertEquals(List.of(), bad, "以下表「写进去再读回来」判定不是 UNCHANGED —— 每一张都会空转升版");
    }

    // ── 存量盘点（只读）：本次改动会让多少存量行的指纹一次性失效 ────────────────────
    @Test
    @DisplayName("SCAN 存量盘点（只读）：ds_quote_incoming_fixed_fee 各组「库中指纹 vs 读回重算」是否一致")
    void scanExistingRowsFingerprintDrift() {
        SheetDef s = sheet();
        @SuppressWarnings("unchecked")
        List<Object[]> axes = em.createNativeQuery(
                "SELECT DISTINCT customer_no, material_no FROM " + s.tableName
                        + " ORDER BY customer_no, material_no").getResultList();
        System.out.println("[D47][SCAN] 采集时刻=" + java.time.Instant.now()
                + " 表=" + s.tableName + " 轴值组数=" + axes.size());
        int matched = 0, drifted = 0, rows = 0;
        for (Object[] a : axes) {          // 每组 1 条 SQL；轴值组数是常数级小量（实测 6）
            String cust = a[0] == null ? null : String.valueOf(a[0]);
            String axisValue = String.valueOf(a[1]);
            DsMainTableReader.BaseGroup g = reader.readGroups(s, List.of(axisValue), cust).get(axisValue);
            if (g == null || g.rows.isEmpty()) continue;
            List<String> dbFps = new ArrayList<>();
            List<Map<String, Object>> vals = new ArrayList<>();
            for (DsMainTableReader.BaseRow r : g.rows) { dbFps.add(r.rowFingerprint); vals.add(r.values); }
            rows += g.rows.size();
            boolean same = RowFingerprints.sameMultiset(dbFps, DatasetFingerprints.computeAll(s, vals));
            if (same) matched++; else drifted++;
            System.out.println("[D47][SCAN] cust=" + cust + " axis=" + axisValue
                    + " v" + g.versionNo + " rows=" + g.rows.size()
                    + " → " + (same ? "UNCHANGED" : "UPGRADED(指纹漂移)"));
        }
        System.out.println("[D47][SCAN] 汇总 组数=" + axes.size() + " 行数=" + rows
                + " 一致=" + matched + " 漂移=" + drifted);
        assertTrue(rows > 0, "表里 0 行的话本次盘点没有意义");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────
    /** 来料固定加工费一行，全部是 Excel 串（导入侧的真实形状）。 */
    private static Map<String, Object> excelRow(String flag) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("material_no", AXIS);
        row.put("item_seq", "是".equals(flag) ? "1" : "2");
        row.put("input_material_no", "T260907D47-RM01");
        row.put("base_value", "12.5");
        row.put("ratio_pct", "3.25");
        row.put("currency", "CNY");
        row.put("pricing_unit", "KG");
        row.put("follow_material_price", flag);
        row.put("material_increase_ratio", "1.5");
        row.put("material_increase_value", "0.75");
        row.put("increase_currency", "CNY");
        row.put("increase_unit", "KG");
        return row;
    }

    /**
     * 任意带版本 sheet 的一行合成夹具：轴列填轴值，其余按<b>物理类型</b>给一个合法的 Excel 串。
     * 🚫 不按列名硬编码 —— 加列/改列时本用例不该失效。
     */
    private static Map<String, Object> syntheticRow(SheetDef s, String axisValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnDef c : s.persistedColumns()) {
            if (c.name.equals(s.axisColumn)) { row.put(c.name, axisValue); continue; }
            String pg = c.pgType == null ? "" : c.pgType.toLowerCase();
            if (pg.startsWith("boolean")) row.put(c.name, "是");
            else if (pg.startsWith("numeric")) row.put(c.name, "1.25");
            else if (pg.startsWith("integer") || pg.startsWith("bigint") || pg.startsWith("smallint")) row.put(c.name, "1");
            else if (c.dropdown != null && c.dropdown.options != null && !c.dropdown.options.isEmpty()
                     && c.enforceEnum) row.put(c.name, c.dropdown.options.get(0));   // 硬枚举必须落域内
            else row.put(c.name, "T260907D47");
        }
        return row;
    }

    private static List<String> trunc(List<String> fps) {
        List<String> out = new ArrayList<>(fps.size());
        for (String f : fps) out.add(f == null ? "null" : f.substring(0, 12));
        return out;
    }
}
