package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二批 · <b>AC-29①②（料号列/名称列的强制校验按数据源语义生效）</b>。
 *
 * <h3>AC 原文（需求文档.md §3.3）</h3>
 * 操作：新建三个组件并保存 —— A 绑「物料BOM」（{@code semantic='TREE'}）不配料号列也不配名称列；
 * B 绑「自制加工费」（{@code semantic=null}）两者都不配；C 绑「自制加工费」只配名称列。断言：
 * <ol>
 *   <li><b>A 保存成功 200</b> —— 树页签取系统列 {@code __hfPartNo}，可不配；</li>
 *   <li><b>B 保存失败 400</b>，文案指出「需配置料号列或名称列至少一个作为匹配标识」；</li>
 *   <li><b>C 保存成功 200</b> —— 「至少一个」，只配名称列合法；</li>
 *   <li>存量 114 个 {@code tab_type} 为空且无 builder 绑定的组件仍可正常保存（AC-15 的放行分支未被改坏）。</li>
 * </ol>
 *
 * <h3>本类覆盖范围</h3>
 * <b>① 与 ②（阴性对照）</b>为本次派工点名的范围，本类全覆盖。<br>
 * <b>③ 未覆盖 —— 无法构造</b>：全库语义图里<b>没有任何一列带 {@code PART_NAME} 角色</b>
 * （实测三方言 39 个数据源共 147 个角色标记：{@code ROW_KEY} 84 / {@code PART_NO} 39 / {@code SORT} 24，
 * {@code PART_NAME} 0）。⇒ 「绑自制加工费只配名称列」在取数配置器里配不出来。
 * 🚫 本类不把它改写成「配料号列」之类能过的断言（那是换掉了 AC 要验的东西）。
 * 已列为交付缺口上报主线，见 test.md §7。<br>
 * <b>④ 已由第一批 {@code LegacyZeroChangeAcTest.ac15_unconfiguredComponentsUnaffected} 覆盖</b>，本类不重复。
 *
 * <h3>🚨 为什么必须有阴性对照</h3>
 * 只验 ①（A 保存 200）的话，把整个校验删掉也会绿。② 证明校验<b>确实还在、且还会拒</b>，
 * 两条一起才说明「按 semantic 分流」而不是「一律放行」。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-29 —— 料号列/名称列强制校验按数据源 semantic 分流：TREE 豁免、普通源仍拒")
class IdentifierColumnGateAcTest extends Batch2Base {

    /** 树数据源，一列标识列都不配（既不 isPartNo 也不 isRowKey），只留一个普通业务列。 */
    private static final String CFG_TREE_NO_IDENTIFIER = """
            { "tabType": "BOM", "variantKey": "", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"MATERIAL_BOM","sourceColumn":"component_qty","fieldName":"组成数量"}
            ]}
            """;

    /** 普通数据源（semantic=null），同样一列标识列都不配。 */
    private static final String CFG_FEE_NO_IDENTIFIER = """
            { "tabType": "费用类", "variantKey": "SELF_PROCESS_FEE", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"SELF_PROCESS_FEE","sourceColumn":"value","fieldName":"值"}
            ]}
            """;

