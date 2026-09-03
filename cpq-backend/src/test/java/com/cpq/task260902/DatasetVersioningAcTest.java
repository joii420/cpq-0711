package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · C 组 升版</b> —— 覆盖 AC-12 ~ AC-20（R-3 指纹 / R-4 升版判定 / R-6 增量语义）。
 *
 * <h3>为什么 AC-12~AC-18 写在同一个方法里</h3>
 * AC-13 原文「<b>紧接 AC-12 再导入同一份文件</b>」、AC-15「<b>把 …… 删掉一行后导入</b>」——
 * 它们是一条<b>序列</b>：版本号 1 → 1 → 2 → 3 → 3 → 3 → 3。
 * 拆成独立方法就变成 7 个各自从零开始的单点，序列里最容易翻车的「中间态」根本不会被走到
 * （{@code testing.md §3.2}：只写单点 = 只覆盖 happy path）。
 *
 * <h3>夹具为什么这么挑（{@code test.md §4} 的反面教材）</h3>
 * {@code RECORD.md} 记过一次「删中间那条时 {@code max(ACTIVE)} 恰等于 {@code max(全部)}，破坏发号器也不变红」。
 * ⇒ 本序列刻意让三个量<b>互不相等</b>：轴 {@code TEST-DS-3120014539} 在物料BOM 下有 <b>8</b> 行、
 * 版本号走到 <b>3</b>、删行后是 <b>7</b> 行。任何一个数字被另一个数字冒充都会被抓到。
 */
@QuarkusTest
@DisplayName("task-260902 · C 组 升版（AC-12~AC-20）")
class DatasetVersioningAcTest extends DatasetAcTestBase {

    private static final String T = "ds_cost_basic_material_bom";
    private static final String H = "ds_cost_basic_material_bom_history";
    private static final String SHEET = "物料BOM";
    /** AC-14 / AC-17 锚定的那一行的组成料号（模板第 3 行）。 */
    private static final String ANCHOR_COMPONENT = "S-2120011658";

