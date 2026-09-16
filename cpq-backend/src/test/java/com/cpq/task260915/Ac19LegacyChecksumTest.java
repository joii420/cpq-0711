package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-19</b>：老包 checksum 不再误报。
 *
 * <p><b>起因</b>（需求文档 §③ AC-19）：立项时写的「checksum 无需特殊处理，老包仍自洽」<b>被后端 A/B 实验证伪</b>。
 * 导入端 {@code verifyChecksum} 拿<b>反序列化后的 DTO</b> 重算，DTO 加 8 字段后老包重算的 JSON 多出
 * {@code "treeConfig":null} 等 8 个键 ⇒ 字节不同 ⇒ 每份合法老包都被判「可能被改动或损坏」。
 * 裁决方案（甲）：8 个新字段加 {@code @JsonInclude(NON_NULL)} + 反射契约测试加守门。
 *
 * <h3>四条断言的分工</h3>
 * <ul>
 *   <li><b>阴性</b>（本类 T1）：老包预览 {@code checksumValid == true} 且 warnings 不含「改动或损坏」</li>
 *   <li><b>阳性对照</b>（本类 T2）：老包里改一个字 ⇒ {@code checksumValid == false}。
 *       🚫 <b>不能省</b> —— 只验阴性的话，<b>把 checksum 校验整个短路掉也能通过</b>，
 *       T1 就成了「断言从未真正执行」那类假绿。</li>
 *   <li><b>回归</b>（本类 T3）：1.1 自产包预览仍 {@code checksumValid == true}（别把老包修好、把新包修坏）</li>
 *   <li><b>防复发守门</b>：8 个新字段全部带 {@code @JsonInclude(NON_NULL)} ——
 *       在 {@link Ac7ExportContractGuardTest#eightRecoveredFieldsCarryJsonIncludeNonNull()}（与 AC-7 同一份反射测试）</li>
 *   <li><b>基线回归</b>：{@code Task0805ExportBindingReportTest} 回到 {@code Tests run: 23, Failures: 2}
 *       （一条不多）—— 脚本 {@code 实验/ac19-基线回归.sh}，不在本类里跑</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-19 老包 checksum 不误报")
class Ac19LegacyChecksumTest extends Sb260915TestBase {

    /** AC-19 原文点名的告警措辞特征（只取最稳的片段做子串匹配）。 */
    private static final String CORRUPT_HINT = "改动或损坏";

    // ══════════════════════════════════════════════════════════════════════
    // T1 · 阴性：合法老包不该被判损坏
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-19-a（阴性）: 1.0 老包原样预览 —— checksumValid=true，warnings 不含「改动或损坏」")
    void legacyBundleChecksumStillValid() throws Exception {
        ObjectNode bundle = loadLegacyBundle();

        // 前置：老包必须真带 checksum，否则"校验通过"可能只是因为根本没东西可校验
        String checksum = bundle.path("checksum").asText("");
        assertTrue(checksum.startsWith("sha256:"),
                "前置：老包应带 sha256 checksum，实际=" + checksum + " —— 没有 checksum 本条 AC 空跑");
        assertEquals("1.0", bundle.path("bundleVersion").asText(), "前置：素材必须是 1.0 老包");

        UUID dir = createDirectory("AC19A");
        Response r = preview(dir, bundle.toString(), "?conflictPolicy=RENAME");
        assertEquals(200, r.statusCode(), "预览应 200，body=" + r.asString());

        JsonNode data = M.readTree(r.asString()).path("data");
        System.out.println("[AC-19-a] checksumValid=" + data.path("checksumValid")
                + " warnings=" + data.path("warnings"));

        assertTrue(data.path("checksumValid").asBoolean(false),
                "AC-19：合法的 1.0 老包 checksum 必须仍然校验通过，实际 checksumValid="
                        + data.path("checksumValid")
                        + "\n → 失配的根因是导入端拿反序列化后的 DTO 重算：新加的 8 个字段以 null 写进 JSON，"
                        + "字节就变了。修法是给这 8 个字段加 @JsonInclude(NON_NULL)（需求文档 AC-19 方案甲）。"
                        + " warnings=" + data.path("warnings"));

        List<String> corruptWarnings = new ArrayList<>();
        for (JsonNode w : data.path("warnings")) {
            if (w.asText("").contains(CORRUPT_HINT)) {
                corruptWarnings.add(w.asText());
            }
        }
        assertTrue(corruptWarnings.isEmpty(),
                "AC-19：合法老包不该出现「可能被改动或损坏」类告警，实际: " + corruptWarnings);

        // 预览只读（api.md §二硬约束 3）
        assertEquals(0L, componentCountIn(dir), "预览不写库，目标目录应仍为 0 个组件");
    }

    // ══════════════════════════════════════════════════════════════════════
    // T2 · 阳性对照：改一个字必须被抓出来（证明校验没被短路）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-19-b（阳性对照）: 老包里改一个组件名一个字 —— checksumValid=false（证明校验没被关掉）")
    void tamperedLegacyBundleChecksumInvalid() throws Exception {
        ObjectNode bundle = loadLegacyBundle();
        String checksumBefore = bundle.path("checksum").asText("");
        assertTrue(checksumBefore.startsWith("sha256:"), "前置：老包应带 sha256 checksum");

        // 只改一个字，且 checksum 字段原样不动 —— 这正是"内容被改动"的最小形态
        ObjectNode first = (ObjectNode) bundle.path("components").get(0);
        String originalName = first.path("name").asText();
        String tamperedName = originalName + "改";
        first.put("name", tamperedName);

        // 前置：确认篡改真的落到了 payload 里（否则下面的 false 可能来自别的原因）
        assertEquals(tamperedName, bundle.path("components").get(0).path("name").asText(),
                "前置：篡改没生效，阳性对照无效");
        assertEquals(checksumBefore, bundle.path("checksum").asText(),
                "前置：checksum 字段必须保持原值不动，否则测的不是「内容被改」");

        UUID dir = createDirectory("AC19B");
        Response r = preview(dir, bundle.toString(), "?conflictPolicy=RENAME");
        assertEquals(200, r.statusCode(), "预览端点本身应 200（checksum 失配是报告项不是 HTTP 错误），body=" + r.asString());

        JsonNode data = M.readTree(r.asString()).path("data");
        System.out.println("[AC-19-b] 篡改 " + originalName + " → " + tamperedName
                + " checksumValid=" + data.path("checksumValid") + " warnings=" + data.path("warnings"));

        assertFalse(data.path("checksumValid").asBoolean(true),
                "AC-19 阳性对照：包内容被改了一个字，checksumValid 必须为 false，实际="
                        + data.path("checksumValid")
                        + "\n → 若这里也是 true，说明 checksum 校验被整个短路/删掉了，"
                        + "AC-19-a 的那条绿就是假绿（校验没跑，当然不会误报）");

        assertEquals(0L, componentCountIn(dir), "预览不写库");
    }

    // ══════════════════════════════════════════════════════════════════════
    // T3 · 回归：1.1 自产包也要继续自洽
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-19-c（回归）: 本系统自产的 1.1 包预览 —— checksumValid=true")
    void selfProducedBundleChecksumValid() throws Exception {
        UUID src = createDirectory("AC19C-SRC");
        UUID comp = insertComponent(src, PREFIX + "AC19-C1", PREFIX + "AC19 组件",
                "[{\"name\":\"甲\",\"field_type\":\"INPUT_TEXT\"}]", "[]",
                null, true, "元素编号", "元素单价", null);
        insertSqlView(comp, "rt_sb_ac19_view", "SELECT 1 AS x",
                "{\"dialect\":\"QUOTE\",\"tabType\":\"BOM\",\"switches\":null,\"axisScope\":\"CLOSURE\","
                        + "\"variantKey\":\"\",\"priceStrategy\":null,\"builderVersion\":1,\"columns\":[]}", 1);
        // 故意留一部分新字段为 null（treeConfig / elementCurrencyField）——
        // @JsonInclude(NON_NULL) 会让这些键在导出 JSON 里消失，本条正是要确认"键消失"不影响自产包自洽。

        Response ex = exportDirectory(src);
        assertEquals(200, ex.statusCode(), "导出应 200，body=" + ex.asString());
        JsonNode bundle = M.readTree(ex.asString());
        assertEquals("1.1", bundle.path("bundleVersion").asText(), "前置：自产包应是 1.1");
        assertTrue(bundle.path("checksum").asText("").startsWith("sha256:"),
                "前置：自产包应带 checksum，实际=" + bundle.path("checksum"));
        assertEquals(1, bundle.path("components").size(), "前置：自产包应含 1 个组件");

        UUID dst = createDirectory("AC19C-DST");
        Response r = preview(dst, bundle.toString(), "?conflictPolicy=RENAME");
        assertEquals(200, r.statusCode(), "预览应 200，body=" + r.asString());
        JsonNode data = M.readTree(r.asString()).path("data");
        System.out.println("[AC-19-c] 自产 1.1 包 checksumValid=" + data.path("checksumValid")
                + " warnings=" + data.path("warnings"));

        assertTrue(data.path("checksumValid").asBoolean(false),
                "AC-19 回归：自产 1.1 包的 checksum 必须自洽，实际 checksumValid="
                        + data.path("checksumValid") + " warnings=" + data.path("warnings")
                        + "\n → 别把老包修好、把新包修坏（导出端与导入端的序列化口径必须同步）");

        assertEquals(0L, componentCountIn(dst), "预览不写库");
    }
}
