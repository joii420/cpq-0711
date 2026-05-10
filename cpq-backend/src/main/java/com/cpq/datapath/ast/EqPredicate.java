package com.cpq.datapath.ast;

import java.util.Objects;

/**
 * 等值/比较谓词，支持 =, !=, >, <, >=, <= 操作符。
 *
 * <p>示例：{@code element_name = 'Ag'} → field="element_name", op=EQ, value="Ag"
 */
public final class EqPredicate extends Predicate {

    public enum Op {
        EQ("="), NEQ("!="), GT(">"), LT("<"), GTE(">="), LTE("<=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    private final String field;
    private final Op     op;
    private final Object value;   // String, Number, Boolean, or nested PathExpression

    public EqPredicate(String field, Op op, Object value) {
        this.field = Objects.requireNonNull(field, "field");
        this.op    = Objects.requireNonNull(op, "op");
        this.value = value;
    }

    public String getField() {
        return field;
    }

    public Op getOp() {
        return op;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitEqPredicate(this);
    }

    @Override
    public String toString() {
        String valStr = (value instanceof String) ? "'" + value + "'" : String.valueOf(value);
        return field + " " + op.getSymbol() + " " + valStr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EqPredicate that)) return false;
        return Objects.equals(field, that.field) && op == that.op && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, op, value);
    }
}
