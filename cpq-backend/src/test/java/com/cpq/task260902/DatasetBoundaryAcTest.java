package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · G 组 边界</b> —— 覆盖 AC-39（空 sheet）、AC-40（超长）、AC-41（并发冲突），
 * 外加 AC-34 / AC-31 的<b>后端半边</b>（UI 半边在 Playwright）。
 */
@QuarkusTest
@DisplayName("task-260902 · G 组 边界（AC-39~AC-41 + AC-34/31 后端半边）")
class DatasetBoundaryAcTest extends DatasetAcTestBase {

    private static final String SHEET = "物料BOM";
    private static final String T = "ds_cost_basic_material_bom";

    // ═══════════════ AC-39 空 sheet ≠ 清空 ═══════════════

    @Test
    @DisplayName("TB-01 / AC-39：某带版本 sheet 只剩表头 → 200 + 该 sheet 轴值数 0 + 该表 count 与导入前相等")
    void tb01_emptySheetIsNotClear() {
        // 先灌一批数据，否则「空 sheet 不清空」是在空表上验的，等于没验（testing.md §3.3）
        assertStatus(importCostBasic(null, "tb01-seed.xlsx"), 200, "AC-39 前置");
        long before = countRows(T, "production_no LIKE '" + P + "%'");
        assertTrue(before > 0,
                "AC-39 前置：" + T + " 里前缀行数为 0 ⇒ 「空 sheet 不清空」是空验证");

        Consumer<DatasetFixtureBuilder> emptied = b -> b.clearDataRows(SHEET, true);
        Response r = importCostBasic(emptied, "tb01-empty-sheet.xlsx");
        assertStatus(r, 200, "AC-39");

        assertSummaryAxisCount(r, SHEET, 0, "AC-39");
        assertEquals(before, countRows(T, "production_no LIKE '" + P + "%'"),
                "AC-39：空 sheet 不等于清空，库中原有数据必须原封不动（R-6）");
    }

    // ═══════════════ AC-40 超长输入：成功且完整，或 400 报超长；不许静默截断 ═══════════════

    /**
     * AC-40（🚩 2026-09-03 修正版）。
     *
     * <p>旧稿写「要素名称 128 字 / 上限 128」是错的 —— 该列实为 {@code varchar(256)}，
     * 后端 #2 实测 129 汉字合法入库时发现。新判据换成两条：
     * <ul>
     *   <li>真正是 {@code varchar(128)} 的「币种」填 <b>129</b> 字符 → 400 + {@code 超出长度上限 128}</li>
     *   <li>{@code varchar(256)} 的「要素名称」填 <b>257</b> 字符 → 400 + {@code 超出长度上限 256}</li>
     * </ul>
     * 两条都<b>不允许静默截断</b> —— 库里不得出现被截短的值。
     *
     * <p>⚠️ 判据先自检了这两列在库中的真实长度上限，长度对不上就以「AC 数字与 schema 不符」硬失败，
     * 不会伪装成实现缺陷（上一版 AC-40 正是栽在这里）。
     */
    @Test
    @DisplayName("TB-02 / AC-40：币种 129 字符 → 超出长度上限 128；要素名称 257 字符 → 超出长度上限 256；🚫 不许静默截断")
    void tb02_longTextNoSilentTruncation() {
        String table = "ds_cost_basic_finished_fixed_fee";
        assertTrue(tableExists(table), "AC-40：" + table + " 不存在，断言无从执行");

        // ── 判据自检：先确认两列的真实上限就是 128 / 256 ──
        assertColumnMaxLength(table, "currency", 128, "币种");
        assertColumnMaxLength(table, "element_name", 256, "要素名称");

        // ── ① 币种 129 字符 → 超出长度上限 128 ──
        String c129 = "A".repeat(129);
        assertEquals(129, c129.length(), "夹具自检");
        assertOverLengthRejected(
                b -> b.setText("成品其他固定费用", 3, "币种", c129),
                "币种", 128, table, "currency", c129, "tb02-currency-129.xlsx");

        // ── ② 要素名称 257 字符 → 超出长度上限 256 ──
        String n257 = "测".repeat(257);
        assertEquals(257, n257.length(), "夹具自检");
        assertOverLengthRejected(
                b -> b.setText("成品其他固定费用", 3, "要素名称", n257),
                "要素名称", 256, table, "element_name", n257, "tb02-elementname-257.xlsx");
    }

