package com.cpq.datapath.ast;

/**
 * AST Visitor 接口，便于 PathToSqlGenerator 及后续阶段使用。
 *
 * @param <T> 访问结果类型
 */
public interface AstVisitor<T> {

    T visitPathExpression(PathExpression node);

    T visitPathSegment(PathSegment node);

    T visitFieldReference(FieldReference node);

    T visitEqPredicate(EqPredicate node);

    T visitInPredicate(InPredicate node);

    T visitLikePredicate(LikePredicate node);

    T visitCompoundPredicate(CompoundPredicate node);
}
