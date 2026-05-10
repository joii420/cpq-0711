package com.cpq.datapath.ast;

import java.util.List;
import java.util.Objects;

/**
 * 顶层路径表达式 AST 节点。
 *
 * <p>一条完整的路径由一个或多个 {@link PathSegment} 组成，
 * 例如：{@code mat_part.part_no} → [segment(mat_part), fieldRef(part_no)]
 * 嵌套：{@code A[k='v'].B[k='v'].C.field} → [segment(A,pred), segment(B,pred), segment(C), fieldRef(field)]
 */
public final class PathExpression implements AstNode {

    /** 路径中的各段（每段对应一个 table/Sheet 引用，含可选谓词） */
    private final List<PathSegment> segments;

    /** 末尾叶子字段（可为 null，表示路径以 table 级结束，如全行查询） */
    private final FieldReference leafField;

    public PathExpression(List<PathSegment> segments, FieldReference leafField) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("PathExpression must have at least one segment");
        }
        this.segments  = List.copyOf(segments);
        this.leafField = leafField;
    }

    public List<PathSegment> getSegments() {
        return segments;
    }

    public FieldReference getLeafField() {
        return leafField;
    }

    /** 是否只有单段（无嵌套） */
    public boolean isSingleSegment() {
        return segments.size() == 1;
    }

    /** 获取主段（第一段，通常是目标表） */
    public PathSegment getPrimarySegment() {
        return segments.get(0);
    }

    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visitPathExpression(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(segments.get(i));
        }
        if (leafField != null) {
            sb.append('.').append(leafField);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathExpression that)) return false;
        return Objects.equals(segments, that.segments) && Objects.equals(leafField, that.leafField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments, leafField);
    }
}
