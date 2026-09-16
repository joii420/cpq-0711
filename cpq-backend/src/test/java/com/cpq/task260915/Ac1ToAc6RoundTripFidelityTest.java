package com.cpq.task260915;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-1 / AC-2 / AC-3 / AC-6</b> —— 源目录 → 导出 → 导入新目录，逐字段保真。
 *
 * <h3>AC 原文（需求文档.md §③）</h3>
 * <ul>
 *   <li><b>AC-1</b> 前置：对目录「取值配置器测试」执行导出；操作：导入到新建目录；
 *       断言：新目录组件的 {@code component_sql_view.builder_config} 与源目录<b>逐字节相等</b>
 *       （{@code builder_config::text} 相同），{@code builder_version} 数值相同；
 *       AC 给出的判据 SQL 返回 <b>0 行</b>：
 *       {@code … WHERE src.builder_config::text IS DISTINCT FROM dst.builder_config::text
 *              OR src.builder_version IS DISTINCT FROM dst.builder_version}。</li>
 *   <li><b>AC-2</b> 源目录中 {@code element_code_field} 非空的组件，其
 *       {@code element_code_field} / {@code element_price_field} / {@code element_currency_field}
 *       三列与源<b>完全相同</b>（<b>含 NULL 保持 NULL，不得被空串替代</b>）。</li>
 *   <li><b>AC-3</b> 源目录 {@code bom_recursive_expand=true} 的组件导入后仍为 {@code true}，
 *       其余仍为 {@code false}；<b>计数断言</b>：新目录
 *       {@code count(*) FILTER (WHERE bom_recursive_expand)} 等于源目录的同一计数。</li>
 *   <li><b>AC-6</b> 源目录与新目录按组件配对，{@code component} 表除白名单外的列、
 *       {@code component_sql_view} 表除白名单外的列，<b>逐列比对差异行数为 0</b>。</li>
 * </ul>
 *
 * <h3>本类相对 AC 原文的三处偏离（全部已在 test-report 上报主线）</h3>
 * <ol>
 *   <li><b>配对键不用 {@code code}。</b> AC-1 的判据 SQL 写的是 {@code FULL JOIN … USING (code)}，
 *       但 {@code component_code_key} 是<b>全局唯一索引</b>（实查 {@code pg_indexes}）⇒ 导入必然 RENAME，
 *       {@code code} 必然不同，按 code 配对会得到「两边各 N 行、全是单边行」。
 *       ⇒ 改用提交响应的 {@code created[].originalCode → componentId} 配对。
 *       这与 AC-6 白名单里「{@code code}（RENAME 策略下允许不同）」的口径一致。</li>
 *   <li><b>组件个数不写死。</b> AC 原文的「6 个组件 / 2 个 true / 其余 8 个 / 1 个有 element_code_field」
 *       是 {@code cpq_db_0724} 的实查值；{@code cpq_db_test} 实查为
 *       <b>9 个组件 / 5 个 builder 视图 / 2 个 true / 1 个有 element_code_field</b>。
 *       ⇒ 期望值一律<b>执行期从源目录现算</b>，只断言「两边相等」+「样本非空」，实测数字 println 供报告记录。</li>
 *   <li><b>AC-6 的列数对不上。</b> AC 原文写 {@code component_sql_view} 「除白名单外 10 列」，
 *       实查两库均为 14 列 − 5 白名单 = <b>9 列</b>。本类按现算列比对，不按文档数字。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-1/2/3/6 往返逐字段保真")
class Ac1ToAc6RoundTripFidelityTest extends Task260915Base {

