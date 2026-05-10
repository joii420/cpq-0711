package com.cpq.datapath.ast;

/**
 * AST 节点基接口，支持 Visitor 模式。
 */
public interface AstNode {
    <T> T accept(AstVisitor<T> visitor);
}
