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
}