    // ═══════════════════════════════════════════════════════════════
    // AC-12 ~ AC-18 一条序列
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TV-01~07 / AC-12~AC-18：首次 → 重导 → 改比对项 → 删行 → 换序 → 改项次 → 补零，版本 1→1→2→3→3→3→3")
    void tv01_to_07_versionSequence() {

        // ── TV-01 / AC-12：空库首次导入 → version_no 全 1，_history 0 行 ──
        Response r1 = importCostBasic(null, "tv01-first.xlsx");
        assertStatus(r1, 200, "AC-12");

        Set<Integer> v = versionsOfAxis();
        assertFalse(v.isEmpty(), "AC-12：导入后 " + T + " 里 " + AXIS_BASIC + " 一行都没有 ⇒ 空验证");
        assertEquals(Set.of(1), v, "AC-12：首次导入的 version_no 必须全为 1，实际 " + v);

        long baseRows = countRows(T, axis());
        assertEquals(8L, baseRows,
                "AC-12：模板里 " + AXIS_BASIC + " 在 " + SHEET + " 下应有 8 行（r3~r10），实际 " + baseRows
                        + " ⇒ 夹具与模板不符，后续「删一行 = 7 行」的判据会失效");

        assertEquals(0L, countRows(H, axis()),
                "AC-12：首次导入不该产生任何历史行");
        System.out.printf("[TV-01] v=%s rows=%d history=0%n", v, baseRows);

        // ── TV-02 / AC-13：紧接着重导同一份文件 → UNCHANGED，版本不变，_history 仍 0 ──
        Response r2 = importCostBasic(null, "tv02-same.xlsx");
        assertStatus(r2, 200, "AC-13");
        // 🚩 AC-13 原文写「UNCHANGED 数 = 1」，那是模板只有 1 个轴值时的数字；
        //    2026-09-03 04:22 的模板物料BOM 有 4 个轴值 ⇒ 字面值 1 已过期。
        //    这里断言 AC-13 真正要表达的**不变量**：一字未改的重导，**没有任何轴值升版或新建**，
        //    且 UNCHANGED 数等于本 sheet 的轴值总数。比写死数字更强，也不随模板换版而假红。
        assertSummary(r2, SHEET, "upgraded", 0, "AC-13");
        assertSummary(r2, SHEET, "created", 0, "AC-13");
        assertSummaryAllUnchanged(r2, SHEET, "AC-13");
        assertEquals(Set.of(1), versionsOfAxis(), "AC-13：一字未改却升了版");
        assertEquals(0L, countRows(H, axis()), "AC-13：一字未改却写了 _history");

        // ── TV-03 / AC-14：改比对项「组成用量」1 → 2 → 升版至 2，旧 8 行进 _history 且 version_no=1 ──
        Consumer<DatasetFixtureBuilder> mutV2 = b -> b.setNumber(SHEET, 3, "组成用量", 2d);
        Response r3 = importCostBasic(mutV2, "tv03-upgrade.xlsx");
        assertStatus(r3, 200, "AC-14");
        assertSummary(r3, SHEET, "upgraded", 1, "AC-14");

        assertEquals(Set.of(2), versionsOfAxis(), "AC-14：改了比对项，version_no 应全部变为 2");
        long archived = countRows(H, axis());
        assertEquals(baseRows, archived,
                "AC-14：归档行数应等于升版前主表该料号的行数（" + baseRows + "），实际 " + archived);
        assertEquals(archived, countRows(H, axis() + " AND version_no = 1"),
                "AC-14：归档行的 version_no 必须全为 1");
        assertEquals(archived, countRows(H, axis() + " AND archive_reason = 'IMPORT_UPGRADE'"),
                "AC-14：归档行的 archive_reason 必须全为 IMPORT_UPGRADE");
        assertNumericEquals("2", scalar(
                        "SELECT component_qty FROM " + T + " WHERE " + axis()
                                + " AND component_no = '" + ANCHOR_COMPONENT + "'"),
                "AC-14：新版本里该行的组成用量应为 2");
        System.out.printf("[TV-03] v=2 archived=%d%n", archived);

        // ── TV-04 / AC-15：删掉该料号的一行 → 行数不同即升版，版本 3 ──
        Consumer<DatasetFixtureBuilder> mutV3 = mutV2.andThen(b -> b.deleteRow(SHEET, 4));
        Response r4 = importCostBasic(mutV3, "tv04-delete-row.xlsx");
        assertStatus(r4, 200, "AC-15");
        assertSummary(r4, SHEET, "upgraded", 1, "AC-15");
        assertEquals(Set.of(3), versionsOfAxis(), "AC-15：行数不同即升版，version_no 应全部变为 3");

        long afterDelete = countRows(T, axis());
        assertEquals(baseRows - 1, afterDelete,
                "AC-15：删一行后主表该料号应剩 " + (baseRows - 1) + " 行，实际 " + afterDelete);
        // 三个量互不相等的自检：版本 3 ≠ 行数 7 ≠ 原行数 8
        assertTrue(3 != afterDelete && afterDelete != baseRows,
                "夹具设计失效：版本号与行数撞上了，巧合会掩盖缺陷（test.md §4）");
        System.out.printf("[TV-04] v=3 rows=%d（原 %d）%n", afterDelete, baseRows);

        long historyAfterV3 = countRows(H, axis());

        // ── TV-05 / AC-16：两行对调顺序、值一字不改 → UNCHANGED（指纹多重集不看顺序） ──
        // 🚨 FT-2 证伪实验瞄准这条：比较改成按顺序逐行比对，本断言必须变红。
        Consumer<DatasetFixtureBuilder> mutSwap = mutV3.andThen(b -> b.swapRows(SHEET, 3, 4));
        Response r5 = importCostBasic(mutSwap, "tv05-swap.xlsx");
        assertStatus(r5, 200, "AC-16");
        assertSummaryAllUnchanged(r5, SHEET, "AC-16");
        assertEquals(Set.of(3), versionsOfAxis(),
                "AC-16：只换行序不该升版（R-4 指纹多重集不看先后顺序）");
        assertEquals(historyAfterV3, countRows(H, axis()), "AC-16：不该新增历史行");

        // ── TV-06 / AC-17：改非比对项「项次」10 → 99 → UNCHANGED，且库值仍为 10 ──
        int anchorRow = anchorRowNumber(mutSwap);
        Consumer<DatasetFixtureBuilder> mutSeq = mutSwap.andThen(
                b -> b.setNumber(SHEET, anchorRow, "项次", 99d));
        Response r6 = importCostBasic(mutSeq, "tv06-item-seq.xlsx");
        assertStatus(r6, 200, "AC-17");
        assertSummaryAllUnchanged(r6, SHEET, "AC-17");
        assertEquals(Set.of(3), versionsOfAxis(), "AC-17：项次不是比对项，不该升版");

        Object itemSeq = scalar("SELECT item_seq FROM " + T + " WHERE " + axis()
                + " AND component_no = '" + ANCHOR_COMPONENT + "'");
        assertNotNull(itemSeq, "AC-17：查不到锚点行 ⇒ 断言空跑");
        assertEquals(10, ((Number) itemSeq).intValue(),
                "AC-17：未升版即未写入，项次应仍为 10（这是规范的既定行为，不是缺陷）");
        System.out.printf("[TV-06] item_seq 仍为 %s%n", itemSeq);

        // ── TV-07 / AC-18：5.5 → 5.500000 → UNCHANGED（R-3 数值规范化） ──
        // 🚨 FT-1 证伪实验瞄准这条：指纹去掉 stripTrailingZeros()，本断言必须变红。
        Consumer<DatasetFixtureBuilder> mutZeros = mutSeq.andThen(
                b -> b.setText("来料加工费", 3, "加工费", "5.500000"));
        Response r7 = importCostBasic(mutZeros, "tv07-trailing-zeros.xlsx");
        assertStatus(r7, 200, "AC-18");
        assertSummaryAllUnchanged(r7, "来料加工费", "AC-18");
        assertEquals(Set.of(3), versionsOfAxis(), "AC-18：物料BOM 不该被这次改动波及");

        Set<Integer> feeVersions = versionsOf("ds_cost_basic_incoming_process_fee");
        assertFalse(feeVersions.isEmpty(), "AC-18：来料加工费表里查不到该料号 ⇒ 断言空跑");
        assertEquals(Set.of(1), feeVersions,
                "AC-18：5.5 与 5.500000 必须同指纹，来料加工费不该升版，实际版本 " + feeVersions);
        assertEquals(0L, countRows("ds_cost_basic_incoming_process_fee_history", axis()),
                "AC-18：不该产生历史行");
        System.out.printf("[TV-07] 来料加工费版本 = %s（期望 {1}）%n", feeVersions);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-19 增量语义
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TV-08 / AC-19：只含一个料号的文件，不得动到另一个料号（R-6 增量语义）")
    void tv08_incrementalSemantics() {
        // 🚨 FT-4 证伪实验瞄准这条：改成「未出现的轴值也升版」，本用例必须变红。

        // 先把两个料号都灌进去：AXIS_BASIC（8 行）+ AXIS_BASIC_OTHER（2 行，由基准文件的另一个轴改名而来）
        Consumer<DatasetFixtureBuilder> both = b -> {
            // 取物料BOM 里第二个轴值的那两行，改成 AXIS_BASIC_OTHER
            List<Integer> rows = b.dataRowNumbers(SHEET, true);
            int r1 = rows.get(rows.size() - 2);
            int r2 = rows.get(rows.size() - 1);
            b.setText(SHEET, r1, "生产料号", AXIS_BASIC_OTHER);
            b.setText(SHEET, r2, "生产料号", AXIS_BASIC_OTHER);
            // 🚨 D-24：造出新轴值就必须同步登记进同一份 Excel 的物料 sheet，否则整份被拒
            Fixtures.registerAxis(b, "生产料号", AXIS_BASIC_OTHER, "AC19另一个料号");
        };
        assertStatus(importCostBasic(both, "tv08-seed.xlsx"), 200, "AC-19 前置");

        long otherRowsBefore = countRows(T, axisOther());
        assertTrue(otherRowsBefore > 0,
                "AC-19：前置未生效，" + AXIS_BASIC_OTHER + " 在库里 0 行 ⇒ 后面的「未被动过」是空验证");
        Set<Integer> otherVersionsBefore = versionsOf(T, AXIS_BASIC_OTHER);
        List<Object> otherFpBefore = fingerprints(T, AXIS_BASIC_OTHER);
        long otherHistoryBefore = countRows(H, axisOther());
        System.out.printf("[TV-08] 前置：%s rows=%d v=%s fp=%d%n",
                AXIS_BASIC_OTHER, otherRowsBefore, otherVersionsBefore, otherFpBefore.size());

        // 再导一份「只含 AXIS_BASIC」的文件：把 AXIS_BASIC_OTHER 那两行删掉
        Consumer<DatasetFixtureBuilder> onlyOne = both.andThen(b -> {
            // 删掉 AXIS_BASIC_OTHER 的全部行（物料 sheet 里的登记保留 —— 登记不等于引用）
            List<Integer> victims = b.dataRowNumbers(SHEET, true).stream()
                    .filter(r -> AXIS_BASIC_OTHER.equals(b.readAsString(SHEET, r, "生产料号")))
                    .toList();
            for (int i = victims.size() - 1; i >= 0; i--) {
                b.deleteRow(SHEET, victims.get(i));
            }
        });
        Response r = importCostBasic(onlyOne, "tv08-only-one-axis.xlsx");
        assertStatus(r, 200, "AC-19");

        assertEquals(otherRowsBefore, countRows(T, axisOther()),
                "AC-19：本次 Excel 里没出现的轴值，行数不该变");
        assertEquals(otherVersionsBefore, versionsOf(T, AXIS_BASIC_OTHER),
                "AC-19：本次 Excel 里没出现的轴值，version_no 不该变");
        assertEquals(otherFpBefore, fingerprints(T, AXIS_BASIC_OTHER),
                "AC-19：本次 Excel 里没出现的轴值，row_fingerprint 应逐值相等");
        assertEquals(otherHistoryBefore, countRows(H, axisOther()),
                "AC-19：_history 中不该新增该料号的行");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-20 三次导入改回原值
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TV-09 / AC-20：原始 → 改比对项 → 改回原值，最终 v3，_history 两组（v1、v2），指纹回到第 ① 次")
    void tv09_threeImportsRestoreOriginal() {
        // ① 原始
        assertStatus(importCostBasic(null, "tv09-1.xlsx"), 200, "AC-20 ①");
        List<Object> fpAfterFirst = fingerprints(T, AXIS_BASIC);
        assertFalse(fpAfterFirst.isEmpty(), "AC-20：第 ① 次导入后指纹为空 ⇒ 断言空跑");
        assertEquals(Set.of(1), versionsOfAxis(), "AC-20 ①：应为 v1");

        // ② 改一个比对项
        Consumer<DatasetFixtureBuilder> changed = b -> b.setNumber(SHEET, 3, "组成用量", 7d);
        assertStatus(importCostBasic(changed, "tv09-2.xlsx"), 200, "AC-20 ②");
        assertEquals(Set.of(2), versionsOfAxis(), "AC-20 ②：应升到 v2");
        List<Object> fpAfterSecond = fingerprints(T, AXIS_BASIC);
        assertFalse(fpAfterFirst.equals(fpAfterSecond),
                "AC-20 ②：改了比对项，指纹却没变 ⇒ 指纹没把该列算进去（R-3）");

        // ③ 改回原值
        assertStatus(importCostBasic(null, "tv09-3.xlsx"), 200, "AC-20 ③");
        assertEquals(Set.of(3), versionsOfAxis(),
                "AC-20 ③：改回原值仍是一次内容变化，应升到 v3（不是回退到 v1）");

        // _history 里应恰有两组：v1 与 v2
        Set<Integer> historyVersions = new LinkedHashSet<>();
        for (Object o : col("SELECT DISTINCT version_no FROM " + H + " WHERE " + axis() + " ORDER BY 1")) {
            historyVersions.add(((Number) o).intValue());
        }
        assertEquals(Set.of(1, 2), historyVersions,
                "AC-20：_history 应有 v1 与 v2 两组，实际 " + historyVersions);

        // 主表当前行的指纹 == 第 ① 次导入后的指纹
        assertEquals(fpAfterFirst, fingerprints(T, AXIS_BASIC),
                "AC-20：改回原值后，当前行的指纹多重集应等于第 ① 次导入后的指纹");
        System.out.printf("[TV-09] v3；_history 版本组 = %s；指纹回到第 ① 次 ✔%n", historyVersions);
    }

    // ═══════════════════════════════════════════════════════════════
    // 助手
    // ═══════════════════════════════════════════════════════════════

    private Response importCostBasic(Consumer<DatasetFixtureBuilder> mutate, String fileName) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC, b.toBytes(), fileName);
        } finally {
            b.close();
        }
    }

    /** 在当前夹具形态下，找到「项次 == 10」那一行的 Excel 物理行号（换序后位置会动）。 */
    private int anchorRowNumber(Consumer<DatasetFixtureBuilder> mutate) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            mutate.accept(b);
            for (int rowNo : b.dataRowNumbers(SHEET, true)) {
                if (AXIS_BASIC.equals(b.readAsString(SHEET, rowNo, "生产料号"))
                        && "10".equals(b.readAsString(SHEET, rowNo, "项次"))) {
                    return rowNo;
                }
            }
            throw new AssertionError("夹具里找不到项次 == 10 的行 ⇒ AC-17 无从构造");
        } finally {
            b.close();
        }
    }

    private String axis() {
        return "production_no = '" + AXIS_BASIC + "'";
    }

    private String axisOther() {
        return "production_no = '" + AXIS_BASIC_OTHER + "'";
    }

    private Set<Integer> versionsOfAxis() {
        return versionsOf(T, AXIS_BASIC);
    }

    private Set<Integer> versionsOf(String table) {
        return versionsOf(table, AXIS_BASIC);
    }

    private Set<Integer> versionsOf(String table, String axisValue) {
        if (!tableExists(table)) {
            return Set.of();
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (Object o : col("SELECT DISTINCT version_no FROM " + table
                + " WHERE production_no = '" + axisValue + "' ORDER BY 1")) {
            out.add(((Number) o).intValue());
        }
        return out;
    }

    /** 指纹按序取出 —— 多重集比较用（AC-19 / AC-20）。 */
    private List<Object> fingerprints(String table, String axisValue) {
        if (!tableExists(table)) {
            return List.of();
        }
        return new ArrayList<>(col("SELECT row_fingerprint FROM " + table
                + " WHERE production_no = '" + axisValue + "' ORDER BY row_fingerprint"));
    }

    private Object scalar(String sql) {
        List<Object> l = col(sql);
        return l.isEmpty() ? null : l.get(0);
    }

    private void assertStatus(Response r, int expected, String ac) {
        if (r.statusCode() == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷，"
                    + "或 admin 被 E2E 置成 INACTIVE。响应：" + r.asString());
        }
        assertEquals(expected, r.statusCode(), ac + "：HTTP 状态码不符。响应体：" + r.asString());
    }

    /** 断言该 sheet 的每一个轴值都是 UNCHANGED（unchanged == axisCount 且 axisCount > 0）。 */
    private void assertSummaryAllUnchanged(Response r, String sheet, String ac) {
        Map<String, Object> item = summaryOf(r, sheet, ac);
        int axisCount = ((Number) item.get("axisCount")).intValue();
        int unchanged = ((Number) item.get("unchanged")).intValue();
        assertTrue(axisCount > 0, ac + "：" + sheet + " 的轴值数为 0 ⇒ 「全部 UNCHANGED」是空验证");
        assertEquals(axisCount, unchanged,
                ac + "：一字未改的重导应让全部 " + axisCount + " 个轴值都 UNCHANGED，实际 "
                        + unchanged + "。整条：" + item);
    }

    private Map<String, Object> summaryOf(Response r, String sheet, String ac) {
        List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
        assertNotNull(summary, ac + "：响应缺 data.summary");
        assertFalse(summary.isEmpty(), ac + "：summary 为空 ⇒ 断言空跑");
        return summary.stream()
                .filter(m -> sheet.equals(String.valueOf(m.get("sheet"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        ac + "：summary 里没有 sheet「" + sheet + "」。实际：" + summary));
    }

    /** 断言导入汇总里某 sheet 的某个计数（api.md §1）。 */
    private void assertSummary(Response r, String sheet, String key, int expected, String ac) {
        List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
        assertNotNull(summary, ac + "：响应缺 data.summary。响应：" + r.asString());
        assertFalse(summary.isEmpty(), ac + "：summary 为空 ⇒ 断言空跑");
        Map<String, Object> item = summary.stream()
                .filter(m -> sheet.equals(String.valueOf(m.get("sheet"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        ac + "：summary 里没有 sheet「" + sheet + "」。实际：" + summary));
        Object actual = item.get(key);
        assertNotNull(actual, ac + "：summary[" + sheet + "] 缺字段 " + key + "。实际：" + item);
        assertEquals(expected, ((Number) actual).intValue(),
                ac + "：summary[" + sheet + "]." + key + " 期望 " + expected + "，实际 " + actual
                        + "。整条：" + item);
    }
}
