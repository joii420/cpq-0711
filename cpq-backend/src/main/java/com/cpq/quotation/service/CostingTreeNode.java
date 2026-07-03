package com.cpq.quotation.service;

/** 递归 SQL 一行(+ 后端生成的树元数据)。 */
public final class CostingTreeNode {
    public final String rootNo, materialNo, bomVersion, parentNo;
    public String nodeId, parentId; public int lvl;
    public CostingTreeNode(String rootNo, String materialNo, String bomVersion, String parentNo) {
        this.rootNo = rootNo; this.materialNo = materialNo; this.bomVersion = bomVersion; this.parentNo = parentNo;
    }
}
