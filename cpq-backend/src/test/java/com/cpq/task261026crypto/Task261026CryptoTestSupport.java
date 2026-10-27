package com.cpq.task261026crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;

/**
 * task-261026 file-crypto test harness.
 *
 * <p>The fake service deliberately records only a hash/length plus the safe
 * contract headers. Authorization is compared in memory and is never copied
 * into diagnostic output.</p>
 */
public final class Task261026CryptoTestSupport {

    public static final String TOKEN = "T261026-SECRET-MUST-NOT-LEAK";
    public static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String HEADER_MARKER = "T261026-CRYPTO-HEADER";
    public static final String PARSE_HEADERS = "/api/cpq/excel-templates/parse-headers";

    private Task261026CryptoTestSupport() {
    }

    public static byte[] workbook(String value) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue(HEADER_MARKER);
            sheet.createRow(1).createCell(0).setCellValue(value);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static Response uploadHeaders(byte[] body, String fileName) {
        return given()
                .multiPart("file", fileName, body, XLSX_MIME)
                .when().post(PARSE_HEADERS);
    }

    public static Map<String, String> safeDatabaseOverrides(boolean rbacEnabled) {
        String host = System.getenv().getOrDefault("DB_HOST", "10.177.152.12");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String database = System.getenv().getOrDefault("DB_NAME", "cpq_db_test");
        String username = System.getenv().getOrDefault("DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "joii5231");
        String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=disable";
        return Map.ofEntries(
                Map.entry("quarkus.datasource.username", username),
                Map.entry("quarkus.datasource.password", password),
                Map.entry("quarkus.datasource.jdbc.url", jdbc),
                Map.entry("quarkus.datasource.\"datasource-readonly\".username", username),
                Map.entry("quarkus.datasource.\"datasource-readonly\".password", password),
                Map.entry("quarkus.datasource.\"datasource-readonly\".jdbc.url", jdbc),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.flyway.locations", "db/migration"),
                Map.entry("quarkus.mailer.mock", "true"),
                Map.entry("cpq.security.rbac.enabled", Boolean.toString(rbacEnabled)),
                Map.entry("cpq.price-adjust.startup-recovery.enabled", "false"));
    }

    public static final class ProdProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return safeDatabaseOverrides(false);
        }
    }

    public static final class ProdRbacProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return safeDatabaseOverrides(true);
        }
    }

    public static final class ProdMissingTokenProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(safeDatabaseOverrides(false));
            overrides.put("file.encryption.server", "http://127.0.0.1:9502");
            overrides.put("file.encryption.token", "");
            return overrides;
        }
    }

    public static final class ProdInvalidServerProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(safeDatabaseOverrides(false));
            overrides.put("file.encryption.server", "http://127.0.0.1:9502/v1/inspect");
            overrides.put("file.encryption.token", TOKEN);
            return overrides;
        }
    }

    public static final class ProdConnectionFailureProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(safeDatabaseOverrides(false));
            overrides.put("file.encryption.server", "http://127.0.0.1:1");
            overrides.put("file.encryption.token", TOKEN);
            return overrides;
        }
    }

    public static final class DevProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "dev";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return safeDatabaseOverrides(false);
        }
    }

    public static final class CustomProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "crypto-contract";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return safeDatabaseOverrides(false);
        }
    }

    /** Programmable in-process replacement for the external file crypto service. */
    public static final class FakeCryptoResource implements QuarkusTestResourceLifecycleManager {
        private static final int MAX_RETAINED_BODY_BYTES = 1024 * 1024;
        private static final AtomicReference<Plan> PLAN = new AtomicReference<>(Plan.plaintext());
        private static final CopyOnWriteArrayList<CapturedRequest> REQUESTS = new CopyOnWriteArrayList<>();
        private static final ConcurrentHashMap<String, ResponseSpec> DECRYPT_BY_FILE = new ConcurrentHashMap<>();
        private static volatile String baseUrl;

        private HttpServer server;
        private ExecutorService executor;
        private Path storage;

        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/v1/inspect", exchange -> respond(exchange, true));
                server.createContext("/v1/decrypt", exchange -> respond(exchange, false));
                server.createContext("/__identity", exchange -> {
                    byte[] body = "T261026-CRYPTO-FAKE".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                executor = Executors.newCachedThreadPool();
                server.setExecutor(executor);
                server.start();
                baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                storage = Files.createTempDirectory("T261026-CRYPTO-MODEL-");
                reset();
                return Map.of(
                        "file.encryption.server", baseUrl,
                        "file.encryption.token", TOKEN,
                        "cpq.model-config.storage-dir", storage.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot start task-261026 fake crypto service", e);
            }
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            if (storage != null && Files.exists(storage)) {
                try (var paths = Files.walk(storage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot clean task-261026 temporary model storage", e);
                }
            }
        }

        private static void respond(HttpExchange exchange, boolean inspect) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            String encodedFileName = headers.getFirst("X-File-Name");
            String requestId = headers.getFirst("X-Request-Id");
            REQUESTS.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    requestBody.length,
                    sha256(requestBody),
                    headers.getFirst("Content-Type"),
                    encodedFileName,
                    requestId,
                    ("Bearer " + TOKEN).equals(headers.getFirst("Authorization")),
                    requestBody.length <= MAX_RETAINED_BODY_BYTES ? requestBody.clone() : new byte[0]));

            Plan plan = PLAN.get();
            if (plan.delayMillis() > 0) {
                try {
                    Thread.sleep(plan.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ResponseSpec namedDecrypt = inspect ? null : DECRYPT_BY_FILE.get(encodedFileName);
            int status = inspect ? plan.inspectStatus()
                    : namedDecrypt == null ? plan.decryptStatus() : namedDecrypt.status();
            byte[] response = inspect ? plan.inspectBody()
                    : namedDecrypt == null ? plan.decryptBody() : namedDecrypt.body();
            exchange.getResponseHeaders().set("Content-Type",
                    inspect || status >= 400 ? "application/json" : "application/octet-stream");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        public static void reset() {
            REQUESTS.clear();
            DECRYPT_BY_FILE.clear();
            PLAN.set(Plan.plaintext());
        }

        public static void plan(int inspectStatus, byte[] inspectBody, int decryptStatus, byte[] decryptBody) {
            REQUESTS.clear();
            DECRYPT_BY_FILE.clear();
            PLAN.set(new Plan(inspectStatus, inspectBody.clone(), decryptStatus, decryptBody.clone(), 0));
        }

        public static void decryptForFile(String encodedFileName, int status, byte[] body) {
            DECRYPT_BY_FILE.put(encodedFileName, new ResponseSpec(status, body.clone()));
        }

        public static void planWithDelay(long delayMillis) {
            REQUESTS.clear();
            PLAN.set(new Plan(200, json("{\"encrypted\":false}"), 200, new byte[]{1}, delayMillis));
        }

        public static List<CapturedRequest> requests() {
            return new ArrayList<>(REQUESTS);
        }

        public static long count(String path) {
            return REQUESTS.stream().filter(r -> path.equals(r.path())).count();
        }

        public static Path storage() {
            return storagePath();
        }

        /** Positive control proving that zero-count assertions use a live, observable fake. */
        public static void assertObserverOperational() throws Exception {
            reset();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/inspect"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/octet-stream")
                    .header("X-File-Name", "positive-control.xlsx")
                    .header("X-Request-Id", "T261026-positive-control")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))
                    .build();
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200 || count("/v1/inspect") != 1) {
                throw new AssertionError("Fake crypto observer positive control did not record inspect");
            }
            reset();
        }

        private static Path storagePath() {
            // The instance path is deliberately recovered from the active fake resource.
            // Tests use it only for before/after regular-file counts.
            return Holder.INSTANCE.storage;
        }

        private static byte[] json(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Set by Quarkus through the lifecycle instance; used only within the same JVM. */
        private static final class Holder {
            private static FakeCryptoResource INSTANCE;
        }

        public FakeCryptoResource() {
            Holder.INSTANCE = this;
        }
    }

    public record CapturedRequest(
            String method,
            String path,
            int size,
            String sha256,
            String contentType,
            String encodedFileName,
            String requestId,
            boolean authorizationMatched,
            byte[] body) {
        public CapturedRequest {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Plan(int inspectStatus, byte[] inspectBody, int decryptStatus, byte[] decryptBody,
                long delayMillis) {
        private static Plan plaintext() {
            return new Plan(200, "{\"encrypted\":false}".getBytes(StandardCharsets.UTF_8),
                    200, new byte[]{1}, 0);
        }
    }

    record ResponseSpec(int status, byte[] body) {
        ResponseSpec {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
