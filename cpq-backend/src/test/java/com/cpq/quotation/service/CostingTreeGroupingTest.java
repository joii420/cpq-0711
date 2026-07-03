package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CostingTreeGroupingTest {
    private CostingTreeNode n(String root, String mat, String ver, String parent) {
        return new CostingTreeNode(root, mat, ver, parent);
    }

    @Test void cardMaterialNoIncludesRootAndDescendants() {
        List<CostingTreeNode> rows = List.of(
            n("P1","P1","v2",null), n("P1","A","v1","P1"), n("P1","B",null,"A"),
            n("P2","P2","v3",null), n("P2","A","v1","P2"));
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        assertEquals(Set.of("P1","A","B"), new HashSet<>(r.cardMaterialNo.get("P1")));
        assertEquals(Set.of("P2","A"), new HashSet<>(r.cardMaterialNo.get("P2")));
        assertEquals(Set.of("P1","A","B","P2"), new HashSet<>(r.totalMaterialNo));
    }

    @Test void multiOccurrencePreservedWithUniqueNodeIds() {
        List<CostingTreeNode> rows = List.of(
            n("P1","P1","v2",null), n("P1","A","v1","P1"),
            n("P1","B",null,"P1"), n("P1","A","v1","B"));
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("P1");
        long aCount = tree.stream().filter(x -> x.materialNo.equals("A")).count();
        assertEquals(2, aCount, "多 occurrence 必须保留两个 A 节点");
        Set<String> ids = new HashSet<>();
        for (CostingTreeNode x : tree) assertTrue(ids.add(x.nodeId), "nodeId 必须唯一: " + x.nodeId);
        assertEquals(List.of("P1","A","B"), new ArrayList<>(new LinkedHashSet<>(r.cardMaterialNo.get("P1"))));
    }

    @Test void rootNodeHasNullParentIdAndLvl1() {
        var r = CostingTreeGrouping.group(List.of(n("P1","P1","v1",null), n("P1","A",null,"P1")));
        CostingTreeNode root = r.treeRowsByRoot.get("P1").stream().filter(x->x.materialNo.equals("P1")).findFirst().get();
        assertNull(root.parentId);
        assertEquals(1, root.lvl);
    }
}
