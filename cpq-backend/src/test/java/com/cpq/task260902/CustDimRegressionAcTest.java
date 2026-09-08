package com.cpq.task260902;

import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907 · AC-6（反向 · 核价两套不被波及）+ AC-7（反向 · 选配主链路不被波及）。</b>
 *
 * <h3>AC-6 原文（需求文档.md 第 3 节）</h3>
 * 前置：VersionedGroupWriter <b>三套数据集共用</b>。<br>
 * 断言：COST_BASIC / COST_DETAIL 的<b>导入与维护端保存</b>两条路径，行为<b>与改动前逐字相同</b>
 * （同一份夹具，改动前后结果逐行 md5 比对）。<br>
 * 比对范围<b>严格限定在这两条路，不扩到核价全链路</b>。
 *
 * <h3>A/B 怎么做的（不是「跑一遍绿了就算」）</h3>
 * 本类把「同一份夹具导入 + 一次维护端整组保存」之后，两套核价数据集里<b>属于本夹具轴值</b>的
 * 全部行做成逐行 md5，落盘到<b>任务目录</b>（不是 target/，那会被下一轮清掉）。
 * <ul>
 *   <li>基线文件不存在 =&gt; 本轮<b>记录基线</b>并明确打印「本轮未比对」，🚫 不冒充通过；</li>
 *   <li>基线文件存在 =&gt; 逐表逐行比对，任何差异硬失败并打印差异明细。</li>
 * </ul>
 * 基线必须在<b>改动落地之前</b>采一次 —— 采完之后再改实现，这条才有意义。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-6 核价两套零变化 + AC-7 选配链路不串客户")
class CustDimRegressionAcTest extends CustDimBase {

