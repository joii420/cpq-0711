package com.cpq.task261026crypto;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guards derived from the exact 20-row matrix in api.md section 4. */
class Task261026UploadSurfaceGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path APP_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path FRONTEND_SRC = Path.of("../cpq-frontend/src");

    private static final List<EndpointBinding> ACTIVE_ENDPOINTS = List.of(
            row("01-process-master", "com/cpq/basicdata/v6/resource/ProcessMasterResource.java",
                    "/api/cpq/v6/process-master", "/import", "/api/cpq/v6/process-master/import", "file"),
            row("02-elements", "com/cpq/configure/resource/ElementResource.java",
                    "/api/cpq/elements", "/import", "/api/cpq/elements/import", "file"),
            row("03-material-recipes", "com/cpq/configure/resource/MaterialRecipeResource.java",
                    "/api/cpq/material-recipes", "/import", "/api/cpq/material-recipes/import", "file"),
            row("04-currency-all", "com/cpq/currency/resource/CurrencyResource.java",
                    "/api/cpq/currencies", "/rates/import", "/api/cpq/currencies/rates/import", "file"),
            row("05-currency-code", "com/cpq/currency/resource/CurrencyResource.java",
                    "/api/cpq/currencies", "/{code}/rates/import",
                    "/api/cpq/currencies/{code}/rates/import", "file"),
            row("06-customers", "com/cpq/customer/resource/CustomerResource.java",
                    "/api/cpq/customers", "/import", "/api/cpq/customers/import", "file"),
            row("07-quotation", "com/cpq/dataset/quotation/QuotationImportResource.java",
                    "/api/cpq/dataset", "/quote/quotation-import",
                    "/api/cpq/dataset/quote/quotation-import", "file"),
            row("08-dataset", "com/cpq/dataset/resource/DatasetImportResource.java",
                    "/api/cpq/dataset", "/{dataset}/import", "/api/cpq/dataset/{dataset}/import", "file"),
            row("09-element-price", "com/cpq/elementprice/priceimport/PriceImportResource.java",
                    "/api/cpq/element-price", "/import", "/api/cpq/element-price/import", "file"),
            row("10-excel-headers", "com/cpq/importexcel/resource/CustomerExcelTemplateResource.java",
                    "/api/cpq/excel-templates", "/parse-headers",
                    "/api/cpq/excel-templates/parse-headers", "file"),
            row("11-customer-material-map",
                    "com/cpq/importexcel/resource/CustomerMaterialMappingResource.java",
                    "/api/cpq/customers/{customerId}/material-mappings", "/import",
                    "/api/cpq/customers/{customerId}/material-mappings/import", "file"),
            row("12-import-execute", "com/cpq/importexcel/resource/ImportResource.java",
                    "/api/cpq/imports", "/execute", "/api/cpq/imports/execute", "file"),
            row("13-import-preview", "com/cpq/importexcel/resource/ImportResource.java",
                    "/api/cpq/imports", "/import-excel", "/api/cpq/imports/import-excel", "file"),
            row("14-internal-materials", "com/cpq/importexcel/resource/InternalMaterialResource.java",
                    "/api/cpq/internal-materials", "/import", "/api/cpq/internal-materials/import", "file"),
            row("15-model-configs", "com/cpq/modelconfig/resource/ModelConfigResource.java",
                    "/api/cpq/model-configs", null, "/api/cpq/model-configs", "glbFile", "thumbnailFile"),
            row("16-products", "com/cpq/product/resource/ProductResource.java",
                    "/api/cpq/products", "/import", "/api/cpq/products/import", "file"),
            row("17-users", "com/cpq/system/resource/UserResource.java",
                    "/api/cpq/users", "/import", "/api/cpq/users/import", "file"),
            row("18-template-excel-header", "com/cpq/template/resource/TemplateExcelViewResource.java",
                    "/api/cpq/templates/{id}/excel-view-config", "/parse-header",
                    "/api/cpq/templates/{id}/excel-view-config/parse-header", "file"),
            row("19-component-preview", "com/cpq/component/resource/ComponentDirectoryResource.java",
                    "/api/cpq/component-directories", "/{id}/import",
                    "/api/cpq/component-directories/{id}/import", "file"),
            row("20-component-commit", "com/cpq/component/resource/ComponentDirectoryResource.java",
                    "/api/cpq/component-directories", "/{id}/import/commit",
                    "/api/cpq/component-directories/{id}/import/commit", "file"));

    @Test
    void ac6_applicationPropertiesContainsOnlyApprovedBaseAddressAndKeeps100M() throws IOException {
        String properties = Files.readString(APP_PROPERTIES, StandardCharsets.UTF_8);
        List<String> serverLines = properties.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("file.encryption.server="))
                .toList();

        assertEquals(List.of("file.encryption.server=http://172.16.18.45:9502"), serverLines);
        assertTrue(properties.contains("file.encryption.token=${CRYPTO_API_TOKEN:}"));
        assertTrue(properties.contains("quarkus.http.limits.max-body-size=100M"));
        assertFalse(serverLines.getFirst().contains("/v1/"));
    }

    @Test
    void ac11_exactTwentyRowsLocateTwentyConcretePostMethods() throws IOException {
        assertEquals(20, ACTIVE_ENDPOINTS.size(), "The manifest must contain exactly 20 rows");
        assertEquals(20, ACTIVE_ENDPOINTS.stream().map(EndpointBinding::fullPath).distinct().count());

        List<String> failures = new ArrayList<>();
        for (EndpointBinding endpoint : ACTIVE_ENDPOINTS) {
            Path path = MAIN_JAVA.resolve(endpoint.source());
            if (!Files.isRegularFile(path)) {
                failures.add(endpoint.name() + " source missing: " + endpoint.source());
                continue;
            }
            String source = stripJavaComments(Files.readString(path, StandardCharsets.UTF_8));
            if (!hasPath(source, endpoint.classPath())) {
                failures.add(endpoint.name() + " class path missing: " + endpoint.classPath());
                continue;
            }
            String method = multipartPostMethod(source, endpoint.methodPath(), endpoint.fileFields());
            if (method == null) {
                failures.add(endpoint.name() + " exact multipart POST/FileUpload method missing: "
                        + endpoint.methodPath() + " fields=" + endpoint.fileFields());
            }
        }
        assertTrue(failures.isEmpty(), "Upload surface drift: " + failures);
    }

    @Test
    void ac12_frontendContainsNoSecretOrDirectUpstreamContract() throws IOException {
        List<String> forbidden = List.of(
                "CRYPTO_API_TOKEN", "file.encryption.server", "/v1/inspect", "/v1/decrypt",
                "172.16.18.45:9502", Task261026CryptoTestSupport.TOKEN);
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(FRONTEND_SRC)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                for (String marker : forbidden) {
                    if (source.contains(marker)) {
                        hits.add(path + " -> " + marker);
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Browser code must never know the upstream service or token: " + hits);
    }

    private static boolean hasPath(String source, String value) {
        return Pattern.compile("@Path\\s*\\(\\s*\\\"" + Pattern.quote(value) + "\\\"\\s*\\)")
                .matcher(source).find();
    }

    private static String multipartPostMethod(
            String source,
            String methodPath,
            List<String> fileFields) {
        List<Integer> anchors = new ArrayList<>();
        if (methodPath == null) {
            var matcher = Pattern.compile("@POST\\b").matcher(source);
            while (matcher.find()) {
                anchors.add(matcher.start());
            }
        } else {
            var matcher = Pattern.compile(
                            "@Path\\s*\\(\\s*\\\"" + Pattern.quote(methodPath) + "\\\"\\s*\\)")
                    .matcher(source);
            while (matcher.find()) {
                anchors.add(matcher.start());
            }
        }
        for (int anchor : anchors) {
            String method = methodAtAnchor(source, anchor);
            if (method != null
                    && method.contains("FileUpload")
                    && fileFields.stream().allMatch(method::contains)) {
                return method;
            }
        }
        return null;
    }

    private static String methodAtAnchor(String source, int anchor) {
        int post = source.lastIndexOf("@POST", anchor);
        if (post < 0 || post < source.lastIndexOf('}', anchor)) {
            post = source.indexOf("@POST", anchor);
        }
        if (post < 0) {
            return null;
        }
        int bodyStart = firstCodeBrace(source, Math.min(post, anchor));
        int bodyEnd = bodyStart < 0 ? -1 : balancedBraceEnd(source, bodyStart);
        return bodyEnd < 0 ? null : source.substring(post, bodyEnd + 1);
    }

    private static int firstCodeBrace(String source, int start) {
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if ((string || character) && c == '\\') {
                escaped = true;
            } else if (!character && c == '"') {
                string = !string;
            } else if (!string && c == '\'') {
                character = !character;
            } else if (!string && !character && c == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int balancedBraceEnd(String source, int open) {
        int depth = 0;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if ((string || character) && c == '\\') {
                escaped = true;
            } else if (!character && c == '"') {
                string = !string;
            } else if (!string && c == '\'') {
                character = !character;
            } else if (!string && !character && c == '{') {
                depth++;
            } else if (!string && !character && c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String stripJavaComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean line = false;
        boolean block = false;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    result.append(c);
                }
            } else if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
            } else if (!string && !character && c == '/' && next == '/') {
                line = true;
                i++;
            } else if (!string && !character && c == '/' && next == '*') {
                block = true;
                i++;
            } else {
                result.append(c);
                if (escaped) {
                    escaped = false;
                } else if ((string || character) && c == '\\') {
                    escaped = true;
                } else if (!character && c == '"') {
                    string = !string;
                } else if (!string && c == '\'') {
                    character = !character;
                }
            }
        }
        return result.toString();
    }

    private static EndpointBinding row(
            String name,
            String source,
            String classPath,
            String methodPath,
            String fullPath,
            String... fileFields) {
        String composed = methodPath == null ? classPath : classPath + methodPath;
        if (!composed.equals(fullPath)) {
            throw new IllegalArgumentException(composed + " != " + fullPath);
        }
        return new EndpointBinding(name, source, classPath, methodPath, fullPath, List.of(fileFields));
    }

    private record EndpointBinding(
            String name,
            String source,
            String classPath,
            String methodPath,
            String fullPath,
            List<String> fileFields) {
    }
}
