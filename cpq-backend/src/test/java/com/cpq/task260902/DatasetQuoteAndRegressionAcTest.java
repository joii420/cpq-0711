package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · F 组 报价侧导入 + G 组 回归 / 性能</b> —— 覆盖 AC-36 / AC-37 / AC-43 / AC-44。
 */
@QuarkusTest
@DisplayName("task-260902 · F 组报价导入 + 回归与性能（AC-36/37/43/44）")
class DatasetQuoteAndRegressionAcTest extends DatasetAcTestBase {

    // ═══════════════ AC-36 报价导入 ═══════════════

    @Test
    @DisplayName("TQ-02 / AC-36：报价模板导入成功 → 物料BOM v1；元素BOM 材质 992 下含量 21.11 与 2.78")
    void tq02_quoteImport() {
        Response r = importQuote(null, "tq02-quote.xlsx");
        assertStatus(r, 200, "AC-36");

        // ① ds_quote_material_bom 里该销售料号 version_no = 1
        List<Object> versions = col("SELECT DISTINCT version_no FROM ds_quote_material_bom "
                + "WHERE material_no = '" + AXIS_QUOTE + "'");
        assertFalse(versions.isEmpty(),
                "AC-36：ds_quote_material_bom 里 " + AXIS_QUOTE + " 一行都没有 ⇒ 断言空跑");
        assertEquals(List.of(1), versions.stream().map(o -> ((Number) o).intValue()).toList(),
                "AC-36：首次导入的 version_no 应全为 1");

        // ② ds_quote_element_bom 里 material_part_no='992' 的 content_pct 含 21.11 与 2.78
        List<Object> pcts = col("SELECT content_pct FROM ds_quote_element_bom "
                + "WHERE material_no = '" + AXIS_QUOTE + "' AND material_part_no = '" + RECIPE_992 + "' "
                + "ORDER BY content_pct");
        assertFalse(pcts.isEmpty(),
                "AC-36：ds_quote_element_bom 里材质 " + RECIPE_992 + " 一行都没有 ⇒ 断言空跑");

        List<String> plain = pcts.stream()
                .map(o -> new java.math.BigDecimal(String.valueOf(o)).stripTrailingZeros().toPlainString())
                .toList();
        assertTrue(plain.contains("21.11"),
                "AC-36：材质 " + RECIPE_992 + " 下应有 content_pct = 21.11，实际 " + plain);
        assertTrue(plain.contains("2.78"),
                "AC-36：材质 " + RECIPE_992 + " 下应有 content_pct = 2.78，实际 " + plain);
        System.out.printf("[TQ-02] %s 的元素含量 = %s%n", RECIPE_992, plain);
    }

    // ═══════════════ AC-37 报价重导 ═══════════════

