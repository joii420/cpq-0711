package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-14（序列）</b> —— 二次往返幂等。
 *
 * <h3>AC 原文（需求文档.md §③ 序列 AC）</h3>
 * <blockquote>
 * 操作：源目录 → 导出包 A → 导入新目录 → 从新目录再导出包 B。<br>
 * 断言：包 A 与包 B 的 {@code components} 数组（<b>忽略 {@code id} 字段与 {@code exportedAt}</b>）
 * <b>逐字段相等</b>；两包 {@code checksum} 的计算输入一致。<br>
 * 说明：这条锁死的是「导入没有悄悄规范化/丢弃某个字段」—— 单次往返比对源库可能被 DB 默认值掩盖。
 * </blockquote>
 *
 * <h3>相对 AC 原文的偏离（已上报主线）</h3>
 * <ol>
 *   <li><b>还要忽略 {@code code}。</b> {@code component_code_key} 是全局唯一索引 ⇒ 导入必然 RENAME，
 *       包 B 里的 {@code code} 必然与包 A 不同。AC-14 只写了忽略 {@code id}，
 *       但 AC-6 白名单已认可「{@code code} 在 RENAME 策略下允许不同」，本类按同一口径忽略 {@code code}
 *       并<b>单独断言</b>「{@code code} 确实变了、且新旧一一对应」，不把它当成静默豁免。</li>
 *   <li><b>「两包 checksum 的计算输入一致」判【未验证】。</b> 「计算输入」不是可观测量 ——
 *       要验它只能去读 {@code computeChecksum} 的实现，而本片<b>禁止读实现</b>（testing.md §1）。
 *       ⇒ 本类改验它的<b>可观测后果</b>：两个包各自过一次导入预览，
 *       {@code checksumValid} 都必须为 {@code true}（各自自洽）。
 *       原句的字面含义未验证，已在 test-report 标注。</li>
 * </ol>
 *
 * <h3>比对为什么用<b>序列化文本</b>而不是 {@code JsonNode.equals}</h3>
 * {@code JsonNode.equals} 对 object <b>忽略键序</b>，会掩盖「导入时被重排」。
 * ⚠️ 注意 DB 侧的 {@code jsonb} 本来就会在存储时归一化键序，所以<b>库列的 ::text 比对抓不到重排</b>；
 * 能抓到重排的只有这里 —— <b>包 A 与包 B 的原始 JSON 文本</b>。这正是 AC-14 存在的价值之一。
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-14 二次往返幂等")
class Ac14DoubleRoundTripTest extends Task260915Base {

    /** AC 原文点名忽略的字段 + RENAME 必然改的 code（理由见类 javadoc）。 */
    private static final Set<String> IGNORED_ITEM_FIELDS = new LinkedHashSet<>(List.of("id", "code"));

