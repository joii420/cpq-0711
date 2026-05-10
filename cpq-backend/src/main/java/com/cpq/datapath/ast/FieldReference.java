package com.cpq.datapath.ast;

import java.util.Objects;

/**
 * 叶子字段引用 AST 节点。
 *
 * <p>路径末尾的字段名，例如：{@code mat_part.part_no} 中的 {@code part_no}，
 * 或 {@code 元素BOM[元素='Ag'].组成含量(%)} 中的 {@code 组成含量(%)}。
 */
public final class FieldReference implements AstNode {

    private final String name;

    public FieldReference(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("FieldReference name must not be blank");
        }
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitFieldReference(this);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldReference that)) return false;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
