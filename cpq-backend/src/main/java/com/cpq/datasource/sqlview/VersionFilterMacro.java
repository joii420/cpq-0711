package com.cpq.datasource.sqlview;

/**
 * {@code :versionFilter(is_current列, 版本列, 料号键列)} 宏展开（纯函数，task-0713 B2）。
 *
 * <p>逐条镜像 {@link SpineKeysMacro} 的解析结构（配平括号 + 顶层逗号切分实参），但服务于
 * 版本切换：取代硬编码的 {@code is_current} 谓词，让核价侧 $view / 递归 BOM 树 SQL 按
 * {@code (componentId, partNo) → viewVersion} override 渲染指定版本，无 override 的料号
 * 落回 {@code is_current}。
 *
 * <p>三种展开形态：
 * <ul>
 *   <li>{@link #expandForExecution}（渲染模式）：展开为单表达式 —— 该料号命中 override 数组
 *       则取 {@code 版本列 = 选定版本}，否则退化为 {@code is_current}。数组来自命名参数
 *       {@code :__vfPart / :__vfVer}（运行时由 {@link SqlViewExecutor} 注入，<b>必须总是绑定
 *       非 null 的（可能为空的）数组</b>——绑定字面量 NULL 会让 {@code x <> ALL(NULL)} 退化成
 *       SQL NULL 而非「空数组时恒真」，导致所有未显式设置 override 上下文的调用方（如
 *       {@code ensureCardValues}/{@code expandFlatDriverBaseRows}）对含本宏的视图整批返回 0
 *       行——这是本宏与 spineKeys 最大的差异点，接线时务必对齐）。</li>
 *   <li>{@link #expandForListing}（列出模式，供版本下拉）：展开为 {@code TRUE}（放开版本过滤，
 *       返回全版本行，由平台收集 distinct view_version）。</li>
 *   <li>{@link #expandForValidation}（保存期 dry-run）：展开为 {@code (is_current列)}，
 *       不引入新占位符，不污染 required_variables。</li>
 * </ul>
 *
 * <p>限制同 {@link SpineKeysMacro}：实参按列引用/表达式设计，不支持含括号或逗号的 SQL
 * 字符串字面量；配平/切分按字符级处理，不解析引号状态。
 */
public final class VersionFilterMacro {

    private static final String MACRO = ":versionFilter";

    private VersionFilterMacro() {}

    public static boolean containsMacro(String sql) {
        return sql != null && sql.contains(MACRO);
    }

    public static String expandForExecution(String sql) {
        return expand(sql, Mode.EXECUTION);
    }

    public static String expandForListing(String sql) {
        return expand(sql, Mode.LISTING);
    }

    public static String expandForValidation(String sql) {
        return expand(sql, Mode.VALIDATION);
    }

    private enum Mode { EXECUTION, LISTING, VALIDATION }

    private static String expand(String sql, Mode mode) {
        if (sql == null || !sql.contains(MACRO)) return sql;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            int m = sql.indexOf(MACRO, i);
            if (m < 0) { out.append(sql, i, sql.length()); break; }
            out.append(sql, i, m);

            int j = m + MACRO.length();
            while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
            if (j >= sql.length() || sql.charAt(j) != '(') {
                throw new IllegalArgumentException(":versionFilter 必须紧跟 '(' 与三个实参");
            }
            int close = matchParen(sql, j);
            String inner = sql.substring(j + 1, close);
            String[] args = splitTopLevelCommas(inner);
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        ":versionFilter 必须恰好 3 个顶层逗号分隔的实参（is_current列, 版本列, 料号键列），实得 " + args.length);
            }
            for (int ai = 0; ai < 3; ai++) {
                if (args[ai].trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            ":versionFilter 第 " + (ai + 1) + " 个实参为空（需要列引用/表达式）");
                }
            }
            String isCurrent = args[0].trim();
            String versionCol = args[1].trim();
            String partNoCol = args[2].trim();
            switch (mode) {
                case LISTING:
                    out.append("TRUE");
                    break;
                case VALIDATION:
                    out.append('(').append(isCurrent).append(')');
                    break;
                case EXECUTION:
                default:
                    out.append(render(isCurrent, versionCol, partNoCol));
                    break;
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * 渲染模式展开：{@code (料号键) IS NOT DISTINCT FROM k.p AND (版本列) IS NOT DISTINCT FROM k.v}
     * 命中 override 数组 → 取该版本；否则（料号键不在 override 数组内）→ 落回 is_current。
     *
     * <p>数组绑定占位符固定为 {@code :__vfPart::text[]} / {@code :__vfVer::text[]}
     * （与 {@link SqlViewExecutor} 注入 & {@code BomTreeRenderService} 递归 SQL 占位符出现次数
     * 绑定约定一致）。空数组时：{@code EXISTS(unnest(空,空))} 恒假 + {@code x <> ALL(空数组)} 恒真
     * → 整体退化为 {@code is_current}，与未接入版本切换前行为等价（零回归）。
     */
    private static String render(String isCurrent, String versionCol, String partNoCol) {
        return "(EXISTS (SELECT 1 FROM unnest(:__vfPart::text[], :__vfVer::text[]) AS k(p, v) WHERE ("
                + partNoCol + ") IS NOT DISTINCT FROM k.p AND (" + versionCol + ") IS NOT DISTINCT FROM k.v)"
                + " OR ((" + partNoCol + ") <> ALL(:__vfPart::text[]) AND (" + isCurrent + ")))";
    }

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
        throw new IllegalArgumentException(":versionFilter 括号不配平");
    }

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
