package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-10 / AC-11</b>：1.0 老包的降级导入与可定位报错。
 *
 * <p><b>输入</b>：{@code 素材/用户原始导出包-bundleVersion1.0.json} —— 用户从另一台电脑导出的<b>原始</b>包，
 * 10 个组件，来源目录「施耐德-4(镍粉无税，H85)」。实查：{@code COMP-0002「物料」} 的公式里有
 * <b>2 处 {@code tree_ref}</b>，其余 9 个组件 0 处；{@code dependencies} 全空；
 * {@code bindingReport.unboundCount = 0}（⇒ 不需要 {@code ignoreMissingDeps} / {@code ignoreUnboundFormulas}
 * 这两个放行开关，用它们会把本用例的判别力冲淡）。
 *
 * <p><b>AC-10 原文</b>：其余 9 个不含树 token 的组件<b>导入成功</b>，不因升版而整包失败。
 * <p><b>AC-11 原文</b>：COMP-0002 的报错文案<b>包含</b>「导入包是旧格式」与「请在源库升级后重新导出」两层信息，
 * <b>不再是</b>孤立的 {@code 当前组件 tabType=(未配置)}。
 *
 * <h3>⚠️ AC-10 的语义拆分（已在回报里报给主线）</h3>
 * 既有基线 {@code Task0805CommitIgnoreUnboundTest.I-CMT-01} 证明：commit 的校验失败是<b>整包回滚、DB 零残留</b>。
 * 因此「10 个一起导、9 个成功 1 个失败」在当前事务语义下<b>不可能同时成立</b>。本类按 AC-10 的<b>目的</b>
 * （"不因<b>升版</b>而整包失败"）拆成两条可执行断言：
 * <ul>
 *   <li><b>AC-10-a</b>：把 COMP-0002 摘掉的 9 组件 1.0 包 → 必须 200 且 9 个全部落库
 *       （证明 1.0 格式本身没有被新版本拒绝）。</li>
 *   <li><b>AC-10-b</b>：完整 10 组件 1.0 包 → 若被拒，拒绝理由必须<b>归因到 COMP-0002</b>，
 *       <b>不得</b>是"包版本不支持"这类整包级理由。</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-10/AC-11 老包（1.0）降级导入")
class Ac10Ac11LegacyBundleImportTest extends Sb260915TestBase {

    private static final String TREE_TOKEN_CODE = "COMP-0002";

    /** 整包级拒绝的措辞特征 —— 出现任一即说明是"因为版本"而不是"因为那个组件"被拒。 */
    private static final List<String> WHOLE_BUNDLE_VERSION_REJECTION_HINTS = List.of(
            "不支持的包版本", "不支持的 bundleVersion", "包版本不兼容", "版本过低，无法导入", "UNSUPPORTED_BUNDLE_VERSION");