    @Test
    @DisplayName("AC-29①②：A(物料BOM/TREE) 不配料号列与名称列 → 200；B(自制加工费/null) 同样不配 → 400 INSPECT_BLOCKED")
    void ac29_identifierGateFollowsDataSourceSemantic() {
        // ── 前置：确认两个数据源的 semantic 真的是 TREE / null，否则本用例测的不是它自以为在测的分流 ──
        assertSemanticIs("BOM", "", "TREE", "AC-29 前置A");
        assertSemanticIs("费用类", "SELF_PROCESS_FEE", null, "AC-29 前置B");

        // ── 前置：确认配置里真的没有任何可推导为标识列的列（否则 A 的 200 是「配了料号列」换来的，非豁免）──
        assertNoIdentifierRole("BOM", "", "component_qty", "AC-29 前置A");
        assertNoIdentifierRole("费用类", "SELF_PROCESS_FEE", "value", "AC-29 前置B");

        // ── ① A：树数据源豁免，保存成功 ──
        UUID a = createBlankComponent("A29-tree");
        Response ra = saveBuilder(a, CFG_TREE_NO_IDENTIFIER);
        assertReachedBusinessLayer(ra, "AC-29①");
        assertEquals(200, ra.statusCode(), "AC-29①：绑 TREE 语义数据源、料号列与名称列都不配，"
                + "保存应成功 200（树页签取系统列 __hfPartNo，与改动前 tabType='BOM' 的豁免同口径），"
                + "实际=" + ra.statusCode() + " body=" + ra.asString());
        assertBuilderVersionPresent(a, "AC-29①");

        // ── ② B（阴性对照）：普通数据源仍被拒，文案不变 ──
        UUID b = createBlankComponent("A29-fee");
        Response rb = saveBuilder(b, CFG_FEE_NO_IDENTIFIER);
        assertReachedBusinessLayer(rb, "AC-29②");
        assertEquals(400, rb.statusCode(), "AC-29②：绑普通数据源（semantic=null）且料号列/名称列都不配，"
                + "保存应仍被拒 400 —— 只放行 A 而不拒 B，等于把校验整条删了。实际=" + rb.statusCode()
                + " body=" + rb.asString());
        assertEquals("INSPECT_BLOCKED", errorCode(rb),
                "AC-29②：错误码应为 INSPECT_BLOCKED，实际 body=" + rb.asString());

        // 文案：AC 原文「需配置料号列或名称列至少一个作为匹配标识」。措辞不是逐字契约，
        // 但「料号列」「名称列」「至少」三个语义片段必须同时出现 —— 否则用户看不出要配什么。
        String body = rb.asString();
        for (String fragment : List.of("料号列", "名称列", "至少")) {
            assertTrue(body.contains(fragment), "AC-29②：拒绝文案里缺少语义片段「" + fragment
                    + "」，用户无从知道要配什么。实际 body=" + body);
        }
        System.out.println("[AC-29②] 拒绝文案实测=" + body);

        // ── 落库自检：B 被拒后不得留下半截配置（否则下一次打开配置页会读到幽灵状态）──
        String bv = scalar("SELECT builder_version::text FROM component_sql_view WHERE component_id = '" + b + "'");
        assertTrue(bv == null, "AC-29②：保存被 400 拒绝后，component_sql_view.builder_version 不应被写入，实际=" + bv);
        System.out.println("[AC-29] A=" + a + " 保存 200 且 builder_version 已写；B=" + b
                + " 保存 400 且 builder_version=" + bv);
    }

    // ═══════════════════════════ 前置断言（防止用例测错分支）═══════════════════════════

    private void assertSemanticIs(String tabType, String variantKey, String expected, String acRef) {
        for (Map<String, Object> s : availableSources("QUOTE", acRef)) {
            if (tabType.equals(String.valueOf(s.get("tabType")))
                    && variantKey.equals(String.valueOf(s.get("variantKey")))) {
                Object sem = s.get("semantic");
                assertEquals(expected, sem == null ? null : String.valueOf(sem),
                        acRef + "：坐标 " + tabType + "/" + variantKey + " 的 semantic 应为 " + expected
                                + "，实际=" + sem + " ⇒ 本用例验的不是它自以为在验的那条分流分支。");
                return;
            }
        }
        throw new AssertionError(acRef + "：QUOTE 清单里找不到坐标 " + tabType + "/" + variantKey
                + " ⇒ 用例前置不成立。");
    }

    /**
     * 断言这一列在语义图里<b>不带</b>标识类角色，否则 A 的 200 可能是「其实配了料号列」换来的。
     *
     * <p>🚨 按<b>坐标解析出的锚点节点 id</b> 查，🚫 不按 {@code node_key}：
     * {@code MATERIAL_BOM} 在三个方言下各有一个节点，只按 key 查会命中 3 行、且跨方言拿到别的表的列。
     */
    private void assertNoIdentifierRole(String tabType, String variantKey, String column, String acRef) {
        String nodeId = anchorNodeId("QUOTE", tabType, variantKey);
        assertNotNull(nodeId, acRef + "：查不到 QUOTE/" + tabType + "/" + variantKey + " 的锚点节点 ⇒ 前置不成立。");

        long exists = count("SELECT count(*) FROM semantic_node_column WHERE node_id = '" + nodeId
                + "' AND db_column = '" + column + "' AND status = 'ACTIVE'");
        assertEquals(1L, exists, acRef + "：列 " + tabType + "/" + variantKey + "." + column
                + " 在 QUOTE 方言的语义图里不存在或非 ACTIVE ⇒ 保存会因 PHYSICAL_EXISTENCE 失败，与本 AC 无关。"
                + "该节点实有列=" + activeColumnsOf(nodeId));

        long hit = count("SELECT count(*) FROM semantic_node_column WHERE node_id = '" + nodeId
                + "' AND db_column = '" + column + "' AND status = 'ACTIVE' "
                + "AND (roles && ARRAY['PART_NO','PART_NAME','ROW_KEY']::text[])");
        assertEquals(0L, hit, acRef + "：所选列 " + tabType + "/" + variantKey + "." + column
                + " 在语义图里带有 PART_NO/PART_NAME/ROW_KEY 角色 ⇒ 「一列标识列都不配」这个前提不成立，"
                + "本用例的 200/400 都不能归因于 semantic 分流。");
    }
}
