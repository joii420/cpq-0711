package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-10 dynamically generated HTTP body-size boundaries; no large fixture is committed. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026HttpSizeBoundaryTest {

    private static final long MIB = 1024L * 1024L;
    private static final long INSIDE_FILE_BYTES = 100L * MIB - 64L * 1024L;
    private static final long OVER_FILE_BYTES = 100L * MIB + 1L;

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac10_justInsideHttpLimitStillRunsInspectAndDecrypt() throws Exception {
        Path file = sparseFile(INSIDE_FILE_BYTES);
        try {
            byte[] plaintext = Task261026CryptoTestSupport.workbook("T261026-CRYPTO-SIZE-INSIDE");
            Task261026CryptoTestSupport.FakeCryptoResource.plan(
                    200, json("{\"encrypted\":true}"), 200, plaintext);

            Response response = given()
                    .multiPart(new MultiPartSpecBuilder(file.toFile())
                            .controlName("file")
                            .fileName("inside-limit.xlsx")
                            .mimeType(Task261026CryptoTestSupport.XLSX_MIME)
                            .build())
                    .when().post(Task261026CryptoTestSupport.PARSE_HEADERS);

            assertEquals(200, response.statusCode(), response.asString());
            assertTrue(response.asString().contains(Task261026CryptoTestSupport.HEADER_MARKER));
            assertEquals(List.of("/v1/inspect", "/v1/decrypt"),
                    Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                            .map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
            assertTrue(Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                            .allMatch(request -> request.size() == INSIDE_FILE_BYTES),
                    "Both upstream requests must receive the complete within-limit file");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void ac10_over100MIsRejectedByHttpLayerBeforeCryptoService() throws Exception {
        Path file = sparseFile(OVER_FILE_BYTES);
        try {
            Task261026CryptoTestSupport.FakeCryptoResource.reset();

            HttpResponse<String> response = overLimitUpload(file);

            assertEquals(413, response.statusCode(), response.body());
            assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size(),
                    "HTTP rejection must happen before inspect/decrypt");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Path sparseFile(long size) throws Exception {
        Path path = Files.createTempFile("T261026-CRYPTO-SIZE-", ".xlsx");
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(size);
        }
        return path;
    }

    private static HttpResponse<String> overLimitUpload(Path file) throws Exception {
        String boundary = "T261026OverLimitBoundary";
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"over-limit.xlsx\"\r\n"
                + "Content-Type: " + Task261026CryptoTestSupport.XLSX_MIME + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + io.restassured.RestAssured.port
                                + Task261026CryptoTestSupport.PARSE_HEADERS))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .expectContinue(true)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(header),
                        HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofByteArray(footer)))
                .build();
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
