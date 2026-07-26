package com.cpq.datasource.sqlview;

/**
 * task-0725 根因 2 修复 —— SQL 文本屏蔽公共工具。
 *
 * <p>实现整体搬自 {@link QuotePendingRewriter#mask(String)}（task-0721 B3.1 原实现）：把字符串字面量 /
 * {@code --} 行注释 / {@code /* *&#47;} 块注释替换为等长空白（换行符原样保留），供命名占位符（{@code :xxx}）
 * 之类的 token 定位用——保证行号/偏移量与原文完全对齐，实际替换/编辑仍须作用于原始文本，不能用本方法的
 * 返回值直接当最终 SQL。
 *
 * <p><b>为什么要抽出来（task-0725 根因 2）</b>：{@code mask()} 原是 {@link QuotePendingRewriter} 里的
 * package-private 方法，只在 {@code com.cpq.datasource.sqlview} 包内可见。但命名参数替换的正则
 * （{@code (?<!:):([a-zA-Z_][a-zA-Z0-9_]*)}）在四个站点各自独立实现，且分布在三个不同包：
 * <ul>
 *   <li>{@link SqlViewExecutor}（本包，{@code rewriteNamedParams}/{@code extractNamedParams}）</li>
 *   <li>{@code com.cpq.component.service.SqlViewValidator}（校验/dry-run 通路）</li>
 *   <li>{@code com.cpq.quotation.service.BomTreeRenderService}（核价树递归裸 JDBC）</li>
 * </ul>
 * 其中只有 {@link SqlViewExecutor} 一处在替换前做了屏蔽，其余三处直接对原始 SQL 文本跑正则，导致
 * {@code --}/{@code /* *&#47;} 注释里写的 {@code :xxx} 也被误识别为占位符——注释内容对 pgjdbc 不可见，
 * 但 Java 侧仍多绑了一个值，两边计数错位，抛出
 * {@code The column index is out of range: N, number of columns: N-1}（已复现于 {@code cp_view}/
 * {@code bom_view}）。抽成 public 工具类后四个站点共用同一套屏蔽语义，不再各自维护正则。
 *
 * <p>用法约定（与 {@link QuotePendingRewriter#rewrite} 一致的"masked 定位 + 原文替换"范式）：
 * <pre>{@code
 * String masked = SqlTextMask.mask(sql);
 * Matcher m = NAMED_PARAM.matcher(masked);
 * while (m.find()) {
 *     out.append(sql, lastEnd, m.start());   // 注意：append 的是原文 sql，不是 masked
 *     ...
 *     lastEnd = m.end();
 * }
 * out.append(sql, lastEnd, sql.length());
 * }</pre>
 */
public final class SqlTextMask {

    private SqlTextMask() {}

    /**
     * 屏蔽字符串字面量 / 行注释 / 块注释为等长空白（换行符原样保留，保证行号/偏移量不变），
     * 供 token 定位用；实际替换仍须作用于原始文本（偏移量对齐）。
     */
    public static String mask(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { out.append("  "); i += 2; continue; }
                        out.append(' '); i++; break;
                    }
                    out.append(d == '\n' ? '\n' : ' '); i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                out.append("  "); i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    out.append(sql.charAt(i) == '\n' ? '\n' : ' '); i++;
                }
                if (i + 1 < n) { out.append("  "); i += 2; } else { i = n; }
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }
}
