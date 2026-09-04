package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · B 组 导入校验</b> —— 覆盖 AC-6 ~ AC-11。
 *
 * <p>判据全部取自 {@code 需求文档.md ④ B 组} 与 {@code api.md §1}（错误条目的四个字段 + reason 封闭集）。
 */
@QuarkusTest
@DisplayName("task-260902 · B 组 导入校验（AC-6~AC-11）")
class DatasetImportValidationAcTest extends DatasetAcTestBase {

    // ═════════════════════ AC-6 必填为空 ═════════════════════

    @Test
    @DisplayName("TI-01 / AC-6：红底必填列为空 → 400 + 单条报告 + 该表 count 不变")
    void ti01_requiredBlank() {
        long before = countRows("ds_cost_basic_material_bom", null);

        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            // 物料BOM 第 3 行的「组成料号」是红底必填（字段矩阵：component_no / FFFF0000）
            b.blank("物料BOM", 2, "组成料号");
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "ac06-required-blank.xlsx");

            assertStatus(r, 400, "AC-6");
            List<Map<String, Object>> errors = errorsOf(r);
            assertHasError(errors, "物料BOM", 2, "组成料号", "必填项为空", "AC-6");
            assertEquals(1, errors.size(),
                    "AC-6 要求报告「含且仅含一条」，实际 " + errors.size() + " 条：" + errors);

            long after = countRows("ds_cost_basic_material_bom", null);
            assertEquals(before, after,
                    "AC-6：Phase 1 拒收必须一行不写，ds_cost_basic_material_bom 的 count 变了 "
                            + before + " → " + after);
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-7 橙底轴列为空 ═════════════════════

