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
            List<CostingTreeNode> tree = e.getValue();
            int seq = 0;
            for (CostingTreeNode nd : tree) {
                String path = nd.nodePath;
                if (path == null || path.isBlank()) {
                    // 兜底:node_path 缺失(应被保存期校验拦住)→ 平铺,视为配置错误
                    nd.nodeId = nd.rootNo + "#" + seq;
                    nd.parentId = null;
                    nd.lvl = 1;
                } else {
                    nd.nodeId = path;
                    int i = path.lastIndexOf('/');
                    nd.parentId = (i < 0) ? null : path.substring(0, i);
                    int lvl = 1;
                    for (int k = 0; k < path.length(); k++) if (path.charAt(k) == '/') lvl++;
                    nd.lvl = lvl;
                }
                seq++;
            }
        }
        return new Result(cardMat, new ArrayList<>(total), byRoot);
    }
}
