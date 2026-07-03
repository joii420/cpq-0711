package com.cpq.quotation.service;

import java.util.*;

/** 递归行 → 分组产物 + 树 nodeId 生成(纯函数,无 DB)。 */
public final class CostingTreeGrouping {
    private CostingTreeGrouping() {}

    public static final class Result {
        public final Map<String, LinkedHashSet<String>> cardMaterialNo;
        public final List<String> totalMaterialNo;
        public final Map<String, List<CostingTreeNode>> treeRowsByRoot;
        Result(Map<String, LinkedHashSet<String>> c, List<String> t, Map<String, List<CostingTreeNode>> tr) {
            this.cardMaterialNo = c; this.totalMaterialNo = t; this.treeRowsByRoot = tr;
        }
    }

    public static Result group(List<CostingTreeNode> rows) {
        Map<String, LinkedHashSet<String>> cardMat = new LinkedHashMap<>();
        LinkedHashSet<String> total = new LinkedHashSet<>();
        Map<String, List<CostingTreeNode>> byRoot = new LinkedHashMap<>();

        for (CostingTreeNode r : rows) {
            cardMat.computeIfAbsent(r.rootNo, k -> new LinkedHashSet<>()).add(r.materialNo);
            total.add(r.materialNo);
            byRoot.computeIfAbsent(r.rootNo, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<CostingTreeNode>> e : byRoot.entrySet()) {
            String root = e.getKey();
            List<CostingTreeNode> tree = e.getValue();
            Map<String, String> firstNodeIdOfMat = new HashMap<>();
            int seq = 0;
            for (CostingTreeNode nd : tree) {
                nd.nodeId = root + "#" + (seq++);
                firstNodeIdOfMat.putIfAbsent(nd.materialNo, nd.nodeId);
            }
            for (CostingTreeNode nd : tree)
                nd.parentId = (nd.parentNo == null) ? null : firstNodeIdOfMat.get(nd.parentNo);
            Map<String, CostingTreeNode> byId = new HashMap<>();
            for (CostingTreeNode nd : tree) byId.put(nd.nodeId, nd);
            for (CostingTreeNode nd : tree) nd.lvl = depth(nd, byId, new HashSet<>());
        }
        return new Result(cardMat, new ArrayList<>(total), byRoot);
    }

    private static int depth(CostingTreeNode nd, Map<String, CostingTreeNode> byId, Set<String> seen) {
        if (nd.parentId == null || !seen.add(nd.nodeId)) return 1;
        CostingTreeNode p = byId.get(nd.parentId);
        return p == null ? 1 : 1 + depth(p, byId, seen);
    }
}
