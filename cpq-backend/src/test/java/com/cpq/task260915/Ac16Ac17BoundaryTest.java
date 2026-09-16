package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-16 / AC-17（边界）</b>。
 *
 * <h3>AC 原文（需求文档.md §③ 边界 AC）</h3>
 * <ul>
 *   <li><b>AC-16 空目录</b>：导出一个 0 组件的目录再导入；断言导出包 {@code components} 为
 *       <b>空数组（非 null）</b>，导入返回成功且创建 <b>0 个</b>组件，<b>不抛异常</b>。</li>
 *   <li><b>AC-17 目标库语义图缺坐标</b>：构造一个 {@code builder_config.tabType} 在目标库
 *       {@code semantic_tab_view} 中不存在的包（如把 {@code tabType} 改成「不存在的类型」）；断言
 *       ① 预览中该组件标记为 {@code UNRESOLVABLE}；
 *       ② <b>导入仍然成功（不阻断）</b>；
 *       ③ {@code builder_config} <b>原样落库</b>（未被清空或改写）。</li>
 * </ul>
 *
 * <h3>🧭 AC-17 的层级说明（2026-09-15 主线通知的处置）</h3>
 * 主线提醒「antd v6 下 {@code .ant-tooltip-inner} 已不存在，断言它会拿到空串」，
 * 并给出前端侧「不阻断」的观察点是<b>提交按钮 {@code disabled === false}</b>。
 * <p>⚠️ <b>本类是后端接口层用例（JUnit + RestAssured + SQL），不含任何 DOM 断言</b>，
 * 因此那条 selector 坑<b>不影响本类</b>；本类对应的「不阻断」观察点是
 * <b>{@code preview.canCommit === true} + {@code blockers} 不提及该组件 + 提交返回 200</b>。
 * <p>🚨 <b>缺口登记</b>：AC-17 ① 的「橙色 Tag + Tooltip 显示人话原因」属于 F-1 的<b>前端呈现</b>，
 * {@code test.md §4} 明确本任务不跑 Playwright ⇒ <b>该呈现由主线亲验承担，本片判【未验证】</b>。
 * 主线亲验时若要读 Tooltip 文本，selector 用 <b>{@code .ant-tooltip}</b>（不是 {@code .ant-tooltip-inner}），
 * 并<b>先断言取到的文本非空</b>再断言内容 —— 否则空串会伪装成「通过」。
 * <p>🚫 主线同时告知「实现里那段逻辑一行未动」。本类<b>不据此放宽任何断言</b>：
 * 用例只从 AC 原文派生（testing.md §1），实现是否改过与断言强度无关。
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-16/17 边界")
class Ac16Ac17BoundaryTest extends Task260915Base {

    @Test
    @DisplayName("AC-16 空目录：导出 components 为空数组（非 null），导入成功且创建 0 个")
    void ac16_emptyDirectoryRoundTrip() {
        final String AC = "AC-16";
        UUID empty = newDirectory("AC16-EMPTY-SRC");

        // 前置自证：它真的是空目录（🚫 带 directory_id 限定，不是全局计数）
        assertEquals(0, count("SELECT count(*) FROM component WHERE directory_id='" + empty + "'::uuid"),
                AC + "：新建的目录里居然有组件 ⇒ 「空目录」这个前置不成立。");

        Bundle b = exportBundle(empty, AC);
        JsonNode comps = b.json().get("components");
        // 三种坏法分开报：字段缺失 / 是 JSON null / 不是数组。AC 原文点名要的是「空数组（非 null）」。
        assertNotNull(comps, AC + "：导出包里<b>没有</b> components 字段 ⇒ 前端遍历它会直接崩。raw=" + b.raw());
        assertFalse(comps.isNull(), AC + "：导出包的 components 是 JSON null，AC 原文要求的是<b>空数组</b> "
                + "⇒ 消费方 `for (c of bundle.components)` 会抛 TypeError。raw=" + b.raw());
        assertTrue(comps.isArray(), AC + "：components 不是数组，实际节点类型=" + comps.getNodeType() + "。raw=" + b.raw());
        assertEquals(0, comps.size(), AC + "：空目录导出的 components 应为 0 个，实际 " + comps.size() + " 个。");
        System.out.println("[" + AC + "] 空目录导出包 components=" + comps + "（数组、长度 0）");

        // 再导入到另一个空目录：成功、创建 0 个、不抛异常
        UUID dst = newDirectory("AC16-EMPTY-DST");
        JsonNode pv = preview(dst, b.raw(), "RENAME", AC);
        assertTrue(pv.path("canCommit").asBoolean(false), AC + "：空包的预览 canCommit=false ⇒ 空包被当成错误了。"
                + " blockers=" + pv.path("blockers") + " checksumValid=" + pv.path("checksumValid"));
        assertEquals(0, pv.path("summary").path("total").asInt(-1), AC + "：空包预览 summary.total 应为 0。pv=" + pv);

        JsonNode res = commit(dst, b.raw(), "RENAME", AC);   // 非 200 会在 commit() 里硬失败（含 5xx 抛异常的情形）
        assertEquals(0, res.path("createdCount").asInt(-1), AC + "：空包导入应创建 0 个组件，实际 "
                + res.path("createdCount").asInt(-1) + " 个。res=" + res);
        assertEquals(0, count("SELECT count(*) FROM component WHERE directory_id='" + dst + "'::uuid"),
                AC + "：空包导入后目标目录里不应有任何组件。");
        System.out.println("[" + AC + "] ✅ 空目录导出→导入全程 200，createdCount=0，未抛异常。");
    }

