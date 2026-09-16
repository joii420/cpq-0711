package com.cpq.task260915;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * task-260915 · <b>AC-15</b>（分片 S-全局，<b>序列</b>类）—— 导入后取数配置器可继续编辑。
 *
 * <h3>AC 原文（需求文档.md §③「序列 AC」）</h3>
 * <blockquote>
 * <b>AC-15 · 导入后配置器可继续编辑</b><br>
 * · 操作：导入后在取数配置器中打开新目录的某个 builder 组件 → 确认显示的配置与源库一致 →
 *   <b>不改动直接保存</b> → 再次打开<br>
 * · 断言：① 打开时显示原有的节点/列/开关配置，<b>不是</b>空白或「未配置」；
 *   ② 保存后 {@code component_sql_view.sql_template} 仍能正常执行（{@code EXPLAIN} 不报错）；
 *   ③ 再次打开配置不丢失
 * </blockquote>
 *
 * <h3>🚨 三段式序列，中间态与最终态都要断言（testing.md §3「序列」）</h3>
 * <pre>
 *   源库 M（自建）──导出──▶ 包 ──导入──▶ 新目录 N
 *        │                                  │
 *        │            ①打开 ────────────────┤  ← 与源库逐项比对（非空 + 一致）
 *        │            ②不改动保存 ──────────┤  ← 200 + EXPLAIN 不报错
 *        │            ③再次打开 ────────────┘  ← 与①逐项相等
 * </pre>
 *
 * <h3>🚨 本 AC 的假绿风险：①③ 都可能在「空对空」上恒成立</h3>
 * 「打开 = 空白」时，①的「与源一致」在两边都空的情况下<b>照样成立</b>，
 * ③的「再次打开与①相等」在两边都空的情况下<b>更是必然成立</b>。
 * ⇒ 本用例先断言 {@code columns} <b>非空</b>、{@code tabType} <b>非空</b>，再谈一致性；
 * 并在末尾用 {@link #assertReopenReflectsWhatWasSaved} 做<b>阳性对照</b>：
 * 改一个字段名再保存再打开，必须看到<b>新名</b> —— 证明「再次打开」读的是真实存储，
 * 不是一个恒定不变（因而恒相等）的缓存/常量。
 *
 * <h3>🚦 本片会动的全局状态（test.md §3，主线须随 test-report.md 上报）</h3>
 * <ol>
 *   <li>{@code PUT /builder} 触发<b>全量视图重编译</b> —— 影响全库所有 builder 视图的编译产物，<b>无法还原</b></li>
 *   <li>组件保存可能刷新组件/模板快照 —— 本片自建组件<b>不绑任何模板</b>，
 *       {@link #assertNoTemplateBinding} 把这一点当场验明，避免「以为不影响」</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260915 · AC-15 —— 导入后配置器能打开→保存→再打开，配置不丢")
class Ac15BuilderReopenAfterImportTest extends Task260915SgBase {

    private static final String AC = "AC-15";

    @Test
    @DisplayName("AC-15【序列】: 导入→打开(非空且与源一致)→原样保存(200+EXPLAIN 通过)→再次打开(与①逐项相等)")
    void ac15_builderConfigSurvivesImportAndResave() throws Exception {
        String semFp0 = semanticBaselineFingerprint();
        printGlobalFootprint("开跑前", foreignViewFingerprint(), semFp0);

        // ════════ 前置：自建源目录 M + 一个 builder 组件 ════════
        UUID dirM = createDirectory("M15");
        UUID compM = createComponentInDir(dirM, "配置器组件", null, null);
        saveBuilderOk(compM, CFG_MATERIAL_BOM, AC + " 前置");

        // 前置非空断言：源库这一侧的配置本身不是空的（否则 ①「与源一致」会空对空）
        JsonNode srcCfg = builderConfigOf(compM, AC + " 前置");
        List<String> srcFieldNames = fieldNames(srcCfg);
        assertFalse(srcFieldNames.isEmpty(), AC + " 前置未满足：源组件的 builderConfig.columns 为空 "
                + "⇒ ①「与源库一致」会在「空 = 空」上恒成立（testing.md §5.5 形态②）。实际=" + srcCfg);
        String srcTabType = srcCfg.path("tabType").asText(null);
        assertTrue(srcTabType != null && !srcTabType.isBlank(),
                AC + " 前置未满足：源组件 builderConfig.tabType 为空 ⇒ 「不是未配置」这条断言没有输入。");
        String srcSql = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = '" + compM + "'");
        assertTrue(srcSql != null && !srcSql.isBlank(),
                AC + " 前置未满足：源组件的 sql_template 为空 ⇒ EXPLAIN 断言会空跑。");
        System.out.println("[" + AC + " 前置✅] 源 tabType=" + srcTabType + " columns=" + srcFieldNames);

        String codeM = scalar("SELECT code FROM component WHERE id = '" + compM + "'");

        // ════════ 导出 M → 导入 N ════════
        String raw = exportRaw(dirM, AC);
        JsonNode bundle = parseBundle(raw, AC);
        assertTrue(bundle.path("components").size() > 0,
                AC + "：导出包 components 为空 ⇒ 后面导入的是个空包，所有断言空跑。body=" + raw);

        UUID dirN = createDirectory("N15");
        Response commit = importCommit(dirN, raw, "RENAME", AC);
        assertEquals(200, commit.statusCode(), AC + "：导入应 200（AC-15 的全部断言都以「导入成功」为前提），实际="
                + commit.statusCode() + " body=" + commit.asString());

        long inN = count("SELECT count(*) FROM component WHERE directory_id = '" + dirN + "'");
        assertEquals(1, inN, AC + "：新目录 " + dirN + " 里应恰好落 1 个组件（源目录 M 只有 1 个），实际=" + inN);
        UUID compN = UUID.fromString(scalar("SELECT id::text FROM component WHERE directory_id = '" + dirN + "'"));
        System.out.println("[" + AC + "] 源组件 " + compM + "(" + codeM + ") → 新组件 " + compN);

        assertNoTemplateBinding(compN);

        // ════════ ① 打开：非空 + 与源库一致 ════════
        JsonNode open1 = builderConfigOf(compN, AC + "①");
        List<String> open1Fields = fieldNames(open1);

        assertFalse(open1Fields.isEmpty(), AC + "①：导入后在取数配置器里打开，columns 为空 "
                + "⇒ 正是 AC 原文要证伪的「打开是空白/未配置」。实际 builderConfig=" + open1);
        String open1TabType = open1.path("tabType").asText(null);
        assertTrue(open1TabType != null && !open1TabType.isBlank() && !"未配置".equals(open1TabType),
                AC + "①：导入后打开显示 tabType=" + open1TabType + " ⇒ 「不是未配置」不成立。builderConfig=" + open1);

        assertEquals(srcTabType, open1TabType,
                AC + "①：新目录组件的 tabType 与源库不一致（源=" + srcTabType + " 新=" + open1TabType + "）。");
        assertEquals(srcFieldNames, open1Fields,
                AC + "①：新目录组件的列配置与源库不一致（源=" + srcFieldNames + " 新=" + open1Fields + "）。");
        assertEquals(srcCfg.path("dialect").asText(null), open1.path("dialect").asText(null),
                AC + "①：dialect 与源库不一致。源=" + srcCfg.path("dialect") + " 新=" + open1.path("dialect"));
        assertEquals(srcCfg.path("variantKey").asText(""), open1.path("variantKey").asText(""),
                AC + "①：variantKey 与源库不一致。源=" + srcCfg.path("variantKey") + " 新=" + open1.path("variantKey"));
        // 「开关配置」（AC 原文点名的第三项）—— 逐字节比对整段，源为空也要求新为空
        assertEquals(srcCfg.path("switches").toString(), open1.path("switches").toString(),
                AC + "①：switches（开关配置）与源库不一致。源=" + srcCfg.path("switches") + " 新=" + open1.path("switches"));
        System.out.println("[" + AC + "①✅] 打开非空且与源一致：tabType=" + open1TabType + " columns=" + open1Fields);

        // ════════ ② 不改动直接保存 → 200，且 sql_template 仍可 EXPLAIN ════════
        Response resave = saveBuilder(compN, open1.toString());
        assertReachedBusinessLayer(resave, AC + "②");
        assertEquals(200, resave.statusCode(), AC + "②：把刚打开的配置「原样」保存回去应成功，实际="
                + resave.statusCode() + " ⇒ 导入落库的 builder_config 不是一份「配置器能再次接受」的配置。"
                + " body=" + resave.asString());

        String sqlAfterSave = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = '" + compN + "'");
        explainOk(sqlAfterSave, AC + "②");

        // ════════ ③ 再次打开：与①逐项相等（配置不丢）════════
        JsonNode open2 = builderConfigOf(compN, AC + "③");
        List<String> open2Fields = fieldNames(open2);
        assertFalse(open2Fields.isEmpty(), AC + "③：再次打开 columns 为空 ⇒ 原样保存把配置存丢了。builderConfig=" + open2);
        assertEquals(open1Fields, open2Fields,
                AC + "③：再次打开的列配置与第一次打开不一致（①=" + open1Fields + " ③=" + open2Fields + "）。");
        assertEquals(open1.path("tabType").asText(null), open2.path("tabType").asText(null), AC + "③：tabType 丢失/改变。");
        assertEquals(open1.path("dialect").asText(null), open2.path("dialect").asText(null), AC + "③：dialect 丢失/改变。");
        assertEquals(open1.path("switches").toString(), open2.path("switches").toString(), AC + "③：switches 丢失/改变。");
        System.out.println("[" + AC + "③✅] 再次打开与①一致：columns=" + open2Fields);

        // ════════ 阳性对照：证明「再次打开」读的是真实存储 ════════
        assertReopenReflectsWhatWasSaved(compN, open2);

        printGlobalFootprint("跑完后", foreignViewFingerprint(), semanticBaselineFingerprint());
        assertEquals(semFp0, semanticBaselineFingerprint(),
                AC + "：语义图基线（semantic_tab_view / semantic_node）在本片跑动前后发生了变化 "
                + "⇒ 本片越界改了配置基线（test.md §3 明令不许动）。");
    }

    /**
     * <b>阳性对照（testing.md §4.4 / §5.5）</b>：①③ 的「相等」断言有一种失效方式 ——
     * {@code GET /builder} 若返回的是一个<b>恒定不变</b>的东西（缓存/常量/兜底空壳），
     * 「再次打开与第一次相等」会<b>永远成立</b>，AC-15③ 等于没验。
     *
     * <p>破法：故意改一个 {@code fieldName} 存进去，再打开必须看到<b>新名</b>。
     * 看不到 ⇒ 说明「打开」读不到刚存的东西，③ 的绿是空的。
     * <p>🚫 这不是在验 AC-15 之外的东西，而是在证明 AC-15③ 的判据本身是活的。
     */
    private void assertReopenReflectsWhatWasSaved(UUID componentId, JsonNode currentCfg) {
        List<String> before = fieldNames(currentCfg);
        assertFalse(before.isEmpty(), AC + " 阳性对照前置：当前配置无列，改不了名。");

        String probeName = "RTSG" + RUN_ID;
        assertFalse(before.contains(probeName), AC + " 阳性对照前置：探针名已存在，换一个。");

        // 🚨 改「最后一列」而不是第一列：第一列在本夹具里带 isRowKey/isPartNo，
        //    动它容易串进「标识列至少配一个」之类与本对照无关的校验，把对照失败误读成 AC 失败。
        String target = before.get(before.size() - 1);
        String mutated = currentCfg.toString().replaceFirst(
                "\"fieldName\"\\s*:\\s*\"" + java.util.regex.Pattern.quote(target) + "\"",
                "\"fieldName\":\"" + probeName + "\"");
        assertNotEquals(currentCfg.toString(), mutated,
                AC + " 阳性对照前置：改名没改动任何字符 ⇒ 探针无效（正则没命中 fieldName）。");

        Response r = saveBuilder(componentId, mutated);
        assertReachedBusinessLayer(r, AC + " 阳性对照");
        assertEquals(200, r.statusCode(), AC + " 阳性对照：改字段名保存应 200（既有基线 Sec32 AC-12 已锁定改名不阻断），"
                + "实际=" + r.statusCode() + " body=" + r.asString()
                + " ⇒ 无法完成对照，此时 AC-15③ 的绿不可采信。");

        List<String> after = fieldNames(builderConfigOf(componentId, AC + " 阳性对照"));
        assertTrue(after.contains(probeName),
                AC + " 阳性对照失败：存进去的新字段名『" + probeName + "』再打开时看不到（实际=" + after + "）"
                + " ⇒ 🚨 GET /builder 返回的不是真实存储，AC-15③「再次打开配置不丢失」的绿是「恒真的空断言」。");
        System.out.println("[" + AC + " 阳性对照✅] 改名 → 再打开看得到『" + probeName + "』⇒ ③ 的判据是活的");
    }

    /**
     * 本片自建组件<b>不绑任何模板</b> —— 当场验明，而不是「以为不影响」。
     * <p>组件保存会刷新模板快照（既有 AP-40 记载），若夹具意外被绑进某个模板，
     * 本片就会改到别人的快照，那属于 test.md §3 未登记的全局副作用。
     */
    private void assertNoTemplateBinding(UUID componentId) {
        long n = count("SELECT count(*) FROM template_component WHERE component_id = '" + componentId + "'");
        assertEquals(0, n, AC + "：自建组件 " + componentId + " 竟被绑进了 " + n + " 个模板 "
                + "⇒ 本片的组件保存会连带刷新那些模板的快照，属于未登记的全局副作用，停下来报主线。");
    }

    private static List<String> fieldNames(JsonNode builderConfig) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : builderConfig.path("columns")) out.add(c.path("fieldName").asText(null));
        return out;
    }
}
