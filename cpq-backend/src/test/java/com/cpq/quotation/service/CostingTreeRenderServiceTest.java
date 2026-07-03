package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CostingTreeRenderService} 的行装配纯函数单测（不碰 DB，仅测 treeRowNode/flatRowNode）。
 */
class CostingTreeRenderServiceTest {

    private ExpandDriverResponse.Row bizRow(String materialNo, Object cost) {
        ExpandDriverResponse.Row r = new ExpandDriverResponse.Row();
        Map<String, Object> driverRow = new LinkedHashMap<>();
        driverRow.put("material_no", materialNo);
        driverRow.put("cost", cost);
        r.driverRow = driverRow;
        r.basicDataValues = new LinkedHashMap<>();
        return r;
    }

    @Test
    void treeRowNodeInjectsSystemColsAndBiz() {
        CostingTreeNode node = new CostingTreeNode("P1", "A", "v1", "P1", "P1/A");
        node.nodeId = "P1/A";
        node.parentId = "P1";
        node.lvl = 2;

        ObjectNode row = CostingTreeRenderService.treeRowNode(node, bizRow("A", new BigDecimal("100")));

        // 系统列
        assertEquals("P1/A", row.get("__nodeId").asText());
        assertEquals("P1", row.get("__parentId").asText());
        assertEquals(2, row.get("__lvl").asInt());
        assertEquals("A", row.get("__hfPartNo").asText());
        assertEquals("P1", row.get("__parentNo").asText());
        assertEquals("v1", row.get("__bomVersion").asText());

        // 业务列必须嵌在 driverRow 下（对齐 CardSnapshotService#spineRowNode 的契约）
        assertTrue(row.has("driverRow"));
        assertEquals("A", row.get("driverRow").get("material_no").asText());
        assertEquals(100, row.get("driverRow").get("cost").asInt());
        assertTrue(row.has("basicDataValues"));
    }

    @Test
    void treeRowNodeEmptyBizStillHasSystemCols() {
        CostingTreeNode node = new CostingTreeNode("P1", "B", null, "P1", "P1/B");
        node.nodeId = "P1/B";
        node.parentId = "P1";
        node.lvl = 2;

        ObjectNode row = CostingTreeRenderService.treeRowNode(node, null);

        // 系统列仍在
        assertEquals("P1/B", row.get("__nodeId").asText());
        assertEquals("P1", row.get("__parentId").asText());
        assertEquals(2, row.get("__lvl").asInt());
        assertEquals("B", row.get("__hfPartNo").asText());
        assertEquals("P1", row.get("__parentNo").asText());
        assertTrue(row.get("__bomVersion").isNull());

        // 业务行缺失 -> driverRow/basicDataValues 均为空对象（非缺失）
        assertTrue(row.has("driverRow"));
        assertTrue(row.get("driverRow").isObject());
        assertEquals(0, row.get("driverRow").size());
        assertTrue(row.has("basicDataValues"));
        assertFalse(row.get("driverRow").has("cost"));
    }

    @Test
    void rootNodeNullParentIdSerializesAsJsonNull() {
        CostingTreeNode root = new CostingTreeNode("P1", "P1", "v2", null, "P1");
        root.nodeId = "P1";
        root.parentId = null;
        root.lvl = 1;

        ObjectNode row = CostingTreeRenderService.treeRowNode(root, bizRow("P1", new BigDecimal("1")));
        assertTrue(row.get("__parentId").isNull());
        assertTrue(row.get("__parentNo").isNull());
    }

    @Test
    void flatRowNodeHasNoSystemCols() {
        ObjectNode row = CostingTreeRenderService.flatRowNode(bizRow("A", new BigDecimal("5")));
        assertEquals("A", row.get("driverRow").get("material_no").asText());
        assertFalse(row.has("__nodeId"));
        assertFalse(row.has("__lvl"));
    }
}
