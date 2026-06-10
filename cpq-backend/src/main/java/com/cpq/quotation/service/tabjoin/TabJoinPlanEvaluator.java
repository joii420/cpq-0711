package com.cpq.quotation.service.tabjoin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/** 页签连表公式求值器：INNER JOIN 宽表 → WHERE → 两层聚合求值。纯逻辑、可单测、无 DB。 */
@ApplicationScoped
public class TabJoinPlanEvaluator {

    /** 一条 INNER JOIN 边：new 侧(leftTab/leftCols) 与已包含侧(rightTab/rightCols) 等值。 */
    public record Join(String leftTab, List<String> leftCols, String rightTab, List<String> rightCols) {}

    /**
     * 从各页签行 + joins 构建 INNER JOIN 宽表。宽表行键 = "别名.字段"。
     * 约定：join 图为连通树，每条 join 的一侧已在累积宽表中、另一侧是尚未加入的新页签。
     */
    public List<Map<String, Object>> buildWideRows(
            String mainTab,
            Map<String, List<Map<String, Object>>> tabRows,
            List<Join> joins) {

        // 1. 主页签行 → 前缀化
        List<Map<String, Object>> wide = new ArrayList<>();
        for (Map<String, Object> r : tabRows.getOrDefault(mainTab, List.of())) {
            wide.add(prefix(mainTab, r));
        }
        Set<String> included = new HashSet<>();
        included.add(mainTab);

        // 2. 逐条 join 引入新页签（顺序无关：每轮挑一条恰好一侧已包含的）
        List<Join> pending = new ArrayList<>(joins);
        while (!pending.isEmpty()) {
            Join picked = null;
            boolean newIsLeft = true;
            for (Join j : pending) {
                boolean lIn = included.contains(j.leftTab());
                boolean rIn = included.contains(j.rightTab());
                if (lIn ^ rIn) { picked = j; newIsLeft = !lIn; break; }
            }
            if (picked == null) break; // 剩余 join 无法连通（环/孤立）→ 忽略，按已连通部分算

            String newTab = newIsLeft ? picked.leftTab() : picked.rightTab();
            List<String> newCols = newIsLeft ? picked.leftCols() : picked.rightCols();
            String incTab = newIsLeft ? picked.rightTab() : picked.leftTab();
            List<String> incCols = newIsLeft ? picked.rightCols() : picked.leftCols();

            wide = joinIn(wide, incTab, incCols, newTab, newCols, tabRows.getOrDefault(newTab, List.of()));
            included.add(newTab);
            pending.remove(picked);
        }
        return wide;
    }

