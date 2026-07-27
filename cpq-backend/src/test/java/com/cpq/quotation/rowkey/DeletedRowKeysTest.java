package com.cpq.quotation.rowkey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeletedRowKeysTest {
    private final ObjectMapper M = new ObjectMapper();
    private JsonNode row(String json) throws Exception { return M.readTree(json); }

    @Test
    void fingerprintStableForSameValues() throws Exception {
        var fp1 = DeletedRowKeys.rowFingerprint(List.of("料件"), row("{\"料件\":\"P1\",\"单价\":7.12,\"启用\":true}"));
        var fp2 = DeletedRowKeys.rowFingerprint(List.of("料件"), row("{\"启用\":true,\"单价\":7.12,\"料件\":\"P1\"}"));
        assertEquals(fp1, fp2, "键序无关(driverRow 按键名排序)");
    }

    @Test
    void numberCanonTrimsTrailingZeros() throws Exception {
        var a = DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.10}"));
        var b = DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.1}"));
        assertEquals(a, b);
        assertTrue(DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.0}")).contains("7"));
    }

    @Test
    void keepMaskDeletesOnlyDoubleMatch() {
        var deleted = List.of(new DeletedRowKeys.Tombstone("K2", "fpB"));
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("K1", "K2", "K2"),
            List.of("fpA", "fpA", "fpB"),
            deleted);
        assertArrayEquals(new boolean[]{true, true, false}, mask);
    }

    @Test
    void parseRoundTrip() throws Exception {
        var ts = DeletedRowKeys.parse("[{\"effKey\":\"K1\",\"fp\":\"f1\"}]");
        assertEquals(1, ts.size());
        assertEquals("K1", ts.get(0).effKey());
        assertEquals("f1", ts.get(0).fp());
        assertTrue(DeletedRowKeys.parse(null).isEmpty());
        assertTrue(DeletedRowKeys.parse("[]").isEmpty());
    }

    /**
     * 夹具 1：撞键删中间剩余键不变。
     * 验证 keepMask 精确按 effKey+fp 双命中，不误伤撞键邻居（P1#1、P1#3 等）。
     */
    @Test
    void keepMaskPreciselyMatchesDisambiguatedKeys() {
        // 5 行已唯一化的 effKeys（模拟撞键消歧结果）
        List<String> effKeys = List.of("P1#1", "P1#2", "P1#3", "P2", "P3");
        List<String> fps     = List.of("fp_p1_1", "fp_p1_2", "fp_p1_3", "fp_p2", "fp_p3");
        // 墓碑只删中间 P1#2
        var deleted = List.of(new DeletedRowKeys.Tombstone("P1#2", "fp_p1_2"));
        boolean[] mask = DeletedRowKeys.keepMask(effKeys, fps, deleted);
        assertArrayEquals(
            new boolean[]{true, false, true, true, true},
            mask,
            "只有 P1#2 被删，#1/#3 及其他行不受影响"
        );
    }

    /**
     * 夹具 2：删行后源集增 1 行墓碑不误命中（漂移防护）。
     * fp 二次校验保证 effKey 相同但字段值变化的新行不被误删。
     */
    @Test
    void keepMaskDoesNotFalselyDeleteRowWithChangedValues() throws Exception {
        // 先对某行计算 fp，存为墓碑
        JsonNode oldRow = row("{\"料件\":\"P1\",\"单价\":100.0}");
        String oldFp = DeletedRowKeys.rowFingerprint(List.of("料件"), oldRow);
        var deleted = List.of(new DeletedRowKeys.Tombstone("P1", oldFp));

        // 新行：effKey 相同（P1），但字段值不同（单价变了）
        JsonNode newRow = row("{\"料件\":\"P1\",\"单价\":200.0}");
        String newFp = DeletedRowKeys.rowFingerprint(List.of("料件"), newRow);

        // 新旧 fp 应不同
        assertNotEquals(oldFp, newFp, "字段值变化后 fp 应不同");

        // keepMask：新行 effKey=P1，fp=newFp —— 与墓碑 fp 不同，应保留
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("P1"),
            List.of(newFp),
            deleted
        );
        assertTrue(mask[0], "fp 不同时，即使 effKey 相同，新行不应被误删（漂移防护）");
    }

    // =========================================================================
    // repair-0727 B1 —— nodeId 维度单测（api.md §2.2）
    // =========================================================================

    /** 新墓碑(含 nodeId) × 两条同 fp 不同 nodeId 的行 → 只删匹配的那条。 */
    @Test
    void keepMaskWithNodeId_sameContentDifferentNode_deletesOnlyMatchingNode() {
        // 两行内容(fp)完全相同(DAG 重复子件，driverRow 一致)，分别挂两个不同节点
        var deleted = List.of(new DeletedRowKeys.Tombstone("992", "fp_992", "S-3120014539/992"));
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("992", "992"),
            List.of("fp_992", "fp_992"),
            List.of("S-3120014539/992", "S-80011/992"),
            deleted);
        assertArrayEquals(new boolean[]{false, true}, mask,
            "只有 nodeId 命中(S-3120014539/992)的那条被删，另一节点(S-80011/992)保留");
    }

    /** 旧墓碑(无 nodeId) × 同上两行 → 两条都删（证明退化行为保持，BL-0055 已知残留边界）。 */
    @Test
    void keepMaskWithoutNodeId_sameContentDifferentNode_deletesBoth() {
        // 旧格式墓碑：nodeId=null（2-arg 构造）
        var deleted = List.of(new DeletedRowKeys.Tombstone("992", "fp_992"));
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("992", "992"),
            List.of("fp_992", "fp_992"),
            List.of("S-3120014539/992", "S-80011/992"),
            deleted);
        assertArrayEquals(new boolean[]{false, false}, mask,
            "旧墓碑无 nodeId → 退化 fp 单键匹配，两条同 fp 的行都判删（改造前逐字节行为）");
    }

    /** 非树行（nodeId=null）× 新墓碑（带 nodeId）→ 仍按 fp 匹配，不受 nodeId 维度影响。 */
    @Test
    void keepMaskWithNodeId_nonTreeRow_matchesByFpOnly() {
        var deleted = List.of(new DeletedRowKeys.Tombstone("K1", "fpA", "some-node-id"));
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("K1"),
            List.of("fpA"),
            java.util.Collections.singletonList(null), // 非树行：row.__nodeId == null
            deleted);
        assertArrayEquals(new boolean[]{false}, mask,
            "非树行(无 __nodeId) × 带 nodeId 的墓碑 → 按 api.md 规则(row.nodeId 为空即满足)仍应命中删除");
    }

    /** nodeIds 传 null(整体) 等价于旧 3 参重载：全部按 fp 单键匹配。 */
    @Test
    void keepMaskNullNodeIdsList_equivalentToLegacyThreeArgOverload() {
        var deleted = List.of(new DeletedRowKeys.Tombstone("992", "fp_992", "S-3120014539/992"));
        boolean[] mask4 = DeletedRowKeys.keepMask(
            List.of("992", "992"), List.of("fp_992", "fp_992"), null, deleted);
        boolean[] mask3 = DeletedRowKeys.keepMask(
            List.of("992", "992"), List.of("fp_992", "fp_992"), deleted);
        assertArrayEquals(mask3, mask4, "nodeIds=null 的 4 参调用必须与 3 参旧重载逐位一致");
        assertArrayEquals(new boolean[]{false, false}, mask4);
    }
}