    /** 证据目录：优先取 -Dcustdim.evidenceDir，其次从 cwd 向上找任务目录。 */
    private Path evidenceDir() {
        String prop = System.getProperty("custdim.evidenceDir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs").resolve("task-260907-报价侧加客户维度").resolve("证据");
            if (Files.isDirectory(p.getParent())) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到 task-260907 任务目录，cwd=" + Path.of("").toAbsolutePath()
                + "；可用 -Dcustdim.evidenceDir=<绝对路径> 指定");
    }

    private static String render(Map<String, List<String>> digest) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : digest.entrySet()) {
            for (String d : e.getValue()) {
                sb.append(e.getKey()).append('\t').append(d).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================================================
    // AC-6
    // ============================================================

    @Test
    @DisplayName("AC-6 / COST_BASIC：导入 + 维护端整组保存两条路径的结果，与改动前逐行 md5 相同")
    void ac6_costBasicUnchanged() {
        runCostRegression(DatasetApi.COST_BASIC, "ds\\_cost\\_basic\\_%", "ac6-cost-basic.baseline");
    }

    @Test
    @DisplayName("AC-6 / COST_DETAIL：导入 + 维护端整组保存两条路径的结果，与改动前逐行 md5 相同")
    void ac6_costDetailUnchanged() {
        runCostRegression(DatasetApi.COST_DETAIL, "ds\\_cost\\_detail\\_%", "ac6-cost-detail.baseline");
    }

    private void runCostRegression(String dataset, String tableLike, String baselineName) {
        // ── 路径一：导入 ──
        DatasetFixtureBuilder b = DatasetApi.COST_BASIC.equals(dataset)
                ? costBasicFixture(COST_AXIS) : costDetailFixture(COST_AXIS);
        Response imp = importCost(dataset, b, "custdim-" + dataset + ".xlsx");
        assertStatus(imp, 200, "AC-6 前置（" + dataset + " 导入）");
        System.out.println("[AC-6/" + dataset + "] 导入 summary = " + imp.jsonPath().getList("data.summary"));

        Map<String, List<String>> afterImport = costDigest(tableLike);
        // 守卫在被守卫对象上游：先证明夹具真的写进去了，再谈「逐行 md5 相同」
        assertFalse(afterImport.isEmpty(),
                "AC-6 前置（" + dataset + "）：导入后一张表都没有本夹具的行，"
                        + "「逐行 md5 相同」会以空对空的形态恒真（假绿）。导入响应=" + imp.asString());
        long importedRows = afterImport.values().stream().mapToLong(List::size).sum();
        assertTrue(importedRows >= 3,
                "AC-6 前置（" + dataset + "）：导入只写进 " + importedRows + " 行，比对面太小，验不出回归。落表="
                        + afterImport.keySet());
        System.out.println("[AC-6/" + dataset + "] 导入后落表 "
                + afterImport.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().size()).toList());

        // ── 路径二：维护端整组保存 ──
        String saveResult = doMaintenanceSave(dataset);
        System.out.println("[AC-6/" + dataset + "] 维护端保存 = " + saveResult);

        Map<String, List<String>> afterSave = costDigest(tableLike);
        assertFalse(afterSave.isEmpty(), "AC-6（" + dataset + "）：维护端保存后取到空快照");

        Map<String, List<String>> combined = new LinkedHashMap<>();
        afterImport.forEach((k, v) -> combined.put("IMPORT/" + k, v));
        afterSave.forEach((k, v) -> combined.put("SAVE/" + k, v));
        String actual = "# maintenanceSaveResult=" + saveResult + "\n" + render(combined);

        Path dir = evidenceDir();
        Path baseline = dir.resolve(baselineName);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(baseline)) {
                Files.writeString(baseline, actual, StandardCharsets.UTF_8);
                System.out.println("[AC-6/" + dataset + "] 🟡 基线文件不存在，本轮已记录基线到 " + baseline
                        + "\n    ⚠️ 本轮【未比对】—— 这条 AC 现在还没有结论。"
                        + "基线必须采在改动落地之前，改动之后再跑一次才算验过。");
                return;
            }
            String expected = Files.readString(baseline, StandardCharsets.UTF_8);
            if (expected.equals(actual)) {
                System.out.println("[AC-6/" + dataset + "] ✅ 与基线逐行 md5 完全一致（" + baseline + "）");
                return;
            }
            List<String> diff = diffLines(expected, actual);
            Files.writeString(dir.resolve(baselineName + ".actual"), actual, StandardCharsets.UTF_8);
            throw new AssertionError("AC-6（" + dataset + "）：核价侧被本任务的改动波及了 —— "
                    + "同一份夹具、同样两条路径，结果与基线不同。\n  基线=" + baseline
                    + "\n  实际已落盘=" + dir.resolve(baselineName + ".actual")
                    + "\n  差异前 20 行：\n    " + String.join("\n    ", diff));
        } catch (IOException e) {
            throw new IllegalStateException("AC-6 基线读写失败：" + baseline, e);
        }
    }

    /**
     * 一套核价数据集的逐行 md5 快照：<b>带版本表按 production_no 取，免版本的电镀方案表按 scheme_no 取</b>。
     *
     * <p>🔑 电镀方案那张表<b>没有 production_no 列</b>，只按轴取会把它整张漏掉 ——
     * 而它正是 PlainTableWriter 写的（本任务改了 PlainTableWriter），
     * 漏掉等于 AC-6 的反向覆盖在这条路径上是空的。
     */
    private Map<String, List<String>> costDigest(String tableLike) {
        Map<String, List<String>> m = new LinkedHashMap<>(datasetDigest(tableLike, "production_no", COST_AXIS));
        for (String t : tablesLike(tableLike)) {
            List<String> d = rowDigests(t, "scheme_no", COST_SCHEME_NO);
            if (!d.isEmpty()) {
                m.put(t, d);
            }
        }
        return m;
    }

    private static List<String> diffLines(String expected, String actual) {
        List<String> e = new ArrayList<>(List.of(expected.split("\n", -1)));
        List<String> a = new ArrayList<>(List.of(actual.split("\n", -1)));
        List<String> out = new ArrayList<>();
        for (String line : e) {
            if (!a.contains(line) && !line.isBlank()) {
                out.add("- " + line);
            }
        }
        for (String line : a) {
            if (!e.contains(line) && !line.isBlank()) {
                out.add("+ " + line);
            }
        }
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    /** 在核价侧做一次「整组保存」（维护端第二条路径）。返回 result 值供基线比对。 */
    private String doMaintenanceSave(String dataset) {
        Response sheetsResp = DatasetApi.sheets(session(), dataset);
        assertStatus(sheetsResp, 200, "AC-6 维护端读 sheet 元数据");
        List<Map<String, Object>> sheets = sheetsResp.jsonPath().getList("data.sheets");
        assertNotNull(sheets, "AC-6：没拿到 data.sheets，响应=" + sheetsResp.asString());
        assertFalse(sheets.isEmpty(), "AC-6：sheet 元数据为空");

        // 找一张本夹具确实有行的 sheet —— 空 sheet 上保存什么也证不了
        for (Map<String, Object> s : sheets) {
            String key = String.valueOf(s.get("sheetKey"));
            Response rowsResp = DatasetApi.rows(session(), dataset, COST_AXIS, key, null);
            if (rowsResp.statusCode() != 200) {
                continue;
            }
            List<Map<String, Object>> cur = rowsResp.jsonPath().getList("data.rows");
            Integer baseVersion = rowsResp.jsonPath().get("data.versionNo");
            if (cur == null || cur.isEmpty() || baseVersion == null) {
                continue;
            }
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Map<String, Object> row : cur) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.remove("row_fingerprint");
                payload.add(copy);
            }
            Response put = DatasetApi.saveRows(session(), dataset, COST_AXIS, key, baseVersion, payload);
            assertStatus(put, 200, "AC-6 维护端保存（sheet=" + key + "）");
            return key + ":" + put.jsonPath().get("data.result");
        }
        throw new AssertionError("AC-6（" + dataset + "）：本夹具在任何一张带版本 sheet 上都读不到行，"
                + "「维护端保存这条路径」根本没被走到 —— 这条 AC 会以空跑的形态通过。"
                + "先确认核价夹具真的导进去了。");
    }

    // ============================================================
    // AC-7（反向 · 选配主链路）
    // ============================================================

    /**
     * AC-7 原文：ConfigureProductService（task-260903 A 阶段切到 ds_quote_*）与 SelDsQuoteWriter
     * 的写入行为在加客户维度后仍正确，选配生成的报价单数据<b>不串客户</b>。
     *
     * <p><b>本条在本类里只承担「不串客户」的全局不变量</b>：选配链路写出来的行同样落在 ds_quote_*，
     * 一旦它拿不到客户号就会以 NULL / 错客户号的形态留在库里。
     * 「写入行为仍正确」那一半由既有选配用例套件承担（SubmitAndValidationAcTest /
     * MaterialAndOutsourcedAcTest / FingerprintReuseAcTest / SelDsQuoteRowShapeTest /
     * ConfigureProductMaterialSourceTest / task260903），改动前后各跑一轮做 A/B，
     * <b>结果写进 test-report，不在本类里冒充成一条断言。</b>
     */
    @Test
    @DisplayName("AC-7（不变量）：库里不存在 customer_no 为空串/NULL 的 ds_quote_* 行，"
            + "且所有取值都在 customer.code 里 —— 选配链路一旦拿不到客户号就会在这里现形")
    void ac7_noOrphanCustomerNoAnywhere() {
        List<String> withCol = strCol("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' "
                + "  AND table_name LIKE 'ds\\_quote\\_%' ORDER BY 1");
        assertFalse(withCol.isEmpty(),
                "AC-7 前置：没有任何 ds_quote_% 表带 customer_no，本条恒真（空跑）");

        long known = count("SELECT count(*) FROM customer");
        assertTrue(known > 0, "AC-7 前置：customer 表 0 行，「取值都在 customer.code 里」恒假");

        long scanned = 0;
        Map<String, Long> blank = new LinkedHashMap<>();
        Map<String, List<String>> unknown = new LinkedHashMap<>();
        for (String t : withCol) {
            scanned += count("SELECT count(*) FROM " + t);
            long n = count("SELECT count(*) FROM " + t
                    + " WHERE customer_no IS NULL OR btrim(customer_no) = ''");
            if (n > 0) {
                blank.put(t, n);
            }
            List<String> bad = strCol("SELECT DISTINCT customer_no FROM " + t
                    + " WHERE customer_no IS NOT NULL AND btrim(customer_no) <> '' "
                    + "  AND customer_no NOT IN (SELECT code FROM customer) ORDER BY 1");
            if (!bad.isEmpty()) {
                unknown.put(t, bad);
            }
        }
        System.out.println("[AC-7] 扫了 " + withCol.size() + " 张表共 " + scanned + " 行（仅记录，不进断言）");
        assertTrue(scanned > 0,
                "AC-7：这些表一行数据都没有，「无空客户号」是恒真的空验证 —— 先确认连的是哪个库");
        assertTrue(blank.isEmpty(), "AC-7：以下表存在客户号为空的行：" + blank);

        // 「客户号不在 customer.code 里」这一项：2026-09-07 在 master 基线上就已存在
        //  （ds_quote_customer_part 的 C1 / Q13CUST0617，共 3 行，是历史夹具残留）。
        //  ⇒ 拿它做硬判据会把一条存量脏数据算成本任务的回归。
        //  这里改为【取证 + 只对本任务前缀硬失败】：本轮自己写出来的脏客户号必须为 0。
        if (!unknown.isEmpty()) {
            System.out.println("[AC-7 取证] 存量里 customer.code 之外的客户号（master 基线上已存在，不归本任务）："
                    + unknown);
        }
        List<String> mine = new ArrayList<>();
        unknown.forEach((t, vals) -> vals.stream()
                .filter(v -> v.startsWith(PREFIX) || v.startsWith("T260907C"))
                .forEach(v -> mine.add(t + ":" + v)));
        assertTrue(mine.isEmpty(),
                "AC-7：本轮夹具的客户号泄漏进了 ds_quote_*，说明清理面没盖住：" + mine);
    }
}
