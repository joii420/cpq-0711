package com.cpq.datapath.ast;

import java.util.List;
import java.util.Objects;

/**
 * IN 谓词。
 *
 * <p>示例：{@code customer_id IN ('uuid1','uuid2')}
 * → field="customer_id", values=["uuid1","uuid2"]
 */
public final class InPredicate extends Predicate {

    private final String       field;
    private final List<Object> values;  // 每个元素为 String / Number / Boolean

    public InPredicate(String field, List<Object> values) {
        this.field  = Objects.requireNonNull(field, "field");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    public String getField() {
        return field;
    }

    public List<Object> getValues() {
        return values;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitInPredicate(this);
    }

    @Override
    public String toString() {
        return field + " IN " + values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InPredicate that)) return false;
        return Objects.equals(field, that.field) && Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, values);
    }
}
