package com.cpq.formula.predicate;

import java.util.List;

/** 条件 predicate 不可变模型。见 plan「Predicate 规范数据结构」。 */
public sealed interface ConditionPredicate
        permits ConditionPredicate.Bool, ConditionPredicate.Comparison {

    enum BoolOp { AND, OR }

    /** 比较运算符；TEXT/parse 时把 "<>" 归一到 NE。 */
    enum CmpOp {
        EQ("="), NE("!="), GT(">"), LT("<"), GE(">="), LE("<=");
        public final String text;
        CmpOp(String t) { this.text = t; }
        public static CmpOp from(String s) {
            return switch (s.trim()) {
                case "=" -> EQ;
                case "!=", "<>" -> NE;
                case ">" -> GT;
                case "<" -> LT;
                case ">=" -> GE;
                case "<=" -> LE;
                default -> throw new IllegalArgumentException("未知运算符: " + s);
            };
        }
    }

    sealed interface Operand permits SourceField, HostField, Literal {}
    record SourceField(String field) implements Operand {}
    record HostField(String field) implements Operand {}
    record Literal(String value) implements Operand {}

    record Bool(BoolOp op, List<ConditionPredicate> children) implements ConditionPredicate {}
    record Comparison(CmpOp op, Operand lhs, Operand rhs) implements ConditionPredicate {}
}