    @Test
    @DisplayName("AC-14 包A 与 包B 的 components 逐字段相等（忽略 id / code / exportedAt）")
    void ac14_secondExportEqualsFirst() {
        final String AC = "AC-14";
        UUID src = sourceDirectoryId();

        // ── 包 A：源目录导出 ──
        Bundle a = exportBundle(src, AC + " 包A");
        assertTrue(a.components() != null && a.components().isArray() && a.components().size() > 0,
                AC + "：包 A 的 components 为空 ⇒ 逐字段比对会 0 次循环（假绿）。raw 前 300 字="
                        + a.raw().substring(0, Math.min(300, a.raw().length())));

        // ── 导入到 M ──
        UUID dirM = newDirectory("AC14-M");
        RoundTrip rt = importInto(src, a, dirM, "RENAME", AC);

        // ── 包 B：从 M 再导出 ──
        Bundle b = exportBundle(dirM, AC + " 包B");
        assertEquals(a.components().size(), b.components().size(), AC + "：包 A 有 " + a.components().size()
                + " 个组件，包 B 有 " + b.components().size() + " 个 ⇒ 二次导出丢了组件。");

        // ── 配对：包A.code → 包B 里那条（用提交响应的 originalCode → finalCode）──
        Map<String, JsonNode> bByCode = new LinkedHashMap<>();
        for (JsonNode n : b.components()) bByCode.put(n.path("code").asText(), n);

        // id 归一化表：跨组件引用（实查 COMP-2269.excelColumns 内嵌兄弟组件 UUID）会被导入端重映射。
        Map<String, String> aNorm = new LinkedHashMap<>(), bNorm = new LinkedHashMap<>();
        List<UUID> aIds = new ArrayList<>(), bIds = new ArrayList<>();
        for (JsonNode n : a.components()) {
            String code = n.path("code").asText();
            String aid = n.path("id").asText(null);
            UUID bid = rt.dstIdByOrigCode().get(code);
            assertNotNull(bid, AC + "：包 A 的组件 " + code + " 在提交结果里找不到落点 ⇒ 无法配对。");
            assertNotNull(aid, AC + "：包 A 的组件 " + code + " 没有 id 字段 "
                    + "（main-api.md §2.1：Item.id 供导入端重映射跨组件引用）⇒ 无法建立归一化对应。");
            aIds.add(UUID.fromString(aid));
            bIds.add(bid);
        }
        buildTokenMaps(aIds, bIds, "C", aNorm, bNorm);
        buildCodeTokenMaps(rt.finalCodeByOrigCode(), aNorm, bNorm);   // 跨组件引用的第二种载体：code

        int pairs = 0, viewPairs = 0, renamed = 0;
        List<String> diffs = new ArrayList<>();
        for (JsonNode ai : a.components()) {
            String origCode = ai.path("code").asText();
            String finalCode = rt.finalCodeByOrigCode().get(origCode);
            JsonNode bi = bByCode.get(finalCode);
            assertNotNull(bi, AC + "：包 B 里找不到 code=" + finalCode + "（由包 A 的 " + origCode
                    + " 导入后重命名而来）。包B codes=" + bByCode.keySet());
            if (!origCode.equals(finalCode)) renamed++;
            pairs++;

            // 字段名集合必须一致 —— 少一个字段就是「二次导出悄悄丢字段」。
            Set<String> af = fieldNames(ai), bf = fieldNames(bi);
            assertEquals(af, bf, AC + "：组件 " + origCode + " 在包 A 与包 B 的字段名集合不同"
                    + "\n  仅 A 有=" + minus(af, bf) + "\n  仅 B 有=" + minus(bf, af));

            for (String f : af) {
                if (IGNORED_ITEM_FIELDS.contains(f)) continue;
                if ("sqlViews".equals(f)) continue;   // 数组，单独配对比
                String av = normalizeIds(ai.get(f).toString(), aNorm);
                String bv = normalizeIds(bi.get(f).toString(), bNorm);
                if (!av.equals(bv)) {
                    diffs.add("  · [components." + f + "] " + origCode + " → " + finalCode
                            + "\n      包A = " + cut(av) + "\n      包B = " + cut(bv));
                }
            }

            // sqlViews：按 sqlViewName 配对（视图名本身也应保真；不等即报，🚫 不按下标凑对）
            Map<String, JsonNode> avs = viewsByName(ai), bvs = viewsByName(bi);
            assertEquals(avs.keySet(), bvs.keySet(), AC + "：组件 " + origCode
                    + " 的 sqlViews 名字集合两包不同（A=" + avs.keySet() + " B=" + bvs.keySet() + "）。");
            for (String vn : avs.keySet()) {
                JsonNode av = avs.get(vn), bv = bvs.get(vn);
                Set<String> avf = fieldNames(av), bvf = fieldNames(bv);
                assertEquals(avf, bvf, AC + "：组件 " + origCode + " 视图 " + vn + " 的字段名集合两包不同"
                        + "\n  仅 A 有=" + minus(avf, bvf) + "\n  仅 B 有=" + minus(bvf, avf));
                for (String f : avf) {
                    String x = normalizeIds(av.get(f).toString(), aNorm);
                    String y = normalizeIds(bv.get(f).toString(), bNorm);
                    if (!x.equals(y)) {
                        diffs.add("  · [sqlViews." + f + "] " + origCode + "/" + vn
                                + "\n      包A = " + cut(x) + "\n      包B = " + cut(y));
                    }
                }
                viewPairs++;
            }
        }
        assertTrue(pairs > 0, AC + "：一对组件都没比到 ⇒ 断言从未执行（假绿）。");
        assertTrue(viewPairs > 0, AC + "：一对 sqlView 都没比到 ⇒ 视图侧的字段（含 builderConfig）从未被验证。");
        assertTrue(diffs.isEmpty(), AC + "：包 A 与包 B 的 components 逐字段比对出 " + diffs.size() + " 处差异（应为 0）——"
                + "\n  差异 = 导入过程悄悄规范化或丢弃了字段（这正是本条 AC 要锁死的东西）："
                + "\n" + String.join("\n", diffs));
        System.out.println("[" + AC + "] ✅ 组件 " + pairs + " 对 / 视图 " + viewPairs + " 对，逐字段全等；"
                + "其中被 RENAME 的 " + renamed + " 个（code 已按 AC-6 白名单口径单列）。");

        // code 必须真的变了（RENAME 是既定行为，不能悄悄当成「没变」而掩盖配对错误）
        assertEquals(pairs, renamed, AC + "：期望全部 " + pairs + " 个组件都因全局唯一 code 冲突被 RENAME，"
                + "实际只有 " + renamed + " 个 ⇒ 配对逻辑或冲突策略与预期不符，请核对后再读上面的「全等」结论。");

        // ── 「checksum 计算输入一致」的可观测替身：两包各自自洽 ──
        UUID probe = newDirectory("AC14-PROBE");
        JsonNode pa = preview(probe, a.raw(), "RENAME", AC + " 包A checksum");
        JsonNode pb = preview(probe, b.raw(), "RENAME", AC + " 包B checksum");
        assertTrue(pa.path("checksumValid").asBoolean(false), AC + "：包 A 的 checksumValid=false ⇒ 包 A 自身不自洽。");
        assertTrue(pb.path("checksumValid").asBoolean(false), AC + "：包 B 的 checksumValid=false "
                + "⇒ 二次导出的包 checksum 与内容对不上（新增字段没纳入计算，或计算口径两次不同）。");
        System.out.println("[" + AC + "] ✅ 包A/包B 的 checksumValid 均为 true。"
                + "⚠️ AC 原文「两包 checksum 的计算输入一致」的字面含义【未验证】——「计算输入」非可观测量，"
                + "验它需读实现，本片禁止读实现。");
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private Map<String, JsonNode> viewsByName(JsonNode item) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode vs = item.get("sqlViews");
        if (vs == null || vs.isNull()) return out;
        assertTrue(vs.isArray(), "sqlViews 不是数组：" + vs);
        for (JsonNode v : vs) out.put(v.path("sqlViewName").asText(), v);
        assertEquals(vs.size(), out.size(), "同一组件里出现了重名 sqlViewName ⇒ 无法按名字配对。" + vs);
        return out;
    }

    private static String cut(String s) {
        if (s == null) return "<null>";
        return s.length() > 400 ? s.substring(0, 400) + "…(共 " + s.length() + " 字符)" : s;
    }
}
