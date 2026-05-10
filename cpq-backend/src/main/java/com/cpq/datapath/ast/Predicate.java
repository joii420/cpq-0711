package com.cpq.datapath.ast;

/**
 * 谓词基类（抽象），所有过滤条件继承自此类。
 *
 * <p>子类：
 * <ul>
 *   <li>{@link EqPredicate} — 等值比较（含 !=, >, <, >=, <=）</li>
 *   <li>{@link InPredicate} — IN 谓词</li>
 *   <li>{@link LikePredicate} — LIKE 谓词</li>
 *   <li>{@link CompoundPredicate} — AND 复合谓词</li>
 * </ul>
 */
public abstract class Predicate implements AstNode {
    // 标记基类，不含具体字段
}
