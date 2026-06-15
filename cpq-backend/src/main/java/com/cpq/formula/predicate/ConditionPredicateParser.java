package com.cpq.formula.predicate;

import java.util.ArrayList;
import java.util.List;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/**
 * 条件文本 → predicate 模型。文法见 spec §5。
 * [页签.字段]：首个出现的页签前缀=source(A)→SourceField，其余前缀=host(B)→HostField；字段取 '.' 后段。
 */
public class ConditionPredicateParser {

    private String src;
    private int pos;
    private String firstTab;   // 首个页签前缀，定 source 侧

    public ConditionPredicate parse(String cond) {
        this.src = cond == null ? "" : cond;
        this.pos = 0;
        this.firstTab = null;
        ConditionPredicate p = orExpr();
        skipWs();
        if (pos != src.length()) throw new IllegalArgumentException("条件解析残留: " + src.substring(pos));
        return p;
    }

    private ConditionPredicate orExpr() {
        List<ConditionPredicate> parts = new ArrayList<>();
        parts.add(andExpr());
        while (matchKeyword("OR")) parts.add(andExpr());
        return parts.size() == 1 ? parts.get(0) : new Bool(BoolOp.OR, parts);
    }

    private ConditionPredicate andExpr() {
        List<ConditionPredicate> parts = new ArrayList<>();
        parts.add(cmpExpr());
        while (matchKeyword("AND")) parts.add(cmpExpr());
        return parts.size() == 1 ? parts.get(0) : new Bool(BoolOp.AND, parts);
    }

    private ConditionPredicate cmpExpr() {
        skipWs();
        if (peek() == '(') {
            pos++;                       // 吃 '('
            ConditionPredicate inner = orExpr();
            skipWs();
            if (peek() != ')') throw new IllegalArgumentException("缺右括号");
            pos++;                       // 吃 ')'
            return inner;
        }
        Operand lhs = operand();
        String op = readOperator();
        Operand rhs = operand();
        return new Comparison(CmpOp.from(op), lhs, rhs);
    }

    private Operand operand() {
        skipWs();
        char c = peek();
        if (c == '[') {                  // [页签.字段]
            int close = src.indexOf(']', pos);
            if (close < 0) throw new IllegalArgumentException("缺 ]");
            String inner = src.substring(pos + 1, close).trim();
            pos = close + 1;
            int dot = inner.lastIndexOf('.');
            String tab = dot >= 0 ? inner.substring(0, dot) : "";
            String field = dot >= 0 ? inner.substring(dot + 1) : inner;
            if (firstTab == null) firstTab = tab;
            return tab.equals(firstTab) ? new SourceField(field) : new HostField(field);
        }
        if (c == '\'' || c == '"') {     // 字符串字面量
            char q = c; pos++;
            int close = src.indexOf(q, pos);
            if (close < 0) throw new IllegalArgumentException("字符串未闭合");
            String v = src.substring(pos, close);
            pos = close + 1;
            return new Literal(v);
        }
        // 数字字面量
        int start = pos;
        while (pos < src.length() && (Character.isDigit(peek()) || peek() == '.' || peek() == '-')) pos++;
        if (pos == start) throw new IllegalArgumentException("非法操作数 @" + pos);
        return new Literal(src.substring(start, pos).trim());
    }

    private String readOperator() {
        skipWs();
        for (String op : new String[]{">=", "<=", "<>", "!=", "=", ">", "<"}) {
            if (src.startsWith(op, pos)) { pos += op.length(); return op; }
        }
        throw new IllegalArgumentException("缺运算符 @" + pos);
    }

    private boolean matchKeyword(String kw) {
        skipWs();
        if (pos + kw.length() <= src.length()
                && src.substring(pos, pos + kw.length()).equalsIgnoreCase(kw)) {
            int after = pos + kw.length();
            // 关键字后须是空白/括号/结尾，避免吞掉 ANDxxx
            if (after == src.length() || Character.isWhitespace(src.charAt(after)) || src.charAt(after) == '(') {
                pos = after; return true;
            }
        }
        return false;
    }

    private void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
    private char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }
}
