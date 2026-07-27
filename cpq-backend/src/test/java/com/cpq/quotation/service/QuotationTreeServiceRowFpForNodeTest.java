package com.cpq.quotation.service;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0727 B4 — {@code QuotationTreeService.computeRowFpForNode} 同节点多行精确定位单测。
 *
 * <p>纯 JUnit（无 DB/CDI，手工 new + 直接赋 package-private 字段，同包内合法，同
 * {@code QuotationTreeServicePartNoFieldTest} 既有模式）。
 */
class QuotationTreeServiceRowFpForNodeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private QuotationTreeService newService() {
        QuotationTreeService svc = new QuotationTreeService();
        svc.formulaCalculator = new FormulaCalculator();
        return svc;
    }

    private ArrayNode rows(String json) {
        try { return (ArrayNode) M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private QuotationTreeService.CompMeta compMeta(String rowKeyFieldsJson) {
        QuotationTreeService.CompMeta cm = new QuotationTreeService.CompMeta();
        cm.rowKeyFields = rowKeyFieldsJson;
        cm.fields = "[]";
        return cm;
    }

    /** 同 nodeId 下两条内容不同的行（N1 下 P1/P2），N2 下只有 1 行(P3)，供多个用例复用。 */
    private static final String TREE_ROWS = "["
        + "{\"driverRow\":{\"料号\":\"P1\"},\"basicDataValues\":{},\"__nodeId\":\"N1\"},"
        + "{\"driverRow\":{\"料号\":\"P2\"},\"basicDataValues\":{},\"__nodeId\":\"N1\"},"
        + "{\"driverRow\":{\"料号\":\"P3\"},\"basicDataValues\":{},\"__nodeId\":\"N2\"}"
        + "]";

    @Test
    void sameNodeTwoDifferentRows_deleteSecond_returnsSecondRowFp() {
        QuotationTreeService svc = newService();
        ArrayNode treeRows = rows(TREE_ROWS);
        QuotationTreeService.CompMeta cm = compMeta("[\"料号\"]");

        // 前端 __effKey 对齐 B0 口径：raw="P2"，nodeId 前缀化 → "N1::P2"（N1 下 P1/P2 互不重复，无需 #序号消歧）
        String fp = svc.computeRowFpForNode(treeRows, "N1", "N1::P2", cm);

        String expectedFp = DeletedRowKeys.rowFingerprint(List.of("料号"),
            treeRows.get(1).path("driverRow")); // row1 = P2
        assertEquals(expectedFp, fp, "rowKey=N1::P2 必须精确定位到第 2 行(P2)，不是第 1 行(P1)");

        String fpNotFirst = DeletedRowKeys.rowFingerprint(List.of("料号"), treeRows.get(0).path("driverRow"));
        assertNotEquals(fpNotFirst, fp, "不应错误地退回第一行(P1)的 fp（B4 修复前的 bug 行为）");
    }

    @Test
    void sameNodeTwoDifferentRows_deleteFirst_returnsFirstRowFp() {
        QuotationTreeService svc = newService();
        ArrayNode treeRows = rows(TREE_ROWS);
        QuotationTreeService.CompMeta cm = compMeta("[\"料号\"]");

        String fp = svc.computeRowFpForNode(treeRows, "N1", "N1::P1", cm);

        String expectedFp = DeletedRowKeys.rowFingerprint(List.of("料号"), treeRows.get(0).path("driverRow"));
        assertEquals(expectedFp, fp, "rowKey=N1::P1 必须精确定位到第 1 行(P1)");
    }

    @Test
    void singleRowUnderNode_behaviorUnchanged_ignoresRowKey() {
        QuotationTreeService svc = newService();
        ArrayNode treeRows = rows(TREE_ROWS);
        QuotationTreeService.CompMeta cm = compMeta("[\"料号\"]");

        // N2 下只有 1 行(P3)，即便 rowKey 传一个完全不相关/不匹配的值，单行短路直接返回该行 fp（行为不变）。
        String fp = svc.computeRowFpForNode(treeRows, "N2", "unrelated-garbage-rowkey", cm);

        String expectedFp = DeletedRowKeys.rowFingerprint(List.of("料号"), treeRows.get(2).path("driverRow"));
        assertEquals(expectedFp, fp, "单行节点应直接返回该行 fp，不受 rowKey 匹配与否影响（改动前行为）");
    }

    @Test
    void sameNodeMultipleRows_rowKeyNotMatchingAnyCandidate_fallsBackToFirstRow() {
        QuotationTreeService svc = newService();
        ArrayNode treeRows = rows(TREE_ROWS);
        QuotationTreeService.CompMeta cm = compMeta("[\"料号\"]");

        // N1 下有 2 行候选 effKey = {"N1::P1","N1::P2"}，rowKey 传一个两者都不匹配的值 → 退回第一条
        // (matches 收集顺序即 treeRows 原始出现顺序，第一条 = P1)，并在实现内部打印 LOG.warn
        // （日志内容以代码审查为准，本测试断言其"退回第一条"的可观察行为）。
        String fp = svc.computeRowFpForNode(treeRows, "N1", "N1::DOES-NOT-EXIST", cm);

        String expectedFp = DeletedRowKeys.rowFingerprint(List.of("料号"), treeRows.get(0).path("driverRow"));
        assertEquals(expectedFp, fp, "rowKey 未命中任何候选 → 退回第一条(P1)");
    }

    @Test
    void nodeIdNotFound_returnsNull() {
        QuotationTreeService svc = newService();
        ArrayNode treeRows = rows(TREE_ROWS);
        QuotationTreeService.CompMeta cm = compMeta("[\"料号\"]");

        assertNull(svc.computeRowFpForNode(treeRows, "N-NOT-EXIST", "whatever", cm));
    }
}
