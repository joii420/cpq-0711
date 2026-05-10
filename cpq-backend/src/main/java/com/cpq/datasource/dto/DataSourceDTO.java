package com.cpq.datasource.dto;

import com.cpq.datasource.entity.DataSource;
import com.cpq.datasource.entity.DataSourceParam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DataSourceDTO {

    public UUID id;
    public String code;
    public String name;
    public String type;
    public String status;
    public String description;
    public String sqlQuery;
    public String sqlResultColumn;
    public String apiUrl;
    public String apiMethod;
    public String apiHeaders;
    public String apiBodyTemplate;
    public String apiResultPath;
    public Integer apiTimeoutSeconds;
    public UUID createdBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public List<DataSourceParamDTO> params;

    public static DataSourceDTO from(DataSource ds, List<DataSourceParam> params) {
        DataSourceDTO dto = new DataSourceDTO();
        dto.id = ds.id;
        dto.code = ds.code;
        dto.name = ds.name;
        dto.type = ds.type;
        dto.status = ds.status;
        dto.description = ds.description;
        dto.sqlQuery = ds.sqlQuery;
        dto.sqlResultColumn = ds.sqlResultColumn;
        dto.apiUrl = ds.apiUrl;
        dto.apiMethod = ds.apiMethod;
        dto.apiHeaders = maskSensitiveHeaders(ds.apiHeaders);
        dto.apiBodyTemplate = ds.apiBodyTemplate;
        dto.apiResultPath = ds.apiResultPath;
        dto.apiTimeoutSeconds = ds.apiTimeoutSeconds;
        dto.createdBy = ds.createdBy;
        dto.createdAt = ds.createdAt;
        dto.updatedAt = ds.updatedAt;
        dto.params = params == null ? List.of() :
                params.stream().map(DataSourceParamDTO::from).collect(Collectors.toList());
        return dto;
    }

    public static DataSourceDTO from(DataSource ds) {
        return from(ds, List.of());
    }

    /**
     * Mask sensitive header values with "****" for API responses.
     *
     * <p>Supports both {@code {"key":...}} and {@code {"name":...}} field naming.
     * Sensitive name pattern: auth / key / token / secret / bearer (case-insensitive).
     * Encrypted values (ENC: prefix) are also masked regardless of name.
     *
     * <p>v4: switched from regex to Jackson parsing to fix T4 P0 finding where
     * {"name":..., "value":...} format leaked plaintext.
     */
    @SuppressWarnings("unchecked")
    private static String maskSensitiveHeaders(String headers) {
        if (headers == null || headers.isBlank()) return headers;
        java.util.regex.Pattern sensitivePattern =
                java.util.regex.Pattern.compile("(?i)(auth|key|token|secret|bearer)");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            List<java.util.Map<String, Object>> entries = mapper.readValue(
                    headers,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});
            for (java.util.Map<String, Object> entry : entries) {
                Object name = entry.get("key");
                if (name == null) name = entry.get("name");
                Object val = entry.get("value");
                if (val == null) continue;
                String valStr = val.toString();
                boolean encryptedAtRest = valStr.startsWith("ENC:");
                boolean sensitiveName = name != null && sensitivePattern.matcher(name.toString()).find();
                if (encryptedAtRest || sensitiveName) {
                    entry.put("value", "****");
                }
            }
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            // Fall back to regex masking for malformed JSON to avoid leaking original
            return headers.replaceAll("\"ENC:[^\"]*\"", "\"****\"")
                          .replaceAll("(?i)(\"(?:Authorization|X-Api-Key|Api-Key|X-Auth-Token|X-Access-Token|Bearer)\"\\s*:\\s*\")([^\"]*)(\")", "$1****$3");
        }
    }
}