    // ══════════════════════════════════════════════════════════════════════
    // AC-10-a：9 个不含树 token 的组件，1.0 老包原样导入必须成功
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10-a: 老包（1.0）去掉 COMP-0002 后的 9 个组件 —— 导入 200 且 9 个全部落库")
    void legacyBundleWithoutTreeComponent_importsAllNine() throws Exception {
        ObjectNode bundle = loadLegacyBundle();

        // 前置 ①：素材确实是 1.0，否则本用例验的不是"老包兼容"
        assertEquals("1.0", bundle.path("bundleVersion").asText(),
                "前置：素材包的 bundleVersion 必须是 1.0，实际 " + bundle.path("bundleVersion")
                        + " —— 素材被换过，停下来报主线");

        ArrayNode kept = M.createArrayNode();
        for (JsonNode c : bundle.path("components")) {
            if (!TREE_TOKEN_CODE.equals(c.path("code").asText())) {
                kept.add(c);
            }
        }
        bundle.set("components", kept);
        // checksum 是对原始 components 算的，摘掉一个组件后必然对不上；
        // 本用例验的不是 checksum（既有基线 Task0805* 的 bundle 同样不带 checksum 照常导入）。
        bundle.remove("checksum");

        // 前置 ②：这 9 个必须真的一个树 token 都没有 —— 否则本用例失败时无法归因
        assertEquals(9, kept.size(), "前置：摘掉 COMP-0002 后应剩 9 个组件，实际 " + kept.size());
        String keptText = kept.toString();
        assertTrue(!keptText.contains("tree_ref") && !keptText.contains("tree_attr"),
                "前置：这 9 个组件里不该有 tree_ref/tree_attr —— 有的话 AC-10 与 AC-11 会混在一起无法归因");

        UUID dir = createDirectory("AC10A");
        Response r = commit(dir, bundle.toString(), "?conflictPolicy=RENAME");

        assertEquals(200, r.statusCode(),
                "AC-10：1.0 老包（无树 token）必须仍可导入，实际 HTTP " + r.statusCode()
                        + "\n → 若这里 400，说明升版把老包整包挡掉了，正是 AC-10 要防的事。body=" + r.asString());

        JsonNode data = M.readTree(r.asString()).path("data");
        // 期望值取自「包里实际有几个组件」，不写死字面量（主线更正：一律"查实际 → 相对断言 + 非空前置"）
        int expected = kept.size();
        assertEquals(expected, data.path("createdCount").asInt(-1),
                "AC-10：应创建 " + expected + " 个组件，实际 createdCount=" + data.path("createdCount")
                        + " body=" + r.asString());
        assertEquals((long) expected, componentCountIn(dir),
                "AC-10：目标目录（本片私有）里应恰好落库 " + expected + " 个组件 —— "
                        + "断言带 directory_id 限定，不用全局计数");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-10-b：完整 10 组件老包 —— 被拒也必须归因到 COMP-0002，而不是"版本不支持"
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10-b: 完整 10 组件老包 —— 失败必须归因到 COMP-0002，不得是整包级版本拒绝")
    void fullLegacyBundle_rejectionIsAttributedToTreeComponentNotVersion() throws Exception {
        ObjectNode bundle = loadLegacyBundle();
        assertEquals(10, bundle.path("components").size(), "前置：素材应有 10 个组件");

        UUID dir = createDirectory("AC10B");
        Response r = commit(dir, bundle.toString(), "?conflictPolicy=RENAME");
        String body = r.asString();
        System.out.println("[AC-10-b] HTTP " + r.statusCode() + " body=" + body);

        if (r.statusCode() == 200) {
            // 若后端能整包放行（例如把 1.0 的树组件按降级规则放过），也是合法结果：
            // AC-10 关心的是「不因升版而整包失败」。此时包里几个就该落几个。
            long expected = bundle.path("components").size();
            assertEquals(expected, componentCountIn(dir),
                    "整包放行时，目标目录里应落库 " + expected + " 个组件，实际 " + componentCountIn(dir));
            return;
        }

        assertEquals(400, r.statusCode(), "预期要么 200 要么 400（业务校验），实际 " + r.statusCode() + " body=" + body);
        String message = M.readTree(body).path("message").asText("");

        List<String> hit = new ArrayList<>();
        for (String h : WHOLE_BUNDLE_VERSION_REJECTION_HINTS) {
            if (message.contains(h)) {
                hit.add(h);
            }
        }
        assertTrue(hit.isEmpty(),
                "AC-10：老包被拒的理由不该是「包版本」级别的整包拒绝，命中措辞=" + hit + " message=" + message);

        assertTrue(message.contains(TREE_TOKEN_CODE) || message.contains("物料"),
                "AC-10：失败必须点名是哪个组件（COMP-0002/物料）导致的，实际 message=" + message);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-11：老包 + 树公式 → 两层信息的可定位报错
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11: 老包里的 COMP-0002（含 tree_ref）单独导入 —— 报错含「旧格式」+「源库升级后重新导出」两层信息")
    void legacyTreeComponent_errorMessageCarriesTwoLayers() throws Exception {
        ObjectNode bundle = loadLegacyBundle();

        ArrayNode only = M.createArrayNode();
        for (JsonNode c : bundle.path("components")) {
            if (TREE_TOKEN_CODE.equals(c.path("code").asText())) {
                only.add(c);
            }
        }
        // 前置：这一个组件必须真含 tree_ref，否则本用例根本触发不了那条报错（空跑）
        assertEquals(1, only.size(), "前置：素材里应有且仅有 1 个 COMP-0002");
        assertTrue(only.toString().contains("tree_ref"),
                "前置：COMP-0002 必须含 tree_ref token，实查素材有 2 处 —— 没有就触发不了 AC-11 那条校验");
        // 前置：1.0 老包不带 builderConfig（树身份的唯一凭据），这正是报错的成因
        assertTrue(!only.get(0).path("sqlViews").path(0).has("builderConfig")
                        || only.get(0).path("sqlViews").path(0).path("builderConfig").isNull(),
                "前置：1.0 老包的 sqlViews 不该有 builderConfig —— 有的话素材不是 1.0 原始包了");

        bundle.set("components", only);
        bundle.remove("checksum");

        UUID dir = createDirectory("AC11");
        Response r = commit(dir, bundle.toString(), "?conflictPolicy=RENAME");
        String body = r.asString();
        System.out.println("[AC-11] HTTP " + r.statusCode() + " body=" + body);

        assertEquals(400, r.statusCode(),
                "AC-11：1.0 老包里的 builder 树页签组件（带 tree_ref、没有 builderConfig）应被拒绝并给出可定位报错，"
                        + "实际 HTTP " + r.statusCode() + " body=" + body
                        + "\n → 若这里 200，说明它被放行了：AC-11 描述的场景不成立，停下来报主线（不要改断言迁就）");

        String message = M.readTree(body).path("message").asText("");

        // 第①层：包是旧格式（实现措辞「而这个导入包是旧格式（bundleVersion 1.0，…）」逐字命中）
        assertTrue(message.contains("导入包是旧格式"),
                "AC-11 第一层信息缺失：报错应说明「导入包是旧格式」，实际 message=" + message);

        // 第②层：出路。🚦 2026-09-15 用户裁决 —— AC 引号内是「信息要点」不是「逐字文案」，
        // 故按三个要点词判定，🚫 不做逐字 containsString（实现措辞「请在源库升级到含本次修复的
        // 版本后重新导出」比 AC 引文多一个定语，且用户/主线认定实现文案更好，保持不改）。
        List<String> missingPoints = new ArrayList<>();
        for (String point : List.of("源库", "升级", "重新导出")) {
            if (!message.contains(point)) {
                missingPoints.add(point);
            }
        }
        assertTrue(missingPoints.isEmpty(),
                "AC-11 第二层信息缺失：报错应给出「到源库升级后重新导出」这条出路，缺少要点 "
                        + missingPoints + "，实际 message=" + message);

        // 第③条：不再是孤立的「当前组件 tabType=(未配置)」——旧文案到此为止，新文案必须继续往下解释
        int idx = message.indexOf("tabType=(未配置)");
        if (idx >= 0) {
            String tail = message.substring(idx);
            assertTrue(tail.contains("旧格式") && tail.contains("重新导出"),
                    "AC-11：报错不该停在孤立的「当前组件 tabType=(未配置)」——它后面必须继续说明"
                            + "「包是旧格式」并给出出路。实际该句之后的内容=" + tail);
        }

        assertEquals(0L, componentCountIn(dir),
                "校验失败应整包回滚，目标目录（本片私有）零残留，实际 " + componentCountIn(dir));
    }
}
