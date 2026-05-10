package com.cpq.datapath.ast;

import java.util.Objects;

/**
 * 路径中的一段，对应一个 table/Sheet 引用，含可选谓词。
 *
 * <p>例如：
 * <ul>
 *   <li>{@code mat_part} → name="mat_part", predicate=null</li>
 *   <li>{@code 元素BOM[元素='Ag']} → name="元素BOM", predicate=EqPredicate(元素, Ag)</li>
 *   <li>{@code mat_part[customer_id IN ('u1','u2')]} → InPredicate</li>
 * </ul>
 */
public final class PathSegment implements AstNode {

    /** Sheet/Table 逻辑名（中文或英文，对应 BasicDataConfig.sheet_name） */
    private final String name;

    /** 可选谓词（null 表示无过滤） */
    private final Predicate predicate;

    public PathSegment(String name, Predicate predicate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PathSegment name must not be blank");
        }
        this.name      = name;
        this.predicate = predicate;
    }

    public String getName() {
        return name;
    }

    public Predicate getPredicate() {
        return predicate;
    }

    public boolean hasPredicate() {
        return predicate != null;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitPathSegment(this);
    }

    @Override
    public String toString() {
        if (predicate == null) return name;
        return name + "[" + predicate + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathSegment that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, predicate);
    }
}