    @Test
    @DisplayName("AC-17 坐标解析不动：标记 UNRESOLVABLE、不阻断导入、builder_config 原样落库")
    void ac17_unresolvableBuilderCoordIsReportedButNotBlocking() {
        final String AC = "AC-17";
        UUID src = sourceDirectoryId();
        final String BOGUS = "RT_SA_260915_" + RUN_ID + "_不存在的类型";

        // 🚨 阳性前提：这个 tabType 在目标库里<b>确实不存在</b>，否则「解析不动」这个前提不成立，用例白跑。
        assertEquals(0, count("SELECT count(*) FROM semantic_tab_view WHERE tab_type=" + lit(BOGUS)),
                AC + " 前置未满足：构造的 tabType「" + BOGUS + "」在目标库 semantic_tab_view 里居然存在 "
                        + "⇒ 它不是「解析不动」的坐标，本条 AC 的断言没有输入。");

        Bundle a = exportBundle(src, AC);

        // 挑一个有 builderConfig 的组件做「被破坏方」，另一个做阳性对照。
        // 🚨 被破坏方必须<b>不含树 token</b>：含 tree_ref/tree_attr 的组件把 tabType 改坏会撞<b>树页签校验</b>（400），
        //    那时红的原因是「树校验拒绝」而不是「坐标解析不动」，两件完全不同的事会被混成同一条红。
        //    ⇒ 这里<b>筛</b>而不是<b>断言第一个恰好合适</b>，否则源目录哪天被加了一条树公式，本用例就会假红。
        List<Integer> withBuilder = new ArrayList<>();
        List<Integer> victimCandidates = new ArrayList<>();
        for (int i = 0; i < a.components().size(); i++) {
            if (!hasBuilderConfig(a.components().get(i))) continue;
            withBuilder.add(i);
            String txt = a.components().get(i).toString();
            if (!txt.contains("tree_ref") && !txt.contains("tree_attr")) victimCandidates.add(i);
        }
        assertTrue(withBuilder.size() >= 2, AC + " 前置未满足：包里只有 " + withBuilder.size()
                + " 个带 builderConfig 的组件，无法同时构造「一个被破坏 + 至少一个正常」"
                + "⇒ 少了阳性对照，「所有组件恒为 UNRESOLVABLE」这种坏法会被当成通过。"
                + "（若这里为 0，说明 B-1 导出端还没把 builderConfig 带上，本条 AC 无从验。）");
        assertFalse(victimCandidates.isEmpty(), AC + " 前置未满足：" + withBuilder.size()
                + " 个带 builderConfig 的组件<b>全都</b>含 tree_ref/tree_attr ⇒ 改谁的 tabType 都会撞树校验，"
                + "本条 AC 无法与「树校验」区分开。请报主线换基准目录。");

        int victimIdx = victimCandidates.get(0);
        ObjectNode victim = (ObjectNode) a.components().get(victimIdx);
        String victimCode = victim.path("code").asText();
        int controlIdx = withBuilder.get(0) == victimIdx ? withBuilder.get(1) : withBuilder.get(0);
        String controlCode = a.components().get(controlIdx).path("code").asText();
        assertFalse(victimCode.equals(controlCode), AC + "：被破坏方与阳性对照选中了同一个组件 ⇒ 对照失效。");

        ObjectNode bc = firstBuilderConfig(victim);
        String before = bc.toString();
        bc.put("tabType", BOGUS);
        String bundleJson = a.json().toString();
        assertTrue(bundleJson.contains(BOGUS), AC + "：改写后的包里找不到 " + BOGUS + " ⇒ 改写没生效，用例会验成「正常包」。");
        System.out.println("[" + AC + "] 被破坏组件=" + victimCode + "（对照=" + controlCode + "）；"
                + "builderConfig 改写前=" + brief(before));

        UUID dst = newDirectory("AC17-DST");

        // ── ① 预览：该组件 UNRESOLVABLE + 人话原因 ──
        JsonNode pv = preview(dst, bundleJson, "RENAME", AC);
        JsonNode victimPlan = planOf(pv, victimCode, AC);
        JsonNode controlPlan = planOf(pv, controlCode, AC);

        JsonNode vCoord = victimPlan.get("builderCoord");
        assertTrue(vCoord != null && !vCoord.isNull(), AC + "①：预览里组件 " + victimCode
                + " 没有 builderCoord 字段（api.md §二：与 formulaBinding 并列的新增项）。plan=" + victimPlan);
        assertEquals("UNRESOLVABLE", vCoord.path("status").asText(), AC + "①：组件 " + victimCode
                + " 的 builderCoord.status 应为 UNRESOLVABLE，实际「" + vCoord.path("status").asText() + "」。coord=" + vCoord);
        String msg = vCoord.path("message").isNull() ? null : vCoord.path("message").asText();
        // 🚨 「拿到空串照样过」是本任务点名要防的假绿形态：非 null、非空白、且要能定位到缺什么。
        assertNotNull(msg, AC + "①：UNRESOLVABLE 的 message 为 null（api.md §二：UNRESOLVABLE 时给人话原因）。");
        assertFalse(msg.isBlank(), AC + "①：UNRESOLVABLE 的 message 是空白串 ⇒ 用户看到一个橙色标记却不知道缺什么。");
        assertTrue(msg.contains(BOGUS), AC + "①：message 里没有点名解析不动的坐标「" + BOGUS + "」"
                + " ⇒ 拿到这条提示也定位不了问题。实际 message=「" + msg + "」");

        // 阳性对照：证明这个字段不是恒为 UNRESOLVABLE（否则本条 AC 无论实现对错都绿）。
        assertEquals("RESOLVED", controlPlan.path("builderCoord").path("status").asText(),
                AC + "① 阳性对照失败：未被破坏的组件 " + controlCode + " 的 builderCoord.status 也不是 RESOLVED，实际「"
                        + controlPlan.path("builderCoord").path("status").asText()
                        + "」⇒ 该字段可能恒为同一个值，UNRESOLVABLE 那条断言不可信。");
        System.out.println("[" + AC + "] ① UNRESOLVABLE message=「" + msg + "」；对照组件 " + controlCode + " = RESOLVED");

        // ── ② 不阻断 ──
        assertTrue(pv.path("canCommit").asBoolean(false), AC + "②：预览 canCommit=false ⇒ UNRESOLVABLE 阻断了导入，"
                + "与 AC-17② 「导入仍然成功（不阻断）」相反。blockers=" + pv.path("blockers")
                + "\n  ⚠️ 若 blockers 里只提到 checksum，那是本用例改写包内容导致的 harness 副作用，"
                + "不是 AC-17 的结论 —— 请按这条区分。checksumValid=" + pv.path("checksumValid"));
        String blockers = pv.path("blockers").toString();
        assertFalse(blockers.contains(victimCode), AC + "②：blockers 里点名了组件 " + victimCode
                + " ⇒ UNRESOLVABLE 进了阻断清单（api.md §二 硬约束 1 明确它不进 blockers）。blockers=" + blockers);

        JsonNode res = commit(dst, bundleJson, "RENAME", AC);
        assertTrue(res.path("createdCount").asInt(0) > 0, AC + "②：导入创建了 0 个组件 ⇒ 实际被阻断了。res=" + res);

        // ── ③ builder_config 原样落库 ──
        UUID newComp = createdIdOf(res, victimCode, AC);
        List<Object> cfgs = col("SELECT builder_config::text FROM component_sql_view WHERE component_id='"
                + newComp + "'::uuid AND builder_config IS NOT NULL");
        assertFalse(cfgs.isEmpty(), AC + "③：导入后组件 " + victimCode + " 的视图 builder_config 为 NULL "
                + "⇒ 坐标解析不动时 builder_config 被<b>清空</b>了（AC-17③ 明确要求原样落库）。");
        String landed = String.valueOf(cfgs.get(0));
        assertTrue(landed.contains(BOGUS), AC + "③：落库的 builder_config 里没有原样保留 tabType=「" + BOGUS
                + "」⇒ 它被改写了（可能被回落成某个默认坐标）。实际落库=" + brief(landed));
        String landedTabType = scalar("SELECT builder_config->>'tabType' FROM component_sql_view WHERE component_id='"
                + newComp + "'::uuid AND builder_config IS NOT NULL LIMIT 1");
        assertEquals(BOGUS, landedTabType, AC + "③：落库的 builder_config->>'tabType' 应原样为「" + BOGUS
                + "」，实际「" + landedTabType + "」。");
        System.out.println("[" + AC + "] ✅ ① UNRESOLVABLE+人话原因  ② 未阻断（createdCount="
                + res.path("createdCount").asInt() + "）  ③ builder_config 原样落库（tabType=" + landedTabType + "）");
        System.out.println("[" + AC + "] ⚠️【未验证】前端橙色 Tag + Tooltip 的呈现（F-1）—— test.md §4 本任务不跑 Playwright，"
                + "由主线亲验承担；读 Tooltip 文本请用 .ant-tooltip（antd v6 下 .ant-tooltip-inner 已不存在，会静默取到空串）。");
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    private static boolean hasBuilderConfig(JsonNode item) {
        JsonNode vs = item.get("sqlViews");
        if (vs == null || !vs.isArray()) return false;
        for (JsonNode v : vs) {
            JsonNode bc = v.get("builderConfig");
            if (bc != null && bc.isObject() && bc.size() > 0) return true;
        }
        return false;
    }

    private ObjectNode firstBuilderConfig(JsonNode item) {
        for (JsonNode v : item.get("sqlViews")) {
            JsonNode bc = v.get("builderConfig");
            if (bc != null && bc.isObject() && bc.size() > 0) return (ObjectNode) bc;
        }
        throw new AssertionError("组件 " + item.path("code").asText() + " 里找不到非空 builderConfig ——"
                + " 若导出包里 builderConfig 恒为 null，说明 B-1（导出端补字段）还没落地，本条 AC 无法验。item=" + item);
    }

    private JsonNode planOf(JsonNode preview, String code, String acRef) {
        JsonNode comps = preview.get("components");
        assertTrue(comps != null && comps.isArray() && comps.size() > 0,
                acRef + "：预览结果里没有 components 计划数组 ⇒ 无从取 builderCoord。pv=" + preview);
        for (JsonNode p : comps) if (code.equals(p.path("code").asText())) return p;
        throw new AssertionError(acRef + "：预览计划里找不到组件 " + code + "。pv=" + comps);
    }

    private UUID createdIdOf(JsonNode commitResult, String originalCode, String acRef) {
        for (JsonNode c : commitResult.path("created")) {
            if (originalCode.equals(c.path("originalCode").asText())) {
                return UUID.fromString(c.path("componentId").asText());
            }
        }
        throw new AssertionError(acRef + "：提交结果里找不到 originalCode=" + originalCode
                + " 的落点 ⇒ 该组件没被导入。created=" + commitResult.path("created"));
    }

    private static String brief(String v) {
        if (v == null) return "<null>";
        return v.length() > 300 ? v.substring(0, 300) + "…(共 " + v.length() + " 字符)" : v;
    }
}
