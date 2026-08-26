package com.cpq.semanticgraph.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;

/**
 * 走写端点删除仍被引用的节点（task-260819 B-3，AC-54②）。
 * 与"直接 psql DELETE 被库层 FK 拦截"是两条独立防线：本异常是应用层的**友好预检**，
 * 列出还有哪些边 / 页签视图在引用该节点；真正兜底的仍是库层 RESTRICT 外键。
 */
public class SemanticNodeReferencedException extends BusinessException {

    private final List<String> referencingEdges;
    private final List<String> referencingTabViews;

    public SemanticNodeReferencedException(String message, List<String> referencingEdges, List<String> referencingTabViews) {
        super(409, message);
        this.referencingEdges = referencingEdges;
        this.referencingTabViews = referencingTabViews;
    }

    public List<String> getReferencingEdges() { return referencingEdges; }
    public List<String> getReferencingTabViews() { return referencingTabViews; }
}