    private List<Map<String, Object>> joinIn(
            List<Map<String, Object>> wide, String incTab, List<String> incCols,
            String newTab, List<String> newCols, List<Map<String, Object>> newRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : wide) {
            for (Map<String, Object> nr : newRows) {
                if (keysEqual(w, incTab, incCols, nr, newCols)) {
                    Map<String, Object> merged = new LinkedHashMap<>(w);
                    merged.putAll(prefix(newTab, nr));
                    out.add(merged);
                }
            }
        }
        return out;
    }

    private boolean keysEqual(Map<String, Object> wide, String incTab, List<String> incCols,
                              Map<String, Object> newRow, List<String> newCols) {
        if (incCols.size() != newCols.size()) return false;
        for (int i = 0; i < incCols.size(); i++) {
            Object a = wide.get(incTab + "." + incCols.get(i));
            Object b = newRow.get(newCols.get(i));
            if (!Objects.equals(str(a), str(b))) return false;
        }
        return true;
    }

    /** 关联键等值用「去空白字符串」比较：页签行来自 JSONB,同一物料编码可能一侧是数字一侧是字符串,统一转字符串桥接。 */
    private static String str(Object o) { return o == null ? null : o.toString().trim(); }

    // ─────────────────────────────────────────────────────────────────
    // Task 4: 组内表达式两层求值
    // ─────────────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern TOKEN =
        java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");

    private final org.apache.commons.jexl3.JexlEngine jexl =
        new org.apache.commons.jexl3.JexlBuilder()
            .arithmetic(new SafeArithmetic()).strict(false).silent(true).create();

    /**
     * 组内表达式求值（两层作用域）：
     * 1. 先把每个聚合调用 FN(子表达式) 替换为标量字面量（子表达式逐行求值后 reduce）。
     * 2. 再把聚合外裸 [别名.字段] 替换为第一行值；[别名.小计]/[别名.总计] 用 subtotals。
     * 3. JEXL（SafeArithmetic）算最终标量。
     */
    public java.math.BigDecimal evalGroupExpression(
            String aggExpr, List<Map<String, Object>> rows, Map<String, java.math.BigDecimal> subtotals) {
        if (aggExpr == null || aggExpr.isBlank()) return java.math.BigDecimal.ZERO;
        String expr = replaceAggregates(aggExpr.trim(), rows);
        Map<String, Object> firstRow = rows.isEmpty() ? Map.of() : rows.get(0);
        expr = substituteScalarTokens(expr, firstRow, subtotals);
        Object r = jexl.createExpression(expr).evaluate(new org.apache.commons.jexl3.MapContext());
        return toBig(r);
    }

    /** 扫描 FN( ... ) 聚合调用（括号配平），逐行算内部子表达式 → reduce → 替换为字面量。 */
    private String replaceAggregates(String expr, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int parenOpen = expr.indexOf('(', fnStart);
            String fn = expr.substring(fnStart, parenOpen).trim().toUpperCase();
            int parenClose = matchParen(expr, parenOpen);
            String inner = expr.substring(parenOpen + 1, parenClose);
            out.append(expr, i, fnStart);
            out.append(reduceAgg(fn, inner, rows).toPlainString());
            i = parenClose + 1;
        }
        return out.toString();
    }

    /** 返回下一个聚合函数名起始下标（其后紧跟 `(`）；无则 -1。 */
    private int findAggCall(String expr, int from) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(?i)\\b(SUM|AVG|MIN|MAX|COUNT)\\s*\\(").matcher(expr);
        return m.find(from) ? m.start(1) : -1;
    }

    private int matchParen(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            if (s.charAt(k) == '(') depth++;
            else if (s.charAt(k) == ')') { depth--; if (depth == 0) return k; }
        }
        return s.length() - 1;
    }

    private java.math.BigDecimal reduceAgg(String fn, String inner, List<Map<String, Object>> rows) {
        List<java.math.BigDecimal> vals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String rowExpr = substituteRowTokens(inner, row);
            Object v = jexl.createExpression(rowExpr).evaluate(new org.apache.commons.jexl3.MapContext());
            if (v != null) vals.add(toBig(v));
        }
        return switch (fn) {
            case "SUM" -> vals.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            case "COUNT" -> java.math.BigDecimal.valueOf(vals.size());
            case "AVG" -> vals.isEmpty() ? java.math.BigDecimal.ZERO
                : vals.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                      .divide(java.math.BigDecimal.valueOf(vals.size()), 10, java.math.RoundingMode.HALF_UP);
            case "MIN" -> vals.stream().min(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO);
            case "MAX" -> vals.stream().max(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO);
            default -> java.math.BigDecimal.ZERO;
        };
    }

    /** 行级：把 [别名.字段] 替换成该行数值字面量（缺→0）。 */
    private String substituteRowTokens(String expr, Map<String, Object> row) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = row.get(m.group(1).trim());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(numLit(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 标量级（聚合外）：[别名.小计]/[别名.总计] 用 subtotals；其它 [别名.字段] 用第一行（缺→0）。 */
    private String substituteScalarTokens(String expr, Map<String, Object> firstRow,
                                          Map<String, java.math.BigDecimal> subtotals) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tok = m.group(1).trim();
            String lit;
            if (tok.endsWith(".小计") || tok.endsWith(".总计")) {
                java.math.BigDecimal s = subtotals.get(tok);
                lit = s != null ? s.toPlainString() : "0";
            } else {
                lit = numLit(firstRow.get(tok));
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(lit));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String numLit(Object v) {
        if (v == null) return "0";
        try { return new java.math.BigDecimal(v.toString().trim()).toPlainString(); }
        catch (Exception e) { return "0"; }
    }

    java.math.BigDecimal toBig(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        if (v instanceof java.math.BigDecimal b) return b;
        try { return new java.math.BigDecimal(v.toString()); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    private Map<String, Object> prefix(String alias, Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) m.put(alias + "." + e.getKey(), e.getValue());
        return m;
    }

    /** 一条 WHERE 条件。op ∈ {=,>,<,包含,不包含}；logic ∈ {AND,OR}（用于与下一条的连接，首条 logic 忽略）。 */
    public record Cond(String col, String op, String value, String logic) {}

    /** 按 where 过滤宽表行。多条按 logic 左折叠（无优先级，从左到右）。空条件 → 原样返回。 */
    public List<Map<String, Object>> applyWhere(List<Map<String, Object>> rows, List<Cond> where) {
        if (where == null || where.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean acc = evalCond(r, where.get(0));
            for (int i = 1; i < where.size(); i++) {
                boolean cur = evalCond(r, where.get(i));
                acc = "OR".equalsIgnoreCase(where.get(i).logic()) ? (acc || cur) : (acc && cur);
            }
            if (acc) out.add(r);
        }
        return out;
    }

    private boolean evalCond(Map<String, Object> row, Cond c) {
        Object cell = row.get(c.col());
        String cv = cell == null ? "" : cell.toString().trim();
        String v = c.value() == null ? "" : c.value().trim();
        return switch (c.op()) {
            case "=" -> cv.equals(v);
            case "包含" -> cv.contains(v);
            case "不包含" -> !cv.contains(v);
            case ">", "<" -> {
                try {
                    int cmp = new java.math.BigDecimal(cv).compareTo(new java.math.BigDecimal(v));
                    yield ">".equals(c.op()) ? cmp > 0 : cmp < 0;
                } catch (Exception e) { yield false; }
            }
            default -> false;
        };
    }
}
