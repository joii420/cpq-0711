package com.cpq.elementprice;

import com.cpq.common.DecimalJacksonCustomizer;
import com.cpq.common.PrecisionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/**
 * task-260916 B-11 (D-12): read change-log snapshots (jsonb text) without going through double,
 * and expose the named decimal fields as canonical decimal strings (plain, no trailing zeros,
 * never scientific notation), matching the PRD precision contract for REST payloads.
 *
 * <p>Other fields (e.g. integer {@code windowNum}, text fields) are left untouched.
 * Stored logs are not modified; conversion happens only on the read path.
 */
public final class SnapshotDecimals {

    /** Exact-decimal reader: floats become BigDecimal nodes, never double. */
    private static final ObjectMapper EXACT = DecimalJacksonCustomizer.newMapper();

    private SnapshotDecimals() {
    }

    /**
     * Parse a snapshot and convert the given decimal fields to canonical decimal strings.
     * Unparseable JSON yields an empty object (same fallback as before).
     */
    public static JsonNode parse(String json, String... decimalFields) {
        JsonNode node;
        try {
            node = EXACT.readTree(json);
        } catch (Exception e) {
            return EXACT.createObjectNode();
        }
        if (!(node instanceof ObjectNode obj)) {
            return node == null ? EXACT.createObjectNode() : node;
        }
        for (String field : decimalFields) {
            JsonNode v = obj.get(field);
            if (v == null || v.isNull()) continue;
            BigDecimal d = null;
            if (v.isNumber()) {
                d = v.decimalValue();
            } else if (v.isTextual()) {
                try {
                    d = new BigDecimal(v.textValue().trim());
                } catch (NumberFormatException ignored) {
                    // leave non-numeric text as is
                }
            }
            if (d != null) obj.put(field, PrecisionPolicy.toPlainDecimalString(d));
        }
        return obj;
    }
}
