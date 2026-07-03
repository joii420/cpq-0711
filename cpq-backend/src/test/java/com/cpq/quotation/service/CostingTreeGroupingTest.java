package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CostingTreeGroupingTest {
    private CostingTreeNode n(String root, String mat, String ver, String parent, String path) {
        return new CostingTreeNode(root, mat, ver, parent, path);
    }

    @Test void cardMaterialNoIncludesRootAndDescendants() {
        List<CostingTreeNode> rows = List.of(
            n("P1","P1","v2",null,"P1"), n("P1","A","v1","P1","P1/A"), n("P1","B",null,"A","P1/A/B"),
            n("P2","P2","v3",null,"P2"), n("P2","A","v1","P2","P2/A"));
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        assertEquals(Set.of("P1","A","B"), new HashSet<>(r.cardMaterialNo.get("P1")));
        assertEquals(Set.of("P2","A"), new HashSet<>(r.cardMaterialNo.get("P2")));
        assertEquals(Set.of("P1","A","B","P2"), new HashSet<>(r.totalMaterialNo));
    }

    @Test void multiOccurrencePreservedWithUniqueNodeIds() {
        List<CostingTreeNode> rows = List.of(
            n("P1","P1","v2",null,"P1"),
            n("P1","A","v1","P1","P1/A"),
            n("P1","B",null,"P1","P1/B"),
            n("P1","A","v1","B","P1/B/A"));
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("P1");
        long aCount = tree.stream().filter(x -> x.materialNo.equals("A")).count();
        assertEquals(2, aCount, "多 occurrence 必须保留两个 A 节点");
        Set<String> ids = new HashSet<>();
        for (CostingTreeNode x : tree) assertTrue(ids.add(x.nodeId), "nodeId 必须唯一: " + x.nodeId);
        assertEquals(List.of("P1","A","B"), new ArrayList<>(new LinkedHashSet<>(r.cardMaterialNo.get("P1"))));
    }

    @Test void childrenAttachToCorrectOccurrenceViaNodePath() {
        // A 出现两次:P1/A 和 P1/B/A;每个 A occurrence 各自有子件 C。
        List<CostingTreeNode> rows = List.of(
            n("P1","P1","v1",null,"P1"),
            n("P1","A","v1","P1","P1/A"),
            n("P1","C","v1","A","P1/A/C"),
            n("P1","B","v1","P1","P1/B"),
            n("P1","A","v1","B","P1/B/A"),
            n("P1","C","v1","A","P1/B/A/C"));
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("P1");
        CostingTreeNode c1 = tree.stream().filter(x -> x.nodeId.equals("P1/A/C")).findFirst().orElseThrow();
        CostingTreeNode c2 = tree.stream().filter(x -> x.nodeId.equals("P1/B/A/C")).findFirst().orElseThrow();
        assertEquals("P1/A", c1.parentId, "C 应挂到 P1/A 这个 A occurrence");
        assertEquals("P1/B/A", c2.parentId, "另一个 C 应挂到 P1/B/A 这个 A occurrence,不挂错枝");
        assertEquals(3, c1.lvl);
        assertEquals(4, c2.lvl);
    }

    @Test void rootNodeHasNullParentIdAndLvl1() {
        var r = CostingTreeGrouping.group(List.of(n("P1","P1","v1",null,"P1"), n("P1","A",null,"P1","P1/A")));
        CostingTreeNode root = r.treeRowsByRoot.get("P1").stream().filter(x->x.materialNo.equals("P1")).findFirst().get();
        assertNull(root.parentId);
        assertEquals(1, root.lvl);
    }
}
