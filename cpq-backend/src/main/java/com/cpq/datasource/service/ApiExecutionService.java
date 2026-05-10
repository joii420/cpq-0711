package com.cpq.datasource.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.datasource.entity.DataSource;
import com.cpq.datasource.entity.DataSourceParam;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApiExecutionService {

    private static final Logger LOG = Logger.getLogger(ApiExecutionService.class);

    @Inject
    EncryptionService encryptionService;

    public String execute(DataSource ds, List<DataSourceParam> params, Map<String, String> paramValues) {
        if (ds.apiUrl == null || ds.apiUrl.isBlank()) {
            throw new BusinessException("API URL is not configured");
        }

        long start = System.currentTimeMillis();

        try {
            // Replace {param_code} placeholders in URL and body
            String url = replacePlaceholders(ds.apiUrl, paramValues);
            String body = ds.apiBodyTemplate != null ? replacePlaceholders(ds.apiBodyTemplate, paramValues) : null;

            int timeoutSeconds = ds.apiTimeoutSeconds != null ? ds.apiTimeoutSeconds : 5;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            // Apply headers from api_headers JSONB (stored as JSON array of {key, value} objects)
            applyHeaders(requestBuilder, ds.apiHeaders);

            String method = ds.apiMethod != null ? ds.apiMethod.toUpperCase() : "GET";
            if ("POST".equals(method)) {
                requestBuilder.POST(body != null
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody());
            } else {
                requestBuilder.GET();
            }

            HttpResponse<String> response = client.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String rawResponse = response.body();
            long elapsed = System.currentTimeMillis() - start;
            LOG.debugf("API datasource id=%s status=%d in %dms", ds.id, response.statusCode(), elapsed);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("API returned HTTP " + response.statusCode());
            }

            String extracted = extractValue(rawResponse, ds.apiResultPath);
            return extracted;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("API datasource id=%s failed in %dms: %s", ds.id, elapsed, e.getMessage());
            throw new BusinessException("API execution failed: " + e.getMessage());
        }
    }

    private String replacePlaceholders(String template, Map<String, String> paramValues) {
        if (template == null || paramValues == null) return template;
        String result = template;
        for (Map.Entry<String, String> entry : paramValues.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyHeaders(HttpRequest.Builder builder, String headersJson) {
        if (headersJson == null || headersJson.isBlank() || "[]".equals(headersJson.trim())) {
            return;
        }
        // v4: parse via Jackson, supporting both {"key":...} and {"name":...} field names
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            List<Map<String, Object>> entries = mapper.readValue(
                    headersJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : entries) {
                Object name = entry.get("key");
                if (name == null) name = entry.get("name");
                Object val = entry.get("value");
                if (name == null || val == null) continue;
                String key = name.toString();
                // Decrypt if stored encrypted (AES-256/GCM); pass-through otherwise
                String decrypted = encryptionService.decrypt(val.toString());
                // Avoid setting restricted headers
                if (!key.equalsIgnoreCase("host") && !key.equalsIgnoreCase("content-length")) {
                    builder.header(key, decrypted);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse api_headers JSON, skipping headers: %s", e.getMessage());
        }
    }

    /**
     * Extract a value from JSON using a simple dot-notation path like $.field.subfield
     * For example: $.data.price extracts {"data":{"price":"100"}} -> "100"
     */
    String extractValue(String json, String path) {
        if (path == null || path.isBlank() || json == null) {
            return json;
        }
        // Strip leading $. or $.
        String normalizedPath = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        String[] parts = normalizedPath.split("\\.");
        String current = json.trim();

        for (String part : parts) {
            if (part.isBlank()) continue;
            // Look for "part": "value" or "part": number or "part": {object}
            Pattern keyPattern = Pattern.compile("\"" + Pattern.quote(part) + "\"\\s*:\\s*(.+)");
            Matcher m = keyPattern.matcher(current);
            if (!m.find()) {
                return null;
            }
            String rest = m.group(1).trim();
            // Extract string value
            if (rest.startsWith("\"")) {
                int end = rest.indexOf("\"", 1);
                current = end > 0 ? rest.substring(1, end) : rest;
            } else if (rest.startsWith("{")) {
                // Nested object — continue with it
                int depth = 0;
                int end = 0;
                for (int i = 0; i < rest.length(); i++) {
                    if (rest.charAt(i) == '{') depth++;
                    else if (rest.charAt(i) == '}') {
                        depth--;
                        if (depth == 0) { end = i + 1; break; }
                    }
                }
                current = rest.substring(0, end);
            } else {
                // Number, boolean, null — take until comma, } or end
                int end = rest.indexOf(',');
                int end2 = rest.indexOf('}');
                if (end < 0) end = rest.length();
                if (end2 >= 0 && end2 < end) end = end2;
                current = rest.substring(0, end).trim();
            }
        }
        return current;
    }
}
