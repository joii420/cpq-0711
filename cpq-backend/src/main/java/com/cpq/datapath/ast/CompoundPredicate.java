package com.cpq.datapath.ast;

import java.util.List;
import java.util.Objects;

/**
 * 复合谓词（AND 连接多个子谓词）。
 *
 * <p>示例：{@code a='x' AND b='y'} → terms=[EqPredicate(a,x), EqPredicate(b,y)]
 */
public final class CompoundPredicate extends Predicate {

    public enum LogicOp {
        AND
    }

    private final LogicOp        op;
    private final List<Predicate> terms;

    public CompoundPredicate(LogicOp op, List<Predicate> terms) {
        this.op    = Objects.requireNonNull(op, "op");
        this.terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
        if (this.terms.size() < 2) {
            throw new IllegalArgumentException("CompoundPredicate requires at least 2 terms");
        }
    }

    public LogicOp getOp() {
        return op;
    }

    public List<Predicate> getTerms() {
        return terms;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitCompoundPredicate(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(terms.get(i));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompoundPredicate that)) return false;
        return op == that.op && Objects.equals(terms, that.terms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, terms);
    }
}
