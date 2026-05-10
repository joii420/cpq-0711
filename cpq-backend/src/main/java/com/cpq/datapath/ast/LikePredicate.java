package com.cpq.datapath.ast;

import java.util.Objects;

/**
 * LIKE 谓词。
 *
 * <p>示例：{@code part_no LIKE '%abc%'}
 * → field="part_no", pattern="%abc%"
 */
public final class LikePredicate extends Predicate {

    private final String field;
    private final String pattern;

    public LikePredicate(String field, String pattern) {
        this.field   = Objects.requireNonNull(field, "field");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
    }

    public String getField() {
        return field;
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitLikePredicate(this);
    }

    @Override
    public String toString() {
        return field + " LIKE '" + pattern + "'";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LikePredicate that)) return false;
        return Objects.equals(field, that.field) && Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, pattern);
    }
}
