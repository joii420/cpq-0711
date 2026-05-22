package com.cpq.component.dto;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1: BNF path / 公式字符串内的上下文变量占位符插值工具 (2026-05-21).
 *
 * <p>占位符格式：{namespace.field}，例如：
 * <ul>
 *   <li>{@code {lineItem.partNo}} → ctx.lineItem.partNo</li>
 *   <li>{@code {lineItem.compositeType}} → ctx.lineItem.compositeType</li>
 *   <li>{@code {lineItem.id}} → ctx.lineItem.id.toString()</li>
 *   <li>{@code {quotation.customerId}} → ctx.quotation.customerId.toString()</li>
 *   <li>{@code {quotation.id}} → ctx.quotation.id.toString()</li>
 *   <li>{@code {user.id}} → ctx.user.id.toString()</li>
 *   <li>{@code {user.role}} → ctx.user.role</li>
 *   <li>{@code {row.元素}} → ctx.row.get("元素").toString()</li>
 *   <li>{@code {global.CURRENT_FISCAL_YEAR}} → ctx.global.get("CURRENT_FISCAL_YEAR").toString()</li>
 * </ul>
 *
 * <p>替换规则：
 * <ul>
 *   <li>能解析且值非 null → 替换为值的字符串表示（UUID 含连字符）</li>
 *   <li>ctx 为 null 或对应 namespace/field 不存在或值为 null → 保留原占位符字符串（不抛异常）</li>
 * </ul>
 *
 * <p>设计约束：
 * <ul>
 *   <li>仅 P0 字符串语法；P1 可升级为结构化 JSON filters[]</li>
 *   <li>不含 `.` 的 {@code {token}} 不属于上下文变量（可能是全局变量 code / BNF 路径），跳过</li>
 *   <li>线程安全：Pattern 是 static final，无可变状态</li>
 * </ul>
 */
public final class ContextInterpolator {

    /** 匹配 {namespace.field} 形式的占位符（namespace 和 field 均只含字母数字下划线中文） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\.(\\S+?)\\}");

    private ContextInterpolator() {}

    /**
     * 对 {@code template} 字符串内所有 {@code {namespace.field}} 占位符做插值替换.
     *
     * @param template 含占位符的字符串（可为 null/空）
     * @param ctx      运行时上下文（可为 null，此时所有占位符保留原样）
     * @return 替换后的字符串；template 为 null 时返回 null
     */
    public static String interpolate(String template, RuntimeContext ctx) {
        if (template == null || template.isBlank() || ctx == null) return template;
        if (!template.contains("{")) return template;

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String namespace = m.group(1);
            String field = m.group(2);
            String resolved = resolve(namespace, field, ctx);
            if (resolved != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
            } else {
                // 未能解析 → 保留原占位符
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 检查字符串是否在插值后所有占位符都已被替换（即不含残留 {namespace.field} 形式）.
     * 用于调用方决定是否因残留未解析占位符而拒绝执行查询。
     */
    public static boolean isFullyResolved(String interpolated) {
        if (interpolated == null) return true;
        return !PLACEHOLDER.matcher(interpolated).find();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部解析
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String resolve(String namespace, String field, RuntimeContext ctx) {
        try {
            return switch (namespace) {
                case "lineItem" -> resolveLineItem(field, ctx.lineItem);
                case "quotation" -> resolveQuotation(field, ctx.quotation);
                case "user"     -> resolveUser(field, ctx.user);
                case "row"      -> resolveMap(field, ctx.row);
                case "global"   -> resolveMap(field, ctx.global);
                default         -> null;
            };
        } catch (Exception e) {
            // 任何反射/NPE/ClassCast 等不预期异常 → 保留原占位符
            return null;
        }
    }

    private static String resolveLineItem(String field, RuntimeContext.LineItemContext li) {
        if (li == null) return null;
        return switch (field) {
            case "partNo"        -> li.partNo;
            case "compositeType" -> li.compositeType;
            case "id"            -> li.id != null ? li.id.toString() : null;
            case "tempId"        -> li.tempId != null ? li.tempId.toString() : null;
            default              -> null;
        };
    }

    private static String resolveQuotation(String field, RuntimeContext.QuotationContext q) {
        if (q == null) return null;
        return switch (field) {
            case "id"               -> q.id != null ? q.id.toString() : null;
            case "customerId"       -> q.customerId != null ? q.customerId.toString() : null;
            case "customerCategory" -> q.customerCategory;
            case "productCategoryId"-> q.productCategoryId != null ? q.productCategoryId.toString() : null;
            default                 -> null;
        };
    }

    private static String resolveUser(String field, RuntimeContext.UserContext u) {
        if (u == null) return null;
        return switch (field) {
            case "id"   -> u.id != null ? u.id.toString() : null;
            case "role" -> u.role;
            default     -> null;
        };
    }

    private static String resolveMap(String key, Map<String, Object> map) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
