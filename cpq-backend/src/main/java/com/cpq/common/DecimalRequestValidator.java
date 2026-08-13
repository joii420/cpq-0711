package com.cpq.common;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Rejects precision-sensitive JSON numbers before they enter calculation or persistence paths. */
public final class DecimalRequestValidator {

    private static final ObjectMapper MAPPER = DecimalJacksonCustomizer.newMapper();
    private static final Pattern PLAIN_DECIMAL =
            Pattern.compile("[+-]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern SCIENTIFIC_DECIMAL = Pattern.compile(
            "[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))[eE][+-]?[0-9]+");
    private static final Set<String> STRUCTURAL_INTEGER_KEYS = Set.of(
            "row_index", "rowIndex", "sort_order", "sortOrder", "decimals",
            "priority", "version", "index", "seqNo", "level", "lvl",
            "annualVolume", "deliveryCycle", "tempParentIndex", "序号", "_项次");

    private DecimalRequestValidator() {
    }

    public static void rejectNumericTokens(Object value, String path) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            String key = leafName(path);
            if (!isAllowedStructuralInteger(key, number)) {
                throw numericToken(path, plainNumber(number));
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                rejectValue(entry.getValue(), childPath(path, key), key);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                rejectValue(item, path + "[" + index++ + "]", null);
            }
        }
    }

    public static void rejectNumericJsonTokens(String json, String path) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            rejectJsonNode(MAPPER.readTree(json), path, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, path + " must contain valid JSON: " + e.getMessage());
        }
    }

    /**
     * Validates persisted component rows using the frozen field definitions. The JSON is only
     * inspected and is never normalized, so input text such as {@code "1.2300"} remains intact.
     */
    public static void validateRowData(String json, String path, Map<String, String> fieldTypes) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                throw new BusinessException(400, path + " must be a JSON array");
            }
            for (int row = 0; row < root.size(); row++) {
                JsonNode rowNode = root.get(row);
                if (!rowNode.isObject()) {
                    throw new BusinessException(400, path + "[" + row + "] must be a JSON object");
                }
                var fields = rowNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    validateFieldValue(entry.getValue(), path + "[" + row + "]." + entry.getKey(),
                            entry.getKey(), fieldTypes != null ? fieldTypes.get(entry.getKey()) : null);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, path + " must contain valid JSON: " + e.getMessage());
        }
    }

    private static void validateFieldValue(JsonNode value, String path, String fieldName, String fieldType) {
        if (value == null || value.isNull()) return;
        if (value.isNumber() && STRUCTURAL_INTEGER_KEYS.contains(fieldName)) {
            if (isAllowedStructuralInteger(fieldName, value.decimalValue())) return;
            throw numericToken(path, value.asText());
        }
        if (fieldType == null || fieldType.isBlank()) {
            if (value.isNumber()) {
                throw new BusinessException(400, path + " has no frozen field metadata; received numeric token "
                        + value.asText());
            }
            return;
        }
        String type = fieldType.toUpperCase(java.util.Locale.ROOT);
        if ("INPUT_TEXT".equals(type)) return;
        if ("INPUT_NUMBER".equals(type) || "BASIC_DATA".equals(type)
                || "DATA_SOURCE".equals(type) || "FIXED_VALUE".equals(type)) {
            if (value.isNumber()) return; // Jackson retains the original decimal token as BigDecimal.
            if (value.isTextual() && isPlainDecimal(value.textValue())) return;
            if (value.isTextual() && value.textValue().isBlank()) return;
            throw new BusinessException(400, path + " must be a decimal string or numeric token; received "
                    + value.toString());
        }
        if (type.equals("FORMULA") || type.endsWith("_FORMULA")) {
            if (value.isNumber()) throw numericToken(path, value.asText());
            if (!value.isTextual() || !isPlainDecimal(value.textValue())) {
                throw new BusinessException(400, path + " must be a decimal string; received " + value);
            }
            BigDecimal decimal = new BigDecimal(value.textValue());
            if (Math.max(decimal.scale(), 0) > PrecisionPolicy.FORMULA_RESULT_SCALE) {
                throw new BusinessException(400, path + " must have at most "
                        + PrecisionPolicy.FORMULA_RESULT_SCALE + " decimal places; received " + value.textValue());
            }
        }
    }

    private static boolean isPlainDecimal(String value) {
        return value != null && PLAIN_DECIMAL.matcher(value).matches();
    }

    /**
     * Converts canonical plain-decimal strings in a request map to {@link BigDecimal} without
     * mutating the caller's map. Nested maps and lists are copied recursively; non-decimal text,
     * booleans, nulls and structural integers retain their original types. Numeric scientific
     * notation is rejected because it is not a canonical precision-safe decimal string.
     */
    public static Map<String, Object> normalizeDecimalStrings(Map<String, Object> values) {
        return normalizeDecimalStrings(values, null);
    }

    public static Map<String, Object> normalizeDecimalStrings(
            Map<String, Object> values, String path) {
        if (values == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(values.size());
        values.forEach((key, value) -> normalized.put(
                key, normalizeDecimalValue(value, childPath(path, key))));
        return normalized;
    }

    private static Object normalizeDecimalValue(Object value, String path) {
        if (value instanceof String text) {
            if (PLAIN_DECIMAL.matcher(text).matches()) {
                return new BigDecimal(text);
            }
            if (SCIENTIFIC_DECIMAL.matcher(text.trim()).matches()) {
                throw new BusinessException(400,
                        path + " must use plain decimal notation; received " + text);
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalized = new LinkedHashMap<>(map.size());
            map.forEach((key, nestedValue) ->
                    normalized.put(key, normalizeDecimalValue(
                            nestedValue, childPath(path, String.valueOf(key)))));
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                normalized.add(normalizeDecimalValue(list.get(i), path + "[" + i + "]"));
            }
            return normalized;
        }
        return value;
    }

    private static void rejectValue(Object value, String path, String key) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            if (isAllowedStructuralInteger(key, number)) {
                return;
            }
            throw numericToken(path, plainNumber(number));
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            rejectNumericTokens(value, path);
        }
    }

    private static void rejectJsonNode(JsonNode node, String path, String key) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isNumber()) {
            if (isAllowedStructuralInteger(key, node.decimalValue())) {
                return;
            }
            throw numericToken(path, node.asText());
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    rejectJsonNode(entry.getValue(), childPath(path, entry.getKey()), entry.getKey()));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectJsonNode(node.get(i), path + "[" + i + "]", null);
            }
        }
    }

    private static boolean isAllowedStructuralInteger(String key, Number number) {
        if (key == null || !STRUCTURAL_INTEGER_KEYS.contains(key)) {
            return false;
        }
        if (number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof Long || number instanceof BigInteger) {
            return true;
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        return false;
    }

    private static BusinessException numericToken(String path, String rawValue) {
        return new BusinessException(400,
                path + " must be a decimal string; received numeric token " + rawValue);
    }

    private static String plainNumber(Number number) {
        return number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
    }

    private static String childPath(String path, String key) {
        return path == null || path.isBlank() ? key : path + "." + key;
    }

    private static String leafName(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
