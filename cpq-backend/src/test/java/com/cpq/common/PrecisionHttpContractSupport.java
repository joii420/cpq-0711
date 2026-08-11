package com.cpq.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared raw-HTTP assertions for task-0810 decimal contracts. */
public final class PrecisionHttpContractSupport {

    public static final String DECIMAL_12 = "98765431.123456789012";
    public static final String SMALL_DECIMAL_12 = "0.000000000001";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrecisionHttpContractSupport() {
    }

    public static JsonNode readJson(Response response) {
        assertNotNull(response, "response");
        String raw = response.asString();
        assertFalse(raw == null || raw.isBlank(), "HTTP response body must not be blank");
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Response is not valid JSON: " + raw, e);
        }
    }

    public static void assertTextualOrNull(JsonNode root, String... jsonPointers) {
        for (String pointer : jsonPointers) {
            JsonNode value = root.at(pointer);
            assertFalse(value.isMissingNode(), "Missing precision response path: " + pointer);
            assertTrue(value.isTextual() || value.isNull(),
                    () -> pointer + " must be JSON string/null, actual=" + value.getNodeType() + " value=" + value);
        }
    }

    public static void assertFieldsTextualOrNull(JsonNode root, Set<String> precisionFieldNames) {
        assertNotNull(root, "root");
        assertDecimalFields(root, "$", new HashSet<>(precisionFieldNames));
    }

    private static void assertDecimalFields(JsonNode node, String path, Set<String> precisionFields) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertDecimalFields(node.get(i), path + "[" + i + "]", precisionFields);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String childPath = path + "." + field.getKey();
            JsonNode value = field.getValue();
            if (precisionFields.contains(field.getKey())) {
                assertTrue(value.isTextual() || value.isNull(),
                        () -> childPath + " must be JSON string/null, actual="
                                + value.getNodeType() + " value=" + value);
            }
            assertDecimalFields(value, childPath, precisionFields);
        }
    }

    public static void assertNoUnexpectedNumericTokens(JsonNode root, Set<String> structuralIntegerFields) {
        assertNoUnexpectedNumericTokens(root, "$", null, new HashSet<>(structuralIntegerFields));
    }

    private static void assertNoUnexpectedNumericTokens(
            JsonNode node, String path, String fieldName, Set<String> structuralFields) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isNumber()) {
            if (fieldName == null || !structuralFields.contains(fieldName)) {
                fail("Unexpected JSON number at " + path + ": " + node);
            }
            assertTrue(node.isIntegralNumber(), path + " structural value must be an integer: " + node);
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoUnexpectedNumericTokens(node.get(i), path + "[" + i + "]", fieldName, structuralFields);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> assertNoUnexpectedNumericTokens(
                    entry.getValue(), path + "." + entry.getKey(), entry.getKey(), structuralFields));
        }
    }

    public static JsonNode assertBadRequest(Response response, String expectedPath, String originalValue) {
        assertEquals(400, response.statusCode(), () -> "Expected HTTP 400, body=" + response.asString());
        String raw = response.asString();
        assertTrue(raw.contains(expectedPath),
                () -> "Error body must contain field path '" + expectedPath + "': " + raw);
        assertTrue(raw.contains(originalValue),
                () -> "Error body must contain original value '" + originalValue + "': " + raw);
        return readJson(response);
    }

    public static QuotationFingerprint fingerprintQuotation(EntityManager em, UUID quotationId) {
        return new QuotationFingerprint(
                singleRowFingerprint(em, "quotation", "id", quotationId),
                aggregateFingerprint(em, "quotation_line_item", "quotation_id", quotationId),
                aggregateFingerprintWhere(em,
                        "quotation_line_component_data",
                        "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id = :id)",
                        quotationId),
                aggregateFingerprint(em, "quotation_view_structure", "quotation_id", quotationId));
    }

    public static void assertUnchanged(QuotationFingerprint before, QuotationFingerprint after) {
        assertEquals(before, after, "Rejected precision request must leave quotation rows unchanged");
    }

    private static RowFingerprint singleRowFingerprint(
            EntityManager em, String table, String idColumn, UUID id) {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
                        "SELECT cast(xmin as text), md5(cast(to_jsonb(t) as text)) "
                                + "FROM " + table + " t WHERE " + idColumn + " = :id")
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) {
            return new RowFingerprint(0L, "", "");
        }
        Object[] row = (Object[]) rows.get(0);
        return new RowFingerprint(1L, String.valueOf(row[0]), String.valueOf(row[1]));
    }

    private static RowFingerprint aggregateFingerprint(
            EntityManager em, String table, String idColumn, UUID id) {
        return aggregateFingerprintWhere(em, table, idColumn + " = :id", id);
    }

    private static RowFingerprint aggregateFingerprintWhere(
            EntityManager em, String table, String predicate, UUID id) {
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT count(*), coalesce(md5(string_agg(md5(cast(to_jsonb(t) as text)), '' "
                                + "ORDER BY cast(t.id as text))), '') "
                                + "FROM " + table + " t WHERE " + predicate)
                .setParameter("id", id)
                .getSingleResult();
        return new RowFingerprint(((Number) row[0]).longValue(), "", String.valueOf(row[1]));
    }

    public record RowFingerprint(long count, String xmin, String md5) {
    }

    public record QuotationFingerprint(
            RowFingerprint quotation,
            RowFingerprint lineItems,
            RowFingerprint componentData,
            RowFingerprint viewStructures) {
    }
}