    @Test
    @DisplayName("TI-02 / AC-7：橙底的轴列为空 → 400 + 轴列不可为空（R-1 凌驾底色）")
    void ti02_axisBlankOverridesFill() {
        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            // 🚩 B-21（2026-09-03）后：建表模板自带的「轴/对比项」标记行已由 stripMarkerRows 删掉，
            //    而「年降系数」在模板里本来就一条数据行都没有 ⇒ 夹具直接在末尾造一条全新数据行、轴值留空。
            //    🚫 不能再用 appendCopyOf(sheet, 2, ...) —— 第 2 行现在要么不存在、要么是真数据，
            //    复制它测的都不是 AC-7 想测的东西。
            b.appendBlankRow("年降系数", row -> {
                row.createCell(0).setBlank();                  // 销售料号（轴，橙底 FFFFC000）留空
                row.createCell(1).setCellValue(1d);            // 年降顺序
                row.createCell(2).setCellValue("5");           // 年降系数（%/年）
                row.createCell(4).setCellValue("CNY");         // 货币
                row.createCell(6).setCellValue(1d);            // 降价次数
            });
            int rowNo = b.dataRowNumbers("年降系数", true).get(0);

            Response r = DatasetApi.importFile(adminSession(), DatasetApi.QUOTE,
                    b.toBytes(), "ac07-axis-blank.xlsx");

            assertStatus(r, 400, "AC-7");
            List<Map<String, Object>> errors = errorsOf(r);
            assertHasError(errors, "年降系数", rowNo, "销售料号", "轴列不可为空", "AC-7");
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-8 主数据不存在 ═════════════════════

    @Test
    @DisplayName("TI-03 / AC-8：元素代码 ZZZZ 不在 element 表 → 400 + 主数据不存在")
    void ti03_masterDataMissing() {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            b.setText("物料与元素BOM", 2, "元素代码", ELEMENT_ABSENT);
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "ac08-master-missing.xlsx");

            assertStatus(r, 400, "AC-8");
            assertHasError(errorsOf(r), "物料与元素BOM", 2, "元素代码", "主数据不存在", "AC-8");
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-9 非数值 ═════════════════════

    @Test
    @DisplayName("TI-04 / AC-9：数值列填 abc → 400 + 不是合法数值")
    void ti04_notANumber() {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            b.setText("来料加工费", 2, "加工费", "abc");
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "ac09-nan.xlsx");

            assertStatus(r, 400, "AC-9");
            assertHasError(errorsOf(r), "来料加工费", 2, "加工费", "不是合法数值", "AC-9");
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-10 四类错误同时 + 45 表零写库 ═════════════════════

    @Test
    @DisplayName("TI-05 / AC-10：4 类错误分布在 4 个 sheet → 一次返回 4 条，且 45 张表 count 全不变")
    void ti05_allFourErrorsAndZeroWrite() {
        // 🚨 FT-3 证伪实验瞄准的就是这条：把写库提到校验之前，本用例必须变红。
        Map<String, Long> before = snapshotAllDatasetCounts();

        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            b.blank("物料BOM", 2, "组成料号");                       // ① 必填项为空
            b.setText("物料与元素BOM", 2, "元素代码", ELEMENT_ABSENT); // ② 主数据不存在
            b.setText("来料加工费", 2, "加工费", "abc");               // ③ 不是合法数值
            b.blank("成品其他固定费用", 2, "生产料号");                // ④ 轴列不可为空

            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "ac10-four-errors.xlsx");

            assertStatus(r, 400, "AC-10");
            List<Map<String, Object>> errors = errorsOf(r);

            assertHasError(errors, "物料BOM", 2, "组成料号", "必填项为空", "AC-10");
            assertHasError(errors, "物料与元素BOM", 2, "元素代码", "主数据不存在", "AC-10");
            assertHasError(errors, "来料加工费", 2, "加工费", "不是合法数值", "AC-10");
            assertHasError(errors, "成品其他固定费用", 3, "生产料号", "轴列不可为空", "AC-10");

            // 🚫 不许 fail-fast：AC-10 的核心就是「同时列出 4 条，不是只报第一条」
            assertTrue(errors.size() >= 4,
                    "AC-10：一次导入必须同时返回 4 条错误（不许遇错即停），实际 " + errors.size() + " 条：" + errors);

            Map<String, Long> after = snapshotAllDatasetCounts();
            assertCountsUnchanged(before, after,
                    "AC-10：Phase 1 拒收后全部 45 张表 count 必须逐表相等（零写库）");
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-11 合法导入 ═════════════════════

    @Test
    @DisplayName("TI-06 / AC-11：合法文件 → 200 + summary 含每 sheet 的轴值数 / CREATED / UPGRADED / UNCHANGED")
    void ti06_validImportSummary() {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "核价2-合法.xlsx");

            assertStatus(r, 200, "AC-11");

            List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
            assertNotNull(summary, "AC-11：响应体缺 data.summary");
            assertFalse(summary.isEmpty(), "AC-11：summary 为空 ⇒ 后面的字段断言会空跑（假绿）");
            assertEquals(Fixtures.COST_BASIC_SHEETS.size(), summary.size(),
                    "AC-11：summary 应逐 sheet 汇总，期望 " + Fixtures.COST_BASIC_SHEETS.size()
                            + " 条，实际 " + summary.size());

            List<String> problems = new ArrayList<>();
            for (Map<String, Object> item : summary) {
                Object sheet = item.get("sheet");
                if (sheet == null) {
                    problems.add("有条目缺 sheet 字段：" + item);
                    continue;
                }
                Object versioned = item.get("versioned");
                if (Boolean.TRUE.equals(versioned)) {
                    for (String k : List.of("axisCount", "created", "upgraded", "unchanged")) {
                        if (!item.containsKey(k)) {
                            problems.add(sheet + " 缺 " + k);
                        }
                    }
                } else if (Boolean.FALSE.equals(versioned)) {
                    for (String k : List.of("inserted", "updated")) {
                        if (!item.containsKey(k)) {
                            problems.add(sheet + " 缺 " + k);
                        }
                    }
                } else {
                    problems.add(sheet + " 缺 versioned 字段（api.md §1 要求）");
                }
            }
            assertTrue(problems.isEmpty(), "AC-11 / api.md §1 summary 结构不完整：" + problems);

            // 断言非空正向结果：合法导入必须真的写进去了，不能 200 却零落库
            long bomRows = countRows("ds_cost_basic_material_bom", "production_no='" + AXIS_BASIC + "'");
            assertTrue(bomRows > 0,
                    "AC-11：返回 200 但 ds_cost_basic_material_bom 里 " + AXIS_BASIC
                            + " 一行都没有 ⇒ 「导入成功」是空的（testing.md §3.3 假绿）");
            System.out.printf("[TI-06] 导入后 %s 的物料BOM 行数 = %d%n", AXIS_BASIC, bomRows);
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-52 轴值登记校验（D-24） ═════════════════════

    /**
     * AC-52（2026-09-03 新增，裁决 D-24）。
     *
     * <p>判定集合 = <b>本次 Excel 物料 sheet 的轴值 ∪ 库中物料表已有的轴值</b>
     * —— 同一份文件内「先登记后引用」要放行，所以本用例的第二段特意<b>只改 Excel、不预先写库</b>，
     * 验的就是「同文件内登记」这条分支。
     */
    @Test
    @DisplayName("TI-07 / AC-52：物料BOM 轴值未在物料表登记 → 400 + 10 张表 count 不变；补进物料 sheet 后重导 → 200 且两轴均 CREATED")
    void ti07_axisMustBeRegisteredInMaterialSheet() {
        String m1 = P + "M1";
        String m9 = P + "M9";

        // 前置自检：M9 确实不在库里，否则「未登记」这条是空验证
        assertEquals(0L, countRows("ds_cost_basic_material", "production_no = '" + m9 + "'"),
                "AC-52 前置：" + m9 + " 不该已存在于 ds_cost_basic_material");

        Map<String, Long> before = costBasicCounts();

        // ── 第一段：物料 sheet 只登记 M1，物料BOM 却引用 M1 + M9 → 400 ──
        DatasetFixtureBuilder b1 = buildTwoAxisFixture(m1, m9, false);
        try {
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b1.toBytes(), "ac52-unregistered.xlsx");
            assertStatus(r, 400, "AC-52 第一段");

            List<Map<String, Object>> errors = errorsOf(r);
            boolean hit = errors.stream().anyMatch(e ->
                    "物料BOM".equals(String.valueOf(e.get("sheet")))
                            && "生产料号".equals(String.valueOf(e.get("column")))
                            && "轴值未在物料表登记".equals(String.valueOf(e.get("reason"))));
            assertTrue(hit, "AC-52：报告里应有 {sheet=物料BOM, column=生产料号, reason=轴值未在物料表登记}，"
                    + "实际：" + errors);

            assertCountsUnchanged(before, costBasicCounts(),
                    "AC-52：轴值未登记必须整份拒收，10 张 ds_cost_basic_* 表 count 逐表相等");
        } finally {
            b1.close();
        }

        // ── 第二段：把 M9 也补进物料 sheet（🚫 不预先写库）→ 200，两个轴值均 CREATED ──
        DatasetFixtureBuilder b2 = buildTwoAxisFixture(m1, m9, true);
        try {
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b2.toBytes(), "ac52-registered.xlsx");
            assertStatus(r, 200, "AC-52 第二段");
            assertSummaryCreated(r, "物料BOM", 2);

            for (String axis : List.of(m1, m9)) {
                long n = countRows("ds_cost_basic_material_bom", "production_no = '" + axis + "'");
                assertTrue(n > 0, "AC-52：补登记后 " + axis + " 应有数据行，实际 " + n);
                List<Object> v = col("SELECT DISTINCT version_no FROM ds_cost_basic_material_bom "
                        + "WHERE production_no = '" + axis + "'");
                assertEquals(List.of(1), v.stream().map(o -> ((Number) o).intValue()).toList(),
                        "AC-52：" + axis + " 首次落库应为 v1（CREATED）");
            }
            System.out.printf("[TI-07] 补登记后 %s / %s 均 CREATED v1%n", m1, m9);
        } finally {
            b2.close();
        }
    }

    /**
     * 造一份「物料 sheet 只有 M1（或 M1+M9）、物料BOM 两个轴值都有」的核价2 夹具。
     * 其余 sheet 清空，避免被无关 sheet 的校验噪声干扰判据。
     */
    private DatasetFixtureBuilder buildTwoAxisFixture(String m1, String m9, boolean registerM9) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());

        // 物料 sheet：只留一行，改成 M1；registerM9 时再追加一行 M9
        List<Integer> matRows = b.dataRowNumbers("物料", false);
        for (int i = matRows.size() - 1; i >= 1; i--) {
            b.deleteRow("物料", matRows.get(i));
        }
        b.setText("物料", 2, "生产料号", m1);
        b.setText("物料", 2, "品名", "AC52主料");
        if (registerM9) {
            b.appendCopyOf("物料", 2, row -> {
                row.getCell(0).setCellValue(m9);
                row.getCell(1).setCellValue("AC52主料9");
            });
        }

        // 物料BOM：只留两行，轴分别为 M1 / M9
        List<Integer> bomRows = b.dataRowNumbers("物料BOM", true);
        for (int i = bomRows.size() - 1; i >= 2; i--) {
            b.deleteRow("物料BOM", bomRows.get(i));
        }
        List<Integer> kept = b.dataRowNumbers("物料BOM", true);
        b.setText("物料BOM", kept.get(0), "生产料号", m1);
        b.setText("物料BOM", kept.get(1), "生产料号", m9);

        // 其余带版本 sheet 清空
        for (String sheet : Fixtures.COST_BASIC_VERSIONED_SHEETS) {
            if (!"物料BOM".equals(sheet)) {
                b.clearDataRows(sheet, true);
            }
        }
        return b;
    }

    private Map<String, Long> costBasicCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if ("cost-basic".equals(s.dataset)) {
                m.put(s.tableName, countRows(s.tableName, null));
                if (s.versioned) {
                    m.put(s.historyTableName(), countRows(s.historyTableName(), null));
                }
            }
        }
        return m;
    }

    private void assertSummaryCreated(Response r, String sheet, int expected) {
        List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
        assertNotNull(summary, "AC-52：缺 data.summary");
        Map<String, Object> item = summary.stream()
                .filter(x -> sheet.equals(String.valueOf(x.get("sheet"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("summary 里没有 " + sheet + "，实际 " + summary));
        Object v = item.get("created");
        assertNotNull(v, "AC-52：summary[" + sheet + "] 缺 created");
        assertEquals(expected, ((Number) v).intValue(),
                "AC-52：summary[" + sheet + "].created 期望 " + expected + "，实际 " + v + "，整条：" + item);
    }

    // ═════════════════════ 断言助手 ═════════════════════

    private void assertStatus(Response r, int expected, String ac) {
        int actual = r.statusCode();
        if (actual == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷"
                    + "（application-test.properties 覆盖导致不带 session 恒 401），"
                    + "或 admin 被 E2E 置成 INACTIVE —— 不要误判成端点没做。响应：" + r.asString());
        }
        assertEquals(expected, actual, ac + "：HTTP 状态码不符。响应体：" + r.asString());
    }

    private List<Map<String, Object>> errorsOf(Response r) {
        List<Map<String, Object>> errors = r.jsonPath().getList("data.errors");
        assertNotNull(errors, "api.md §1：400 响应必须带 data.errors 数组。实际响应：" + r.asString());
        assertFalse(errors.isEmpty(), "data.errors 为空 ⇒ 逐条断言会空跑（假绿）。响应：" + r.asString());
        return errors;
    }

    private void assertHasError(List<Map<String, Object>> errors,
                                String sheet, int row, String column, String reason, String ac) {
        boolean hit = errors.stream().anyMatch(e ->
                sheet.equals(String.valueOf(e.get("sheet")))
                        && String.valueOf(row).equals(String.valueOf(e.get("row")))
                        && column.equals(String.valueOf(e.get("column")))
                        && reason.equals(String.valueOf(e.get("reason"))));
        assertTrue(hit, ac + "：报告里找不到条目 {sheet=" + sheet + ", row=" + row
                + ", column=" + column + ", reason=" + reason + "}。实际报告：" + errors);
    }
}