    /** 断言某列在库中的 {@code character_maximum_length} 与 AC 的数字一致。 */
    private void assertColumnMaxLength(String table, String column, int expected, String label) {
        Object v = scalarOrNull("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + table + "' AND column_name='" + column + "'");
        assertNotNull(v, "AC-40 判据自检：查不到 " + table + "." + column + " 的长度上限");
        assertEquals(expected, ((Number) v).intValue(),
                "AC-40 判据自检失败：「" + label + "」(" + column + ") 的实际上限是 " + v
                        + "，而 AC 写的是 " + expected + "。"
                        + "\n  ⇒ 这是 AC 数字与 schema 不符（上一版 AC-40 就是这么错的），不是实现缺陷。"
                        + "请主线核对后再跑。");
    }

    /** 超长值必须被拒收（400 + 指定 reason），且库中不得出现被截短的值。 */
    private void assertOverLengthRejected(Consumer<DatasetFixtureBuilder> mutate,
                                          String excelColumn, int limit,
                                          String table, String dbColumn,
                                          String sentValue, String fileName) {
        Response r = importCostBasic(mutate, fileName);

        assertEquals(400, r.statusCode(),
                "AC-40：「" + excelColumn + "」超长（" + sentValue.length() + " > " + limit
                        + "）必须返回 400，实际 " + r.statusCode() + "。响应：" + r.asString());

        List<Map<String, Object>> errors = r.jsonPath().getList("data.errors");
        assertNotNull(errors, "AC-40：400 时必须带 data.errors。响应：" + r.asString());
        assertFalse(errors.isEmpty(), "AC-40：errors 为空 ⇒ 断言空跑");

        String want = "超出长度上限 " + limit;
        boolean hit = errors.stream().anyMatch(e ->
                excelColumn.equals(String.valueOf(e.get("column")))
                        && want.equals(String.valueOf(e.get("reason"))));
        assertTrue(hit, "AC-40：报告里应有 {column=" + excelColumn + ", reason=" + want
                + "}，实际报告：" + errors);

        // 🚫 静默截断的反向证据：库里不得出现该值的任何前缀
        String prefix = sentValue.substring(0, Math.min(20, sentValue.length()));
        long truncated = countRows(table,
                dbColumn + " LIKE '" + prefix.replace("'", "''") + "%'");
        assertEquals(0L, truncated,
                "AC-40 🚫 静默截断：整份拒收后库中却出现了 " + table + "." + dbColumn
                        + " 以「" + prefix + "…」开头的行（" + truncated + " 行）");
    }

    // ═══════════════ AC-41 乐观锁冲突 ═══════════════

    @Test
    @DisplayName("TB-03 / AC-41：A 保存成功后 B 用过期 baseVersion 保存 → 409 + B 的提交未写入")
    void tb03_optimisticLockConflict() {
        assertStatus(importCostBasic(null, "tb03-seed.xlsx"), 200, "AC-41 前置");

        String sheetKey = resolveSheetKey(SHEET);
        Response rowsResp = DatasetApi.rows(adminSession(), DatasetApi.COST_BASIC,
                AXIS_BASIC, sheetKey, null);
        assertStatus(rowsResp, 200, "AC-41 前置读行");

        int baseVersion = rowsResp.jsonPath().getInt("data.versionNo");
        List<Map<String, Object>> rows = rowsResp.jsonPath().getList("data.rows");
        assertNotNull(rows, "AC-41 前置：rows 为 null");
        assertFalse(rows.isEmpty(), "AC-41 前置：rows 为空 ⇒ 冲突断言会空跑");

        // A：改一个比对项后保存 → 升版
        List<Map<String, Object>> rowsA = stripServerOnlyFields(rows);
        rowsA.get(0).put("component_qty", "11");
        Response respA = DatasetApi.saveRows(adminSession(), DatasetApi.COST_BASIC,
                AXIS_BASIC, sheetKey, baseVersion, rowsA);
        assertStatus(respA, 200, "AC-41 A 保存");
        int newVersion = respA.jsonPath().getInt("data.versionNo");
        assertEquals(baseVersion + 1, newVersion,
                "AC-41 前置：A 保存后版本应为 " + (baseVersion + 1) + "，实际 " + newVersion);

        long historyBefore = countRows("ds_cost_basic_material_bom_history",
                "production_no = '" + AXIS_BASIC + "'");

        // B：仍拿着旧 baseVersion 保存 → 409
        List<Map<String, Object>> rowsB = stripServerOnlyFields(rows);
        rowsB.get(0).put("component_qty", "22");
        Response respB = DatasetApi.saveRows(adminSession(), DatasetApi.COST_BASIC,
                AXIS_BASIC, sheetKey, baseVersion, rowsB);

        assertEquals(409, respB.statusCode(),
                "AC-41：B 用过期 baseVersion=" + baseVersion + " 保存应返回 409，实际 "
                        + respB.statusCode() + "，响应：" + respB.asString());
        // ⚠️ 不能用 jsonPath().get("message")：RestAssured 的 Groovy GPath 会把它当字符序列展开，
        //    抛 ClassCastException: String cannot be cast to [C。用 getString 明确取标量。
        String msg = respB.jsonPath().getString("message");
        assertTrue(msg.contains("数据已被他人更新至 v" + newVersion),
                "AC-41：冲突文案应为「数据已被他人更新至 v" + newVersion + "，请刷新后重试」，实际「" + msg + "」");

        // B 的提交未写入
        Response after = DatasetApi.rows(adminSession(), DatasetApi.COST_BASIC, AXIS_BASIC, sheetKey, null);
        assertEquals(newVersion, after.jsonPath().getInt("data.versionNo"),
                "AC-41：B 冲突后版本号不该变");
        assertEquals(historyBefore, countRows("ds_cost_basic_material_bom_history",
                        "production_no = '" + AXIS_BASIC + "'"),
                "AC-41：B 冲突后 _history 不该新增");
        assertNumericEquals("11", scalarOrNull("SELECT component_qty FROM " + T
                        + " WHERE production_no = '" + AXIS_BASIC + "' AND component_qty IN (11, 22) LIMIT 1"),
                "AC-41：库里应保留 A 写的 11，B 的 22 必须没写进去");
    }

    // ═══════════════ AC-34 后端半边：错数据集整份拒收 ═══════════════

    @Test
    @DisplayName("TE-11 后端半边 / AC-34：把报价文件传给 cost-basic → 400 + 「不属于基础核价数据集」，且 ds_cost_basic_* 全不变")
    void te11_wrongDatasetRejected() {
        Map<String, Long> before = datasetCounts("cost-basic");

        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC,
                    b.toBytes(), "wrong-dataset.xlsx");
            assertStatus(r, 400, "AC-34");

            List<Map<String, Object>> errors = r.jsonPath().getList("data.errors");
            assertNotNull(errors, "AC-34：400 必须带 data.errors");
            assertFalse(errors.isEmpty(), "AC-34：errors 为空 ⇒ 断言空跑");

            boolean hit = errors.stream().anyMatch(e ->
                    String.valueOf(e.get("reason")).contains("不属于基础核价数据集"));
            assertTrue(hit,
                    "AC-34：报告里应有 `sheet「{名}」不属于基础核价数据集`。"
                            + "\n  ⚠️ AC 原文点名的是「核价1」文件里的 sheet「产能」，但该文件损坏且不在 worktree"
                            + "（见 TD-01c）⇒ 这里改用报价文件构造同型错误，"
                            + "「产能」这个字面 sheet 名待主线补文件后亲验。"
                            + "\n  实际报告：" + errors);

            assertCountsUnchanged(before, datasetCounts("cost-basic"),
                    "AC-34：错数据集必须整份拒收，ds_cost_basic_* 全部表 count 不变");
        } finally {
            b.close();
        }
    }

    // ═══════════════ AC-31 后端半边：写端点鉴权 ═══════════════

    @Test
    @DisplayName("TE-08 后端半边 / AC-31：无 session 调写端点 → 401（角色维度的「可见但禁用」在 E2E 验）")
    void te08_writeEndpointsRequireAuth() {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            Response imp = DatasetApi.importFileNoSession(DatasetApi.COST_BASIC,
                    b.toBytes(), "no-session.xlsx");
            assertEquals(401, imp.statusCode(),
                    "AC-31：未登录调 POST import 应 401，实际 " + imp.statusCode());
        } finally {
            b.close();
        }

        Response put = DatasetApi.saveRowsNoSession(DatasetApi.COST_BASIC, AXIS_BASIC,
                resolveSheetKey(SHEET), 1, List.of(Map.of("item_seq", 10)));
        assertEquals(401, put.statusCode(),
                "AC-31：未登录调 PUT rows 应 401，实际 " + put.statusCode());
    }

    // ═══════════════ AC-25 配置状态过滤（B-15） ═══════════════

    /**
     * AC-25 的「配置状态：全部 / 已配齐 / 未配齐」三态过滤（`原型图/核价数据-列表.html`）。
     *
     * <p>🚨 判据写成<b>不变量</b>而不是写死数字：
     * {@code 已配齐 + 未配齐 == 全部}，且两个子集<b>互不相交</b>、<b>各自非空</b>。
     * 共享库在漂移（{@code test.md §0.3}），写死行数必然是假红；
     * 而只断言「加起来相等」又会被「两边都返回全集」骗过 —— 所以再加一条不相交。
     */
    @Test
    @DisplayName("TQ-05 / AC-25：configured 三态 —— 已配齐 ∪ 未配齐 = 全部，且互不相交、各自非空")
    void tq05_configuredFilterTriState() {
        // 先灌一份数据，保证两个子集都非空（否则不变量在空集上恒成立 = 假绿）
        assertStatus(importCostBasic(null, "ac25-seed.xlsx"), 200, "AC-25 前置");

        long all = partsTotal(null);
        assertTrue(all > 0, "AC-25：列表为空 ⇒ 三态断言会空跑（testing.md §3.3）");

        long configured = partsTotal(Boolean.TRUE);
        long unconfigured = partsTotal(Boolean.FALSE);
        System.out.printf("[TQ-05] 全部=%d 已配齐=%d 未配齐=%d%n", all, configured, unconfigured);

        assertEquals(all, configured + unconfigured,
                "AC-25：已配齐(" + configured + ") + 未配齐(" + unconfigured
                        + ") 应等于全部(" + all + ")");

        // 互不相交：两个子集的轴值集合无交集。
        // 🚫 少了这条，「两边都返回全集」也能让上面的等式在 all=0 时成立。
        Set<String> a = partsAxisValues(Boolean.TRUE);
        Set<String> b = partsAxisValues(Boolean.FALSE);
        Set<String> overlap = new java.util.LinkedHashSet<>(a);
        overlap.retainAll(b);
        assertTrue(overlap.isEmpty(),
                "AC-25：已配齐与未配齐不该有交集，重复出现的料号：" + overlap);

        assertFalse(a.isEmpty() && b.isEmpty(),
                "AC-25：两个子集都为空 ⇒ 不变量在空集上恒成立，等于没验");
    }

    private long partsTotal(Boolean configured) {
        var req = io.restassured.RestAssured.given().cookie("CPQ_SESSION", adminSession())
                .queryParam("page", 0).queryParam("size", 1);
        if (configured != null) {
            req = req.queryParam("configured", configured);
        }
        Response r = req.when().get("/api/cpq/dataset/{dataset}/parts", DatasetApi.COST_BASIC);
        assertStatus(r, 200, "AC-25 /parts?configured=" + configured);
        Object total = r.jsonPath().get("data.total");
        assertNotNull(total, "AC-25：/parts 未返回 data.total。响应：" + r.asString());
        return ((Number) total).longValue();
    }

    private Set<String> partsAxisValues(Boolean configured) {
        var req = io.restassured.RestAssured.given().cookie("CPQ_SESSION", adminSession())
                .queryParam("page", 0).queryParam("size", 500);
        if (configured != null) {
            req = req.queryParam("configured", configured);
        }
        Response r = req.when().get("/api/cpq/dataset/{dataset}/parts", DatasetApi.COST_BASIC);
        assertStatus(r, 200, "AC-25 /parts?configured=" + configured);
        List<String> items = r.jsonPath().getList("data.items.axisValue");
        return items == null ? Set.of() : new java.util.LinkedHashSet<>(items);
    }

    // ═══════════════ 助手 ═══════════════

    /** 从 {@code GET /sheets} 拿 sheetName → sheetKey 的映射（api.md §2），不猜实现里的枚举名。 */
    private String resolveSheetKey(String sheetName) {
        Response r = DatasetApi.sheets(adminSession(), DatasetApi.COST_BASIC);
        assertStatus(r, 200, "api.md §2 GET /sheets");
        List<Map<String, Object>> sheets = r.jsonPath().getList("data.sheets");
        assertNotNull(sheets, "GET /sheets 缺 data.sheets");
        assertFalse(sheets.isEmpty(), "GET /sheets 返回空 ⇒ 后续断言空跑");
        return sheets.stream()
                .filter(m -> sheetName.equals(String.valueOf(m.get("sheetName"))))
                .map(m -> String.valueOf(m.get("sheetKey")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "GET /sheets 里没有 sheetName=" + sheetName + "，实际：" + sheets));
    }

    /** 去掉 {@code role=NAME} 的只读列与 {@code row_fingerprint}（api.md §7 明确前端不回传）。 */
    private List<Map<String, Object>> stripServerOnlyFields(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(r);
            copy.remove("row_fingerprint");
            copy.keySet().removeIf(k -> k.endsWith("_name"));
            out.add(copy);
        }
        return out;
    }

    private Map<String, Long> datasetCounts(String dataset) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (dataset.equals(s.dataset)) {
                m.put(s.tableName, countRows(s.tableName, null));
                if (s.versioned) {
                    m.put(s.historyTableName(), countRows(s.historyTableName(), null));
                }
            }
        }
        return m;
    }

    private Response importCostBasic(Consumer<DatasetFixtureBuilder> mutate, String name) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC, b.toBytes(), name);
        } finally {
            b.close();
        }
    }

    private Object scalarOrNull(String sql) {
        List<Object> l = col(sql);
        return l.isEmpty() ? null : l.get(0);
    }

    private void assertSummaryAxisCount(Response r, String sheet, int expected, String ac) {
        List<Map<String, Object>> summary = r.jsonPath().getList("data.summary");
        assertNotNull(summary, ac + "：缺 data.summary");
        Map<String, Object> item = summary.stream()
                .filter(m -> sheet.equals(String.valueOf(m.get("sheet"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ac + "：summary 里没有 " + sheet + "，实际 " + summary));
        Object v = item.get("axisCount");
        assertNotNull(v, ac + "：summary[" + sheet + "] 缺 axisCount");
        assertEquals(expected, ((Number) v).intValue(),
                ac + "：summary[" + sheet + "].axisCount 期望 " + expected + "，实际 " + v);
    }

    private void assertStatus(Response r, int expected, String ac) {
        if (r.statusCode() == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷。响应：" + r.asString());
        }
        assertEquals(expected, r.statusCode(), ac + "：HTTP 状态码不符。响应体：" + r.asString());
    }
}
