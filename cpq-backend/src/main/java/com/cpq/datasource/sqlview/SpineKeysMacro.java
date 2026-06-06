package com.cpq.datasource.sqlview;

/**
 * {@code :spineKeys(料号列, 父料号列, 版本列)} 宏展开（纯函数）。
 *
 * <p>把宏文本替换成 NULL-safe 的 EXISTS+unnest 子句，按位绑定：
 * 第 1 实参 ↔ k.p（子件料号）、第 2 ↔ k.pp（父件料号）、第 3 ↔ k.v（子件自身版本）。
 *
 * <p>两种形态：
 * <ul>
 *   <li>{@link #expandForExecution}：数组来自命名参数 {@code :__skP/:__skPP/:__skV}（运行时由 SqlViewExecutor 注入）；</li>
 *   <li>{@link #expandForValidation}：数组用 {@code ARRAY[]::text[]} 字面量（保存期 dry-run，无占位符，不污染 required_variables）。</li>
 * </ul>
 *
 * <p>解析：配平括号定位宏的右括号；顶层逗号（括号深度 0）切分实参，必须恰好 3 个；
 * 实参原样透传并外裹一层 {@code ()} 防优先级问题。
 *
 * <p>限制：实参按列引用/表达式设计，<b>不支持含括号或逗号的 SQL 字符串字面量</b>
 * （如 {@code coalesce(t.v, ')')} 或 {@code ','}）—— 配平/切分按字符级处理，不解析引号状态。
 */
public final class SpineKeysMacro {

    private static final String MACRO = ":spineKeys";

    private SpineKeysMacro() {}

    public static boolean containsMacro(String sql) {
        return sql != null && sql.contains(MACRO);
    }

    public static String expandForExecution(String sql) {
        return expand(sql, ":__skP::text[]", ":__skPP::text[]", ":__skV::text[]");
    }

    public static String expandForValidation(String sql) {
        return expand(sql, "ARRAY[]::text[]", "ARRAY[]::text[]", "ARRAY[]::text[]");
    }

    private static String expand(String sql, String arrP, String arrPP, String arrV) {
        if (sql == null || !sql.contains(MACRO)) return sql;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            int m = sql.indexOf(MACRO, i);
            if (m < 0) { out.append(sql, i, sql.length()); break; }
            out.append(sql, i, m);

            // 跳过 ":spineKeys" 后的空白，要求紧跟 '('
            int j = m + MACRO.length();
            while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
            if (j >= sql.length() || sql.charAt(j) != '(') {
                throw new IllegalArgumentException(":spineKeys 必须紧跟 '(' 与三个实参");
            }
            int close = matchParen(sql, j);   // 配平括号
            String inner = sql.substring(j + 1, close);
            String[] args = splitTopLevelCommas(inner);
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        ":spineKeys 必须恰好 3 个顶层逗号分隔的实参（料号列, 父料号列, 版本列），实得 " + args.length);
            }
            for (int ai = 0; ai < 3; ai++) {
                if (args[ai].trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            ":spineKeys 第 " + (ai + 1) + " 个实参为空（需要列引用/表达式）");
                }
            }
            out.append(render(args[0].trim(), args[1].trim(), args[2].trim(), arrP, arrPP, arrV));
            i = close + 1;
        }
        return out.toString();
    }

    private static String render(String a, String b, String c, String arrP, String arrPP, String arrV) {
        return "EXISTS (SELECT 1 FROM unnest(" + arrP + ", " + arrPP + ", " + arrV + ") AS k(p, pp, v) WHERE ("
                + a + ") IS NOT DISTINCT FROM k.p AND ("
                + b + ") IS NOT DISTINCT FROM k.pp AND ("
                + c + ") IS NOT DISTINCT FROM k.v)";
    }

    /** 从 sql[open]=='(' 起配平括号，返回对应 ')' 下标；不平衡抛错。 */
    private static int matchParen(String sql, int open) {
        int depth = 0;
        for (int k = open; k < sql.length(); k++) {
            char ch = sql.charAt(k);
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return k;
            }
        }
        throw new IllegalArgumentException(":spineKeys 括号不配平");
    }

    /** 按括号深度 0 的逗号切分。 */
    private static String[] splitTopLevelCommas(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0, start = 0;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(s.substring(start, k));
                start = k + 1;
            }
        }
        parts.add(s.substring(start));
        return parts.toArray(new String[0]);
    }
}
