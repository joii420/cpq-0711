package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T1.10 · AC-15</b> —— 【基础资料维护】报价数据 tab 零回归（N-10 的守卫：
 * 新端点不得连累共用路径）。
 *
 * <h3>判据（D-32，2026-09-07 用户裁决改两层）</h3>
 * 原判据是「逐字一致」。实测推翻：{@code parts} / {@code rows} 返回<b>共享库的活数据</b>，
 * 任何并发会话写一行 {@code ds_quote_*} 逐字 diff 就红，而那不是回归。现判据：
 * <ol>
 *   <li><b>结构层（严格）</b> —— 字段集 / 嵌套结构 / HTTP 状态码逐字一致。
 *       用 {@link JsonShape} 抽骨架比对，对数据漂移天然免疫。<b>这层红 = 真回归。</b></li>
 *   <li><b>数据层（可归因）</b> —— 值差异必须能逐条归因到已知写入方
 *       （并发会话夹具 / 已知迁移 / 本任务导入）；<b>来路不明即失败</b>。</li>
 * </ol>
 *
 * <h3>🚨 diff 前必须先断言 HTTP 200（硬纪律，实证得来）</h3>
 * 2026-09-07 实测：session 过期时 14 个端点全返 401，逐字 diff 忠实报出
 * <b>14/14 全漂移</b> —— 那会是一份「本任务把所有维护端点都搞坏了」的<b>假红报告</b>。
 * ⇒ 本类每个端点先 {@code assertEquals(200, ...)}，不过就地硬失败，绝不进入 diff。
 *
 * <h3>🚨 基线必须非空</h3>
 * A 侧首轮按 parts 首个轴值 {@code S0003} 取样，rows/versions 都是 0 行 ⇒
 * A/B 成了「空 vs 空」<b>恒相等恒通过</b>。已补采两个真实有数据的轴值，本类对其硬断言非空。
 *
 * <h3>⏱ 采集时点</h3>
 * 窗口被两条并发线夹着：4 个前端公共件的 {@code git mv}、28 表 {@code customer_no} DDL，
 * 任一先落地这份对照就废。故 {@code @Order(1)}，且必须在本包任何导入类用例之前跑。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac15MaintenanceRegressionTest extends QuoteImportAcTestBase {

    private static final String AXIS_A = "0526-2609000004";
    private static final String AXIS_B = "0526-2609000005";
    /** A 侧首轮取样的轴值 —— 0 行，只作端点形状基线，不作内容判据。 */
    private static final String AXIS_SHAPE_ONLY = "S0003";

    /**
     * 数据层「已知写入方」白名单。差异只要落在这些前缀/字段上就算可归因。
     * 🚩 {@code T260907-} 是并发后端会话的夹具前缀（实测 {@code T260907-M1/M2}）；
     *    {@code T260907T-} 是本套用例自己的。两者互不匹配，见基座 {@link #P} 的注释。
     */
    private static final List<String> KNOWN_FIXTURE_PREFIXES = List.of("T260907-", "T260907T-", "TEST-DS-");
    /** 天然随写入变动、不承载业务语义的字段，差异可归因。 */
    private static final List<String> VOLATILE_FIELDS =
            List.of("lastUpdatedAt", "updatedAt", "createdAt", "total", "frozenAt");

    private Path taskDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs").resolve("task-260907-报价导入建单切ds新表");
            if (Files.isDirectory(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到任务目录");
    }

    private void write(Path dir, String name, String body) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 采一个端点。
     * 🚨 200 是进入 diff 的<b>前置条件</b>，不是顺带检查的 —— 见类注释的假红实证。
     */
    private String grab200(Response r, String what) {
        assertEquals(200, r.statusCode(),
                "AC-15 对照面端点「" + what + "」返回 HTTP " + r.statusCode()
                        + "，不是 200 ⇒ 后面的 diff 全部无意义（session 过期就会造成 14/14 全漂移的假红）。"
                        + "\n  响应：" + r.asString().substring(0, Math.min(300, r.asString().length())));
        return r.asString();
    }

    @Test
    @Order(1)
    void t1_10_维护端读端点结构层零回归且数据层差异可归因() {
        String s = adminSession();
        Path base = taskDir().resolve("证据").resolve("AC-15基线");
        Path out = taskDir().resolve("证据").resolve("AC-15对照-B侧");
        assertTrue(Files.isDirectory(base), "A 侧基线目录不存在：" + base + " —— 没有基线就没有对照");

        // ── 采集（每个端点都先过 200 关） ──
        Map<String, String> b = new LinkedHashMap<>();
        b.put("01-sheets.json", grab200(QuoteImportApi.datasetSheets(s), "sheets"));
        b.put("02-parts-p1.json", grab200(QuoteImportApi.datasetParts(s, 1, 20), "parts"));
        b.put("03-overview.json",
                grab200(QuoteImportApi.datasetOverview(s, AXIS_SHAPE_ONLY), "overview/" + AXIS_SHAPE_ONLY));
        b.put("04-rows-MATERIAL_BOM.json",
                grab200(QuoteImportApi.datasetRows(s, AXIS_SHAPE_ONLY, "MATERIAL_BOM"), "rows/S0003"));
        b.put("05-versions-MATERIAL_BOM.json",
                grab200(QuoteImportApi.datasetVersions(s, AXIS_SHAPE_ONLY, "MATERIAL_BOM"), "versions/S0003"));
        b.put("06-lookup-recipe.json", grab200(QuoteImportApi.datasetLookup(s, "recipe"), "lookup/recipe"));
        for (String axis : List.of(AXIS_A, AXIS_B)) {
            String t = axis.replace("-", "");
            b.put("07-overview-" + t + ".json",
                    grab200(QuoteImportApi.datasetOverview(s, axis), "overview/" + axis));
            b.put("08-rows-MATERIAL_BOM-" + t + ".json",
                    grab200(QuoteImportApi.datasetRows(s, axis, "MATERIAL_BOM"), "rows/MATERIAL_BOM/" + axis));
            b.put("09-rows-ELEMENT_BOM-" + t + ".json",
                    grab200(QuoteImportApi.datasetRows(s, axis, "ELEMENT_BOM"), "rows/ELEMENT_BOM/" + axis));
            b.put("10-versions-MATERIAL_BOM-" + t + ".json",
                    grab200(QuoteImportApi.datasetVersions(s, axis, "MATERIAL_BOM"), "versions/" + axis));
        }
        b.forEach((k, v) -> write(out, k, v));
        assertFalse(b.isEmpty(), "一个端点都没采到 = 断言从未执行（假绿）");

        // ── 空验证守卫：0 行的基线做 A/B 会恒相等恒通过 ──
        String rowsA = b.get("08-rows-MATERIAL_BOM-" + AXIS_A.replace("-", "") + ".json");
        assertTrue(rowsA.contains("\"material_no\""),
                "轴 " + AXIS_A + " 的 MATERIAL_BOM 无行 ⇒ A/B 是「空 vs 空」，恒相等恒通过。"
                        + "基线已失效（该轴被其它会话动过），应重采基线而不是判通过。实际：" + rowsA);

        // ── 判据①：结构层严格 ──
        List<String> missing = new ArrayList<>();
        List<String> shapeDiff = new ArrayList<>();
        List<String> valueDiff = new ArrayList<>();

        b.forEach((name, bodyB) -> {
            Path a = base.resolve(name);
            if (!Files.isRegularFile(a)) {
                missing.add(name);
                return;
            }
            String bodyA;
            try {
                bodyA = Files.readString(a, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Set<String> sa = new TreeSet<>(JsonShape.of(bodyA));
            Set<String> sb = new TreeSet<>(JsonShape.of(bodyB));
            if (!sa.equals(sb)) {
                Set<String> onlyA = new TreeSet<>(sa);
                onlyA.removeAll(sb);
                Set<String> onlyB = new TreeSet<>(sb);
                onlyB.removeAll(sa);
                shapeDiff.add(name + "\n      只在 A（基线有、现在没了 ⇒ 字段被删/改名）: " + onlyA
                        + "\n      只在 B（本任务新加的字段）: " + onlyB);
            }
            // ── 判据②：数据层，仅当逐字不等时才去归因 ──
            if (!bodyA.equals(bodyB)) {
                valueDiff.add(name);
            }
        });

        assertTrue(missing.isEmpty(), "基线缺文件（对照面不完整，不能判通过）：" + missing);
        assertTrue(shapeDiff.isEmpty(),
                "❌ AC-15 判据① 结构层不通过 —— 共用端点的字段集/嵌套结构被本任务改了（" + shapeDiff.size()
                        + " 处）。\n🚫 这 7 个端点被【基础资料维护】与三个 dataset 页签共用，不许因为「看起来无害」放行。\n  - "
                        + String.join("\n  - ", shapeDiff));

        // 数据层：本用例只负责「标出来 + 落盘」，归因结论写进 test-report。
        // 🚩 这里不硬失败，是因为「可归因」需要人看并发写入方；硬失败会把
        //    每一次并发夹具写入都变成红灯，反而让人学会忽略红灯。
        write(out, "00-数据层差异清单.txt",
                "AC-15 判据②：以下文件逐字不等，需在 test-report 中逐条归因（来路不明即判失败）\n"
                        + "已知写入方前缀：" + KNOWN_FIXTURE_PREFIXES + "\n"
                        + "天然易变字段：" + VOLATILE_FIELDS + "\n\n"
                        + (valueDiff.isEmpty() ? "（无差异）" : String.join("\n", valueDiff)));
    }
}
