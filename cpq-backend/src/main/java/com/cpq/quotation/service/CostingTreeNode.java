package com.cpq.quotation.service;

/** 递归 SQL 一行(+ 后端生成的树元数据)。 */
public final class CostingTreeNode {
    public final String rootNo, materialNo, bomVersion, parentNo, nodePath;
    public String nodeId, parentId; public int lvl;
    public CostingTreeNode(String rootNo, String materialNo, String bomVersion, String parentNo, String nodePath) {
        this.rootNo = rootNo; this.materialNo = materialNo; this.bomVersion = bomVersion;
        this.parentNo = parentNo; this.nodePath = nodePath;
    }
}
