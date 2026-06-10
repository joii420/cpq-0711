package com.cpq.quotation.service.tabjoin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/** 页签连表公式求值器（v2）：行键全外连对齐 → 加减项分段求值。纯逻辑、可单测、无 DB。 */
@ApplicationScoped
public class TabJoinPlanEvaluator {

    /** 关联键等值用「去空白字符串」比较：页签行来自 JSONB,同一物料编码可能一侧是数字一侧是字符串,统一转字符串桥接。 */
    static String str(Object o) { return o == null ? null : o.toString().trim(); }

    // ─────────────────────────────────────────────────────────────────
    // 公共令牌解析（DRY）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 公式令牌解析结果。
     * raw    = 原文（trim 后）
     * total  = 是否以"(总计)"结尾
     * alias  = "别名.列名" 的 别名 部分；无点时 alias=body
     * column = "别名.列名" 的 列名 部分；无点时 column=null
     *          total=true 且 column!=null → 列小计；total=true 且 column==null → 页签总计；
     *          total=false → 明细行令牌，alias 即页签别名
     */
    record Tok(String raw, boolean total, String alias, String column) {}

    static Tok parseTok(String raw) {
        raw = raw.trim();
        boolean total = raw.endsWith("(总计)");
        String body = total ? raw.substring(0, raw.length() - "(总计)".length()) : raw;
        int dot = body.indexOf('.');
        String alias  = dot >= 0 ? body.substring(0, dot) : body;
        String column = dot >= 0 ? body.substring(dot + 1) : null;
        return new Tok(raw, total, alias, column);
    }

    /**
     * 行键对齐（全外连）：把同一行键类的若干页签按 rowKeyFields 值对齐。
     * 行键值并集，每个行键组合出一行；某页签该行键缺行 → 其字段不并入(取值→null→0)。
     * 行键唯一 → 无笛卡尔放大。宽行键 = "别名.字段"。
     * @param tabRows 仅含"被表达式明细引用到"的页签 alias→rows
     */
    public List<Map<String, Object>> alignByRowKey(
            List<String> rowKeyFields, Map<String, List<Map<String, Object>>> tabRows) {
        LinkedHashMap<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (var e : tabRows.entrySet()) {
            String alias = e.getKey();
            for (Map<String, Object> r : e.getValue()) {
                String keyStr = keyOf(rowKeyFields, r);
                Map<String, Object> merged = byKey.computeIfAbsent(keyStr, k -> new LinkedHashMap<>());
                for (var fe : r.entrySet()) merged.put(alias + "." + fe.getKey(), fe.getValue());
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String keyOf(List<String> rowKeyFields, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (String k : rowKeyFields) sb.append(str(row.get(k))).append("");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // 共用常量与引擎
    // ─────────────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern TOKEN =
        java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");

    /** 聚合函数调用识别正则：匹配 SUM/AVG/MIN/MAX/COUNT 后紧跟 `(`；捕获组 1 = 函数名（用于 m.start(1) 定位）。 */
    private static final java.util.regex.Pattern AGG_CALL =
        java.util.regex.Pattern.compile("(?i)\\b(SUM|AVG|MIN|MAX|COUNT)\\s*\\(");


    private final org.apache.commons.jexl3.JexlEngine jexl =
        new org.apache.commons.jexl3.JexlBuilder()
            .arithmetic(new SafeArithmetic()).strict(false).silent(true).create();

    // ─────────────────────────────────────────────────────────────────
    // Task 4: v2 evalExpression — 加减项分段 + 裸明细默认求和
    // ─────────────────────────────────────────────────────────────────

    /**
     * v2 求值：按顶层 +/- 拆加减项；含"裸明细"(未被聚合函数圈住)的项→对齐行逐行算再求和；
     * 否则(明细全在聚合内/纯标量)→算一次。total 令牌(以"(总计)"结尾)从 scalars 取，detail 令牌逐行取。
     * @param alignedRows 行键对齐后的宽行（键 别名.字段）
     * @param scalars     总计令牌(raw,如 "回料(总计)"/"投料.金额(总计)")→值
     */
    public java.math.BigDecimal evalExpression(
            String expression, List<Map<String, Object>> alignedRows,
            Map<String, java.math.BigDecimal> scalars) {
        if (expression == null || expression.isBlank()) return java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (Term t : splitTerms(expression)) {
            String aggResolved = replaceAggregatesS(t.text, alignedRows, scalars);
            java.math.BigDecimal termVal;
            if (hasBareDetail(t.text)) {
                java.math.BigDecimal s = java.math.BigDecimal.ZERO;
                for (Map<String, Object> r : alignedRows) s = s.add(evalRow(aggResolved, r, scalars));
                termVal = s;
            } else {
                termVal = evalRow(aggResolved, java.util.Map.of(), scalars);
            }
            total = t.sign >= 0 ? total.add(termVal) : total.subtract(termVal);
        }
        return total;
    }

    private record Term(int sign, String text) {}

    /** 顶层 +/- 拆项（尊重括号），首项 sign=+1。 */
    private List<Term> splitTerms(String expr) {
        List<Term> out = new ArrayList<>();
        int depth = 0, sign = 1; StringBuilder cur = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '(') depth++; else if (ch == ')') depth--;
            if (depth == 0 && (ch == '+' || ch == '-') && cur.toString().trim().length() > 0) {
                out.add(new Term(sign, cur.toString())); cur.setLength(0); sign = ch == '+' ? 1 : -1;
            } else cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) out.add(new Term(sign, cur.toString()));
        return out;
    }

    /** 项内去掉聚合函数后仍有 detail 令牌(非 "(总计)" 结尾)？复用 parseTok 判断。 */
    private boolean hasBareDetail(String term) {
        String stripped = blankOutAggregates(term);
        java.util.regex.Matcher m = TOKEN.matcher(stripped);
        while (m.find()) { if (!parseTok(m.group(1)).total) return true; }
        return false;
    }

    /** 把 FN(...) 整体换成 "0"（用于判断裸明细），不递归。 */
    private String blankOutAggregates(String expr) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int open = expr.indexOf('(', fnStart), close = matchParen(expr, open);
            out.append(expr, i, fnStart).append('0'); i = close + 1;
        }
        return out.toString();
    }