    @Test
    @DisplayName("TQ-03 / AC-37：重复导入同一报价文件 → 全部 sheet UNCHANGED，13 张 ds_quote_*_history 全 0 行")
    void tq03_quoteReimportUnchanged() {
        assertStatus(importQuote(null, "tq03-1.xlsx"), 200, "AC-37 前置");

        long seeded = countRows("ds_quote_material_bom", "material_no LIKE '" + P + "%'");
        assertTrue(seeded > 0, "AC-37 前置：报价数据没灌进去 ⇒ 「UNCHANGED」是空验证");

        Response r = importQuote(null, "tq03-2.xlsx");
        assertStatus(r, 200, "AC-37");

        List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
        assertNotNull(summary, "AC-37：缺 data.summary");
        assertFalse(summary.isEmpty(), "AC-37：summary 为空 ⇒ 断言空跑");

        List<String> upgraded = new ArrayList<>();
        int versionedSeen = 0;
        for (Map<String, Object> item : summary) {
            if (!Boolean.TRUE.equals(item.get("versioned"))) {
                continue;
            }
            versionedSeen++;
            Number up = (Number) item.get("upgraded");
            Number created = (Number) item.get("created");
            if ((up != null && up.intValue() > 0) || (created != null && created.intValue() > 0)) {
                upgraded.add(String.valueOf(item.get("sheet")) + "→" + item);
            }
        }
        assertEquals(13, versionedSeen,
                "AC-37：报价侧应有 13 张带版本 sheet，summary 里只看到 " + versionedSeen + " 张");
        assertTrue(upgraded.isEmpty(),
                "AC-37：一字未改的重导必须全部 UNCHANGED，以下 sheet 却升版/新建了：" + upgraded);

        // 13 张 _history 全 0 行（限定在本次夹具的前缀内，避免被别的会话数据干扰）
        List<String> dirty = new ArrayList<>();
        int checked = 0;
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (!"quote".equals(s.dataset) || !s.versioned) {
                continue;
            }
            checked++;
            long n = countRows(s.historyTableName(), "material_no LIKE '" + P + "%'");
            if (n > 0) {
                dirty.add(s.historyTableName() + "=" + n);
            }
        }
        assertEquals(13, checked, "AC-37：报价侧带版本表应为 13 张，矩阵解析出 " + checked + " 张");
        assertTrue(dirty.isEmpty(), "AC-37：13 张 ds_quote_*_history 应全为 0 行，实际：" + dirty);
    }

    // ═══════════════ AC-43 新旧两条线互不串扰 ═══════════════

    @Test
    @DisplayName("TR-02 / AC-43：走旧导入端点后，ds_* 全部 84 张表 count 零变化（双轨互不串扰）")
    void tr02_legacyImportDoesNotTouchNewTables() {
        Map<String, Long> before = snapshotAllDatasetCounts();

        // 旧导入端点（2026-09-03 由主线给出正确路径，BasicDataImportV6Resource）：
        //   POST /api/cpq/basic-data-import/v6/pricing  ← 核价侧（「料号核价」页签内的导入）
        //   POST /api/cpq/basic-data-import/v6/quote    ← 报价侧（「从基础数据导入」按钮）
        // ⚠️ 我上一版猜的 /api/cpq/pricing-basic-data/import 根本不存在（返 404），
        //    那会让「新表没被碰」变成空验证 —— 见 assertNotEquals404 的守卫。
        // api.md §9 明确这两个端点「一个字节都不改」。
        Response legacyPricing = postLegacy("/api/cpq/basic-data-import/v6/pricing", "legacy-pricing.xlsx");
        assertNotEquals404(legacyPricing, "/api/cpq/basic-data-import/v6/pricing");
        System.out.printf("[TR-02] 旧核价导入端点返回 %d%n", legacyPricing.statusCode());

        Response legacyQuote = postLegacy("/api/cpq/basic-data-import/v6/quote", "legacy-quote.xlsx");
        assertNotEquals404(legacyQuote, "/api/cpq/basic-data-import/v6/quote");
        System.out.printf("[TR-02] 旧报价导入端点返回 %d%n", legacyQuote.statusCode());

        assertCountsUnchanged(before, snapshotAllDatasetCounts(),
                "AC-43：走旧导入端点不得写到任何 ds_* 表");
    }

    // ═══════════════ AC-44 性能 + 非 N+1 ═══════════════

    @Test
    @DisplayName("TP-01 / AC-44：物料BOM 2000 行 / 200 料号 → 单次导入 ≤ 30s 且成功")
    void tp01_bulkImportPerformance() {
        final int axisCount = 200;
        final int rowsPerAxis = 10;

        DatasetFixtureBuilder b = bulkBuilder(axisCount, rowsPerAxis);
        byte[] bytes;
        try {
            int total = b.dataRowCount("物料BOM", true);
            assertEquals(axisCount * rowsPerAxis, total,
                    "AC-44 夹具自检：物料BOM 应有 " + (axisCount * rowsPerAxis) + " 行，实际 " + total);
            bytes = b.toBytes();
        } finally {
            b.close();
        }

        long t0 = System.nanoTime();
        Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC, bytes, "tp01-bulk.xlsx");
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertStatus(r, 200, "AC-44");
        System.out.printf("[TP-01] 2000 行 / 200 料号 导入耗时 %d ms%n", ms);
        assertTrue(ms <= 30_000,
                "AC-44：单次导入应 ≤ 30s，实际 " + ms + " ms");

        long stored = countRows("ds_cost_basic_material_bom", "production_no LIKE '" + P + "PERF-%'");
        assertEquals((long) axisCount * rowsPerAxis, stored,
                "AC-44：应落库 " + (axisCount * rowsPerAxis) + " 行，实际 " + stored
                        + " ⇒ 「30s 内完成」若建立在少写了行的基础上就是假绿");

        // 🚩 AC-44 的后半句「SQL 条数与料号数无关」属于日志/探针口径，
        //    本层拿不到 SQL 计数器 ⇒ 用「耗时随料号数的增长是否线性」做弱代理，结论以主线亲验的日志为准。
        System.out.println("[TP-01] ⚠️ 「SQL 条数不随料号数线性增长」需主线在 dev server 日志侧亲验，"
                + "本用例只覆盖耗时与落库完整性。");
    }

    /** 造 200 料号 × 10 行的物料BOM（其余 sheet 保持模板原样）。 */
    private DatasetFixtureBuilder bulkBuilder(int axisCount, int rowsPerAxis) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        List<Integer> src = b.dataRowNumbers("物料BOM", true);
        assertFalse(src.isEmpty(), "AC-44：模板物料BOM 无数据行，无法复制造数");
        int templateRow = src.get(0);

        for (int i = 0; i < axisCount; i++) {
            String axis = P + "PERF-" + String.format("%03d", i);
            // 🚨 D-24：每个新轴值都要同步登记进物料 sheet，否则整份被拒（201 处「轴值未在物料表登记」）
            Fixtures.registerAxis(b, "生产料号", axis, "性能测试料号" + i);
            for (int j = 0; j < rowsPerAxis; j++) {
                final int seq = (j + 1) * 10;
                b.appendCopyOf("物料BOM", templateRow, row -> {
                    row.getCell(0).setCellValue(axis);   // 生产料号（轴）
                    row.getCell(1).setCellValue(seq);    // 项次
                });
            }
        }
        // 清掉模板原有的数据行，只留造出来的 2000 行
        for (int i = src.size() - 1; i >= 0; i--) {
            b.deleteRow("物料BOM", src.get(i));
        }
        return b;
    }

    // ═══════════════ 助手 ═══════════════

    private Response importQuote(Consumer<DatasetFixtureBuilder> mutate, String name) {
        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return DatasetApi.importFile(adminSession(), DatasetApi.QUOTE, b.toBytes(), name);
        } finally {
            b.close();
        }
    }

    /** 往旧导入端点发一份夹具文件（内容合不合法不影响本条判据，它验的是「新表没被碰」）。 */
    private Response postLegacy(String path, String fileName) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            return io.restassured.RestAssured.given()
                    .cookie("CPQ_SESSION", adminSession())
                    .multiPart("file", fileName, b.toBytes(),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .when().post(path);
        } finally {
            b.close();
        }
    }

    /**
     * 🚨 阳性对照：先证明请求**真的打到了**旧链路，再谈「新表没被碰」。
     * 端点若返 404，那条结论就是空验证 —— 一个根本没执行的写入当然不会碰任何表。
     */
    private void assertNotEquals404(Response r, String path) {
        assertTrue(r.statusCode() != 404,
                "TR-02：旧导入端点 " + path + " 返回 404 ⇒ 请求根本没打到旧链路，"
                        + "本条「新表没被碰」是空验证（testing.md §4.4 要求阳性对照）。响应：" + r.asString());
    }

    private void assertStatus(Response r, int expected, String ac) {
        if (r.statusCode() == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷。响应：" + r.asString());
        }
        assertEquals(expected, r.statusCode(), ac + "：HTTP 状态码不符。响应体：" + r.asString());
    }
}