    @Test
    @DisplayName("AC-1 builder_config / builder_version 往返逐字节相等（AC 判据 SQL 返 0 行）")
    void ac1_builderConfigAndVersionSurviveRoundTrip() {
        final String AC = "AC-1";
        UUID src = sourceDirectoryId();

        // 🚨 防空跑：源目录里必须真有 builder 视图，否则「逐字节相等」的断言没有输入（testing.md §3）。
        long builderViews = count("SELECT count(*) FROM component_sql_view v"
                + " JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + src + "'::uuid AND v.builder_config IS NOT NULL");
        assertTrue(builderViews > 0, AC + " 前置未满足：源目录「" + SOURCE_DIR_NAME + "」里 0 个带 builder_config 的视图 "
                + "⇒ 本条 AC 的断言会 0 次循环，测试照样绿（假绿）。");
        System.out.println("[" + AC + "] 源目录 builder 视图数（执行期现算）= " + builderViews);

        RoundTrip rt = roundTrip(src, "AC1-M", AC);

        // ── 判据 A：AC 原文那条 SQL，按 (源组件 id, sql_view_name) 做 FULL JOIN，必须 0 行 ──
        String pairs = pairValues(src, rt);
        String sql =
                "WITH pairs(k, dst) AS (VALUES " + pairs + "),\n"
              + "     s AS (SELECT p.k AS k, v.sql_view_name AS vn, v.builder_config::text AS bc, v.builder_version AS bv\n"
              + "             FROM pairs p JOIN component_sql_view v ON v.component_id = p.k),\n"
              + "     d AS (SELECT p.k AS k, v.sql_view_name AS vn, v.builder_config::text AS bc, v.builder_version AS bv\n"
              + "             FROM pairs p JOIN component_sql_view v ON v.component_id = p.dst)\n"
              + "SELECT COALESCE(s.k::text, d.k::text) AS pair_key, COALESCE(s.vn, d.vn) AS view_name,\n"
              + "       s.bv AS src_bv, d.bv AS dst_bv,\n"
              + "       left(COALESCE(s.bc,'<NULL>'), 200) AS src_bc, left(COALESCE(d.bc,'<NULL>'), 200) AS dst_bc\n"
              + "  FROM s FULL JOIN d USING (k, vn)\n"
              + " WHERE s.bc IS DISTINCT FROM d.bc OR s.bv IS DISTINCT FROM d.bv";
        List<Object[]> bad = rows(sql);
        assertTrue(bad.isEmpty(), AC + "：AC 原文的判据 SQL 应返 0 行，实际 " + bad.size() + " 行 ——"
                + "\n  （FULL JOIN 会把「只有一侧有这条视图」也算进来：src_bc=<NULL> 且该行是 d 单边 ⇒ 视图整条没导进来）"
                + "\n" + dump(bad, "pair_key", "view_name", "src_bv", "dst_bv", "src_bc", "dst_bc"));

        // ── 判据 B：逐对显式比对（把「真的比了 N 对」打出来，防上面那条 SQL 因 pairs 为空而恒 0 行）──
        int compared = 0, nonNullCfg = 0;
        List<String> viewCols = List.of("sql_view_name", "builder_config", "builder_version");
        for (Map.Entry<String, UUID> e : srcIdByCode(src, rt).entrySet()) {
            UUID s = e.getValue();
            UUID d = rt.dstIdByOrigCode().get(e.getKey());
            Map<String, Map<String, String>> sv = viewsOf(s, viewCols);
            Map<String, Map<String, String>> dv = viewsOf(d, viewCols);
            assertEquals(sv.keySet(), dv.keySet(), AC + "：组件 " + e.getKey()
                    + " 的视图名集合两边不等 ⇒ 视图被丢/被改名，无法配对（sql_view_name 本身也是应保真的字段）。");
            for (String vn : sv.keySet()) {
                compared++;
                String sbc = sv.get(vn).get("builder_config");
                String dbc = dv.get(vn).get("builder_config");
                if (sbc != null) nonNullCfg++;
                assertEquals(sbc, dbc, AC + "：" + e.getKey() + "/" + vn + " 的 builder_config 不是逐字节相等。"
                        + "\n  源=" + brief(sbc) + "\n  新=" + brief(dbc));
                assertEquals(sv.get(vn).get("builder_version"), dv.get(vn).get("builder_version"),
                        AC + "：" + e.getKey() + "/" + vn + " 的 builder_version 不同。");
            }
        }
        assertTrue(compared > 0, AC + "：一对视图都没比到 ⇒ 断言从未执行（假绿）。");
        assertTrue(nonNullCfg > 0, AC + "：比到了 " + compared + " 对视图，但没有一对的源 builder_config 非空 "
                + "⇒ 全是「NULL 等于 NULL」，等于没验（testing.md §3）。");
        System.out.println("[" + AC + "] ✅ 比对视图 " + compared + " 对，其中源 builder_config 非空 " + nonNullCfg + " 对。");
    }

    @Test
    @DisplayName("AC-2 元素价格三列往返相等，且 NULL 保持 NULL（不得被空串替代）")
    void ac2_elementPriceColumnsSurviveRoundTrip() {
        final String AC = "AC-2";
        UUID src = sourceDirectoryId();

        // 🚨 防空跑：AC 前置写的是「源目录中 element_code_field 非空的组件」——没有就没有输入。
        long withCode = count("SELECT count(*) FROM component WHERE directory_id='" + src
                + "'::uuid AND element_code_field IS NOT NULL");
        assertTrue(withCode > 0, AC + " 前置未满足：源目录里 0 个组件的 element_code_field 非空 "
                + "⇒ 「接价格策略的组件字段保真」这条断言没有输入（需求文档 §② 记载实查为 1 个）。");
        System.out.println("[" + AC + "] 源目录 element_code_field 非空组件数（现算）= " + withCode);

        RoundTrip rt = roundTrip(src, "AC2-M", AC);

        List<String> cols = List.of("element_code_field", "element_price_field", "element_currency_field");
        int checked = 0, nonNullCases = 0, nullCases = 0;
        List<String> report = new ArrayList<>();
        for (Map.Entry<String, UUID> e : srcIdByCode(src, rt).entrySet()) {
            Map<String, String> s = rowText("component", cols, e.getValue());
            Map<String, String> d = rowText("component", cols, rt.dstIdByOrigCode().get(e.getKey()));
            for (String c : cols) {
                checked++;
                String sv = s.get(c), dv = d.get(c);
                if (sv == null) {
                    nullCases++;
                    // 「不得被空串替代」：这是 AC-2 点名的丢失形态，单独给它一条专用文案。
                    assertNull(dv, AC + "：" + e.getKey() + "." + c + " 源为 SQL NULL，导入后变成 "
                            + (dv != null && dv.isEmpty() ? "<空串 \"\">" : "「" + dv + "」")
                            + " ⇒ NULL 被替代，正是 AC-2 点名禁止的丢失形态。");
                } else {
                    nonNullCases++;
                    assertEquals(sv, dv, AC + "：" + e.getKey() + "." + c + " 往返后值变了。源=「" + sv + "」新=「" + dv + "」");
                }
            }
            report.add(e.getKey() + " → " + s);
        }
        assertTrue(checked > 0, AC + "：一个组件都没比到 ⇒ 断言从未执行。");
        // 🚨 两个分支都必须真的走到：全是 NULL 等于没验「值保真」，全是非 NULL 等于没验「NULL 保持 NULL」。
        assertTrue(nonNullCases > 0, AC + "：比了 " + checked + " 个 (组件×列)，但没有一个源值非空 "
                + "⇒ 只验了「NULL==NULL」，「值保真」这一半从未执行。");
        assertTrue(nullCases > 0, AC + "：比了 " + checked + " 个 (组件×列)，但没有一个源值为 NULL "
                + "⇒ 「NULL 不得被空串替代」这一半从未执行。");
        System.out.println("[" + AC + "] ✅ 比对 " + checked + " 个 (组件×列)：非空 " + nonNullCases
                + " 个 / NULL " + nullCases + " 个。源侧实际值：\n    " + String.join("\n    ", report));
    }

    @Test
    @DisplayName("AC-3 bom_recursive_expand 往返保真（逐对 + 目录内计数）")
    void ac3_bomRecursiveExpandSurvivesRoundTrip() {
        final String AC = "AC-3";
        UUID src = sourceDirectoryId();

        long srcTrue = count("SELECT count(*) FROM component WHERE directory_id='" + src
                + "'::uuid AND bom_recursive_expand");
        long srcFalse = count("SELECT count(*) FROM component WHERE directory_id='" + src
                + "'::uuid AND NOT bom_recursive_expand");
        // 🚨 两个分支都要有样本：全 false 时「导入后仍为 false」恒成立，等于没验。
        assertTrue(srcTrue > 0, AC + " 前置未满足：源目录里 0 个 bom_recursive_expand=true 的组件 "
                + "⇒ 「true 仍为 true」这一半没有输入（需求文档记载实查为 2 个）。");
        assertTrue(srcFalse > 0, AC + " 前置未满足：源目录里 0 个 bom_recursive_expand=false 的组件 "
                + "⇒ 「false 仍为 false」这一半没有输入。");
        System.out.println("[" + AC + "] 源目录现算：true=" + srcTrue + " / false=" + srcFalse);

        RoundTrip rt = roundTrip(src, "AC3-M", AC);

        // 逐对比对
        for (Map.Entry<String, UUID> e : srcIdByCode(src, rt).entrySet()) {
            String s = rowText("component", List.of("bom_recursive_expand"), e.getValue()).get("bom_recursive_expand");
            String d = rowText("component", List.of("bom_recursive_expand"),
                    rt.dstIdByOrigCode().get(e.getKey())).get("bom_recursive_expand");
            assertEquals(s, d, AC + "：" + e.getKey() + " 的 bom_recursive_expand 往返后变了（源=" + s + " 新=" + d + "）。");
        }

        // 计数断言 —— 🚫 带 directory_id 限定，不是全局计数（testing.md §4.5）。
        long dstTrue = count("SELECT count(*) FROM component WHERE directory_id='" + rt.targetDir()
                + "'::uuid AND bom_recursive_expand");
        long dstFalse = count("SELECT count(*) FROM component WHERE directory_id='" + rt.targetDir()
                + "'::uuid AND NOT bom_recursive_expand");
        assertEquals(srcTrue, dstTrue, AC + "：新目录里 bom_recursive_expand=true 的组件数应等于源目录的 "
                + srcTrue + "，实际 " + dstTrue + "（BOM 树将不递归展开）。");
        assertEquals(srcFalse, dstFalse, AC + "：新目录里 false 的组件数应等于源目录的 " + srcFalse + "，实际 " + dstFalse + "。");
        System.out.println("[" + AC + "] ✅ 新目录 true=" + dstTrue + " / false=" + dstFalse + "（与源一致）。");
    }

    @Test
    @DisplayName("AC-6 全字段差集为空：component 与 component_sql_view 除白名单外逐列差异 0 行")
    void ac6_allColumnsDiffIsEmpty() {
        final String AC = "AC-6";
        UUID src = sourceDirectoryId();

        List<String> compCols = comparedColumns("component", COMPONENT_WHITELIST);
        List<String> viewCols = comparedColumns("component_sql_view", SQLVIEW_WHITELIST);
        System.out.println("[" + AC + "] 执行期现算待比对列：component " + compCols.size() + " 列 " + compCols
                + "\n              component_sql_view " + viewCols.size() + " 列 " + viewCols);
        // AC 原文写的是 18 / 10。18 对得上；10 对不上（实查 9）。⚠️ 不硬红，打成醒目 WARN 进报告，由主线裁决。
        if (compCols.size() != 18 || viewCols.size() != 10) {
            System.out.println("[" + AC + "] ⚠️ 与 AC-6 原文声明的列数不符：AC 写 component=18 / component_sql_view=10，"
                    + "实际现算 " + compCols.size() + " / " + viewCols.size()
                    + " ⇒ 需求文档的列数需更新。本类按现算列比对（比文档数字更可信），差异结论不受影响。");
        }

        // 🚨 证伪实验：先证明差异引擎能报出差异，再相信「0 差异」。
        assertDiffEngineIsWired(src, compCols, AC);

        RoundTrip rt = roundTrip(src, "AC6-M", AC);
        Map<String, UUID> srcIds = srcIdByCode(src, rt);

        // ── id 归一化表：跨组件引用（实查：COMP-2269.excel_columns 内嵌 COMP-2267/2268 的 UUID）会被导入端重映射 ──
        List<UUID> srcList = new ArrayList<>(srcIds.values());
        List<UUID> dstList = new ArrayList<>();
        for (String code : srcIds.keySet()) dstList.add(rt.dstIdByOrigCode().get(code));
        Map<String, String> srcNorm = new LinkedHashMap<>(), dstNorm = new LinkedHashMap<>();
        buildTokenMaps(srcList, dstList, "C", srcNorm, dstNorm);
        buildTokenMaps(viewIdsOf(srcList), viewIdsOf(dstList), "V", srcNorm, dstNorm);
        buildCodeTokenMaps(rt.finalCodeByOrigCode(), srcNorm, dstNorm);   // 跨组件引用的第二种载体：code

        List<Diff> raw = new ArrayList<>();
        List<Diff> norm = new ArrayList<>();
        int compPairs = 0, viewPairs = 0;
        for (Map.Entry<String, UUID> e : srcIds.entrySet()) {
            String key = e.getKey() + " → " + rt.finalCodeByOrigCode().get(e.getKey());
            UUID s = e.getValue(), d = rt.dstIdByOrigCode().get(e.getKey());
            Map<String, String> sr = rowText("component", compCols, s);
            Map<String, String> dr = rowText("component", compCols, d);
            raw.addAll(diffRow(key, "component", compCols, sr, dr, null, null));
            norm.addAll(diffRow(key, "component", compCols, sr, dr, srcNorm, dstNorm));
            compPairs++;

            Map<String, Map<String, String>> sv = viewsOf(s, viewCols);
            Map<String, Map<String, String>> dv = viewsOf(d, viewCols);
            assertEquals(sv.keySet(), dv.keySet(), AC + "：组件 " + key
                    + " 的视图名集合两边不等（源=" + sv.keySet() + " 新=" + dv.keySet() + "）⇒ 视图被丢或被改名。");
            for (String vn : sv.keySet()) {
                raw.addAll(diffRow(key + "/" + vn, "component_sql_view", viewCols, sv.get(vn), dv.get(vn), null, null));
                norm.addAll(diffRow(key + "/" + vn, "component_sql_view", viewCols, sv.get(vn), dv.get(vn), srcNorm, dstNorm));
                viewPairs++;
            }
        }
        assertTrue(compPairs > 0, AC + "：一对组件都没比到 ⇒ 断言从未执行（假绿）。");
        System.out.println("[" + AC + "] 比对规模：组件 " + compPairs + " 对 × " + compCols.size() + " 列，"
                + "视图 " + viewPairs + " 对 × " + viewCols.size() + " 列。");

        // 原始差异（未归一化）全部打印 —— 供主线判断哪些是「跨组件 id 重映射」这一类。
        if (!raw.isEmpty()) {
            System.out.println("[" + AC + "] 原始（未归一化）差异 " + raw.size() + " 条：" + fmt(raw));
        }
        assertTrue(norm.isEmpty(), AC + "：归一化跨组件引用（id 与 code）之后，差集仍有 " + norm.size()
                + " 条差异（应为 0）——"
                + "\n  这些是<b>真正丢失 / 被改写 / 该重映射而没重映射</b>的字段："
                + fmt(norm)
                + "\n  ⚠️ 判读提示：若某条的『新』侧仍是<b>源库的 UUID 原值</b>（没换成新 id），"
                + "那不是『重映射造成的差异』，恰恰是『<b>该重映射而没重映射</b>』——"
                + "导入后的组件会指向源目录的对象，跨库搬运时即成悬空引用。"
                + "\n  （归一化只把成对的 旧id→新id / 旧code→新code 换成同一 token，不碰任何业务值。"
                + "原始未归一化差异 " + raw.size() + " 条，归一化后 " + norm.size() + " 条 —— "
                + "两者不是包含关系：原始比对会把『新侧保留了源库旧 id』读成『相等』，那是假绿。）");
        System.out.println("[" + AC + "] ✅ 归一化后差异 0 条（原始未归一化差异 " + raw.size() + " 条，已逐条打印）。");
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 源目录里参与本次往返的 code → 源 component.id（只取包里真的有的那些）。 */
    private Map<String, UUID> srcIdByCode(UUID srcDir, RoundTrip rt) {
        Map<String, UUID> all = componentIdsByCode(srcDir);
        Map<String, UUID> out = new LinkedHashMap<>();
        for (String code : rt.dstIdByOrigCode().keySet()) {
            UUID id = all.get(code);
            assertNotNull(id, "包里的组件 " + code + " 在源目录里找不到 ⇒ 配对基准错了。");
            out.put(code, id);
        }
        assertFalse(out.isEmpty(), "配对表为空 ⇒ 后续循环 0 次，断言从未执行。");
        return out;
    }

    /** {@code (源组件 id, 目标组件 id)} 的 VALUES 片段。 */
    private String pairValues(UUID srcDir, RoundTrip rt) {
        Map<String, UUID> srcIds = componentIdsByCode(srcDir);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, UUID> e : rt.dstIdByOrigCode().entrySet()) {
            UUID s = srcIds.get(e.getKey());
            assertNotNull(s, "源目录里找不到 code=" + e.getKey());
            parts.add("('" + s + "'::uuid, '" + e.getValue() + "'::uuid)");
        }
        assertFalse(parts.isEmpty(), "pairs 为空 ⇒ 判据 SQL 会恒返 0 行（假绿）。");
        return String.join(", ", parts);
    }

    private static String brief(String v) {
        if (v == null) return "<SQL NULL>";
        if (v.isEmpty()) return "<空串>";
        return v.length() > 300 ? v.substring(0, 300) + "…(共 " + v.length() + " 字符)" : v;
    }

    private static String dump(List<Object[]> rs, String... headers) {
        StringBuilder sb = new StringBuilder("  " + String.join(" | ", headers));
        for (Object[] r : rs) {
            List<String> cells = new ArrayList<>();
            for (Object o : r) cells.add(Objects.toString(o, "<NULL>"));
            sb.append("\n  ").append(String.join(" | ", cells));
        }
        return sb.toString();
    }
}