    /** 同 v1 replaceAggregates，但内层逐行求值用 evalRow(带 scalars)。 */
    private String replaceAggregatesS(String expr, List<Map<String, Object>> rows,
                                      Map<String, java.math.BigDecimal> scalars) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int open = expr.indexOf('(', fnStart);
            String fn = expr.substring(fnStart, open).trim().toUpperCase();
            int close = matchParen(expr, open);
            String inner = expr.substring(open + 1, close);
            out.append(expr, i, fnStart);
            out.append(reduceAggS(fn, inner, rows, scalars).toPlainString());
            i = close + 1;
        }
        return out.toString();
    }

    private java.math.BigDecimal reduceAggS(String fn, String inner, List<Map<String, Object>> rows,
                                            Map<String, java.math.BigDecimal> scalars) {
        List<java.math.BigDecimal> vals = new ArrayList<>();
        for (Map<String, Object> r : rows) vals.add(evalRow(inner, r, scalars));
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

    /**
     * 单行求值：detail 令牌→该行值(缺0)；total 令牌("(总计)"结尾)→scalars(缺0)；JEXL(SafeArithmetic)。
     * 复用 parseTok 判断 total。使用 StringBuilder（JDK9+ Matcher 支持）。
     */
    private java.math.BigDecimal evalRow(String expr, Map<String, Object> row,
                                         Map<String, java.math.BigDecimal> scalars) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Tok tok = parseTok(m.group(1));
            String lit;
            if (tok.total()) {
                java.math.BigDecimal s = scalars.get(tok.raw()); lit = s != null ? s.toPlainString() : "0";
            } else lit = numLit(row.get(tok.raw()));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(lit));
        }
        m.appendTail(sb);
        Object r = jexl.createExpression(sb.toString()).evaluate(new org.apache.commons.jexl3.MapContext());
        return toBig(r);
    }

    // ─────────────────────────────────────────────────────────────────
    // 共用工具方法
    // ─────────────────────────────────────────────────────────────────

    /** 返回下一个聚合函数名起始下标（其后紧跟 `(`）；无则 -1。 */
    private int findAggCall(String expr, int from) {
        java.util.regex.Matcher m = AGG_CALL.matcher(expr);
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

    private String numLit(Object v) {
        if (v == null) return "0";
        try { return new java.math.BigDecimal(v.toString().trim()).toPlainString(); }
        catch (Exception e) { return "0"; }
    }

    private java.math.BigDecimal toBig(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        if (v instanceof java.math.BigDecimal b) return b;
        try { return new java.math.BigDecimal(v.toString()); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    // ─────────────────────────────────────────────────────────────────
    // Task 5 (v2): 整列求值入口 evaluateColumn
    // ─────────────────────────────────────────────────────────────────

    /**
     * v2 整列求值：解析 expression 引用的页签 → 取明细页签行按 rowKeyFields 全外连对齐 →
     * 收集总计令牌标量 → evalExpression。返回单值。
     */
    @SuppressWarnings("unchecked")
    public java.math.BigDecimal evaluateColumn(Map<String, Object> col,
                                               com.cpq.quotation.service.card.CardDataProvider provider) {
        String expr = (String) col.getOrDefault("expression", "");
        if (expr.isBlank()) return java.math.BigDecimal.ZERO;
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) col.getOrDefault("tabs", List.of());
        Map<String, String> tabKeyOf = new LinkedHashMap<>();
        Map<String, List<String>> rkfOf = new LinkedHashMap<>();
        for (Map<String, Object> t : tabs) {
            tabKeyOf.put((String) t.get("alias"), (String) t.get("tabKey"));
            rkfOf.put((String) t.get("alias"), (List<String>) t.getOrDefault("rowKeyFields", List.of()));
        }
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        java.util.LinkedHashSet<String> detailAliases = new java.util.LinkedHashSet<>();
        Map<String, java.math.BigDecimal> scalars = new LinkedHashMap<>();
        while (m.find()) {
            Tok tok = parseTok(m.group(1));
            if (tok.total()) {
                // 列小计（alias.column(总计)）或页签总计（alias(总计)）
                java.math.BigDecimal s;
                if (tok.column() != null) {
                    s = provider.subtotalOfColumn(tabKeyOf.get(tok.alias()), tok.column());
                } else {
                    s = provider.subtotalOf(tabKeyOf.get(tok.alias()));
                }
                scalars.put(tok.raw(), s != null ? s : java.math.BigDecimal.ZERO);
            } else {
                // 明细令牌：只有 alias 在 tabKeyOf 中声明的才加入对齐集
                if (tabKeyOf.containsKey(tok.alias())) {
                    detailAliases.add(tok.alias());
                }
                // 未声明的 alias 静默跳过，不参与对齐，也不抛 NPE
            }
        }
        List<Map<String, Object>> aligned = List.of();
        if (!detailAliases.isEmpty()) {
            // 选第一个在 tabKeyOf 中有声明且 rkf 非空的明细 alias 作对齐基准
            List<String> rkf = List.of();
            for (String alias : detailAliases) {
                List<String> candidate = rkfOf.getOrDefault(alias, List.of());
                if (!candidate.isEmpty()) { rkf = candidate; break; }
            }
            Map<String, List<Map<String, Object>>> tabRows = new LinkedHashMap<>();
            for (String alias : detailAliases) {
                String tabKey = tabKeyOf.get(alias);
                if (tabKey != null) tabRows.put(alias, provider.rowsOf(tabKey));
            }
            aligned = alignByRowKey(rkf, tabRows);
        }
        return evalExpression(expr, aligned, scalars);
    }
}
