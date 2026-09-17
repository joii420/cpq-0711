package com.cpq.component.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * repair-260916 (问题说明 5.4): rewrites legacy Excel TAB_JOIN_FORMULA text so that a bare
 * {@code [alias.col]} that pointed at a subtotal-flagged column becomes {@code [alias.col(小计)]}.
 *
 * <p>Rules (shared with migration {@code V446__repair260916_tabjoin_subtotal_suffix.sql}):
 * <ul>
 *   <li>only columns with {@code source_type = TAB_JOIN_FORMULA};</li>
 *   <li>for each {@code tabs[]} entry: component id = part of {@code tabKey} before the first ':';
 *       {@code tabKey} starting with {@code idx:} is skipped;</li>
 *   <li>subtotal columns = field names of that component whose {@code is_subtotal} is true;</li>
 *   <li>literal (non-regex) replacement of {@code [alias.col]} with {@code [alias.col(小计)]};
 *       idempotent because the new text no longer contains the old literal;</li>
 *   <li>if any referenced component is unknown the whole column is left untouched and reported;</li>
 *   <li>only the {@code expression} value changes — other keys and column order are preserved.</li>
 * </ul>
 * Pure in-memory; performs no database access.
 */
public final class TabJoinSubtotalSuffixRewriter {

    static final String SUFFIX = "(小计)";

    private TabJoinSubtotalSuffixRewriter() {}

    /** Outcome of one rewrite pass. */
    public static final class Result {
        /** Number of columns whose expression text changed. */
        public int rewrittenColumns;
        /** Human-readable notices for columns left untouched because a referenced component is unknown. */
        public final List<String> notices = new ArrayList<>();
    }

    /** Subtotal-flagged field names of a component's {@code fields} JSON array (accepts true / "true"). */
    public static Set<String> subtotalColumnsOf(JsonNode fields) {
        Set<String> out = new LinkedHashSet<>();
        if (fields == null || !fields.isArray()) return out;
        for (JsonNode f : fields) {
            JsonNode flag = f.get("is_subtotal");
            boolean on = flag != null && (flag.isBoolean() ? flag.booleanValue() : "true".equals(flag.asText()));
            String name = f.path("name").asText(null);
            if (on && name != null && !name.isEmpty()) out.add(name);
        }
        return out;
    }

    /**
     * Rewrites {@code excelColumns} in place.
     *
     * @param excelColumns        Excel column array (may be null / non-array → no-op)
     * @param subtotalColsByCompId component id (string) → subtotal column names; a missing key = unknown component
     */
    public static Result rewrite(JsonNode excelColumns, Map<String, Set<String>> subtotalColsByCompId) {
        Result r = new Result();
        if (excelColumns == null || !excelColumns.isArray()) return r;
        for (JsonNode colNode : (ArrayNode) excelColumns) {
            if (!(colNode instanceof ObjectNode col)) continue;
            if (!"TAB_JOIN_FORMULA".equals(col.path("source_type").asText())) continue;
            JsonNode exprNode = col.get("expression");
            if (exprNode == null || !exprNode.isTextual()) continue;
            String expr = exprNode.asText();
            String next = expr;
            boolean unknown = false;
            JsonNode tabs = col.get("tabs");
            if (tabs != null && tabs.isArray()) {
                for (JsonNode tab : tabs) {
                    String alias = tab.path("alias").asText(null);
                    String tabKey = tab.path("tabKey").asText(null);
                    if (alias == null || tabKey == null || tabKey.startsWith("idx:")) continue;
                    int colon = tabKey.indexOf(':');
                    String compId = colon >= 0 ? tabKey.substring(0, colon) : tabKey;
                    Set<String> subs = subtotalColsByCompId.get(compId);
                    if (subs == null) {
                        unknown = true;
                        r.notices.add("col_key=" + col.path("col_key").asText() + " references unknown component "
                            + compId + " (alias " + alias + "); column left unchanged");
                        break;
                    }
                    for (String c : subs) {
                        String bare = "[" + alias + "." + c + "]";
                        next = next.replace(bare, "[" + alias + "." + c + SUFFIX + "]");
                    }
                }
            }
            if (unknown || next.equals(expr)) continue;
            col.put("expression", next);   // ObjectNode.put keeps the key's original position
            r.rewrittenColumns++;
        }
        return r;
    }

    /**
     * Whether a bundle version is strictly lower than {@code major.minor}. Missing/blank or
     * unparseable versions are treated as the oldest format (1.0).
     */
    public static boolean versionLowerThan(String version, int major, int minor) {
        int[] v = parseVersion(version);
        return v[0] != major ? v[0] < major : v[1] < minor;
    }

    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) return new int[]{1, 0};
        String[] p = version.trim().split("\\.");
        try {
            int ma = Integer.parseInt(p[0]);
            int mi = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return new int[]{ma, mi};
        } catch (NumberFormatException e) {
            return new int[]{1, 0};
        }
    }
}
