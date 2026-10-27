package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-CONTRACT: black-box contract tests for AC-2/7/8/9/11/12.
 *
 * <p>The endpoint under test is an existing read-only workbook parser, so these
 * tests exercise the real multipart/resource/parser chain without creating
 * business rows.</p>
 */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026ProdCryptoContractTest {

    private static final byte[] CIPHER = "T261026-CIPHER-BYTES".getBytes(StandardCharsets.UTF_8);
    private static final String CHINESE_NAME = "罗格朗 014整改报告.xlsx";
    private static final String ENCODED_CHINESE_NAME =
            "%E7%BD%97%E6%A0%BC%E6%9C%97%20014%E6%95%B4%E6%94%B9%E6%8A%A5%E5%91%8A.xlsx";

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac2_plaintextPerformsOneInspectAndNoDecrypt() throws Exception {
        byte[] plain = Task261026CryptoTestSupport.workbook("T261026-CRYPTO-PLAIN");
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":false}"), 200, new byte[]{1});

        Response response = Task261026CryptoTestSupport.uploadHeaders(plain, "plain.xlsx");

        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(response.asString().contains(Task261026CryptoTestSupport.HEADER_MARKER),
                "The existing parser must still return its non-empty header result");
        assertEquals(1, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/inspect"));
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));
        assertArrayEquals(plain, Task261026CryptoTestSupport.FakeCryptoResource.requests().getFirst().body());
    }

    @Test
    void ac7_encryptedRequestUsesRawBytesSafeHeadersAndOneSharedRequestId() throws Exception {
        byte[] plain = Task261026CryptoTestSupport.workbook("T261026-CRYPTO-DECRYPTED");
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":true}"), 200, plain);

        Response response = Task261026CryptoTestSupport.uploadHeaders(CIPHER, CHINESE_NAME);

        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(response.asString().contains(Task261026CryptoTestSupport.HEADER_MARKER),
                "Only the decrypted workbook may reach the existing parser");
        List<Task261026CryptoTestSupport.CapturedRequest> captured =
                Task261026CryptoTestSupport.FakeCryptoResource.requests();
        assertEquals(2, captured.size(), "Exactly inspect -> decrypt must be emitted");
        assertEquals(List.of("/v1/inspect", "/v1/decrypt"), captured.stream().map(
                Task261026CryptoTestSupport.CapturedRequest::path).toList());
        for (Task261026CryptoTestSupport.CapturedRequest request : captured) {
            assertEquals("POST", request.method());
            assertTrue(request.contentType().startsWith("application/octet-stream"), request.contentType());
            assertEquals(ENCODED_CHINESE_NAME, request.encodedFileName());
            assertTrue(request.authorizationMatched(), "Bearer token must match without exposing it in output");
            assertArrayEquals(CIPHER, request.body(), "Both upstream calls must carry the original ciphertext");
        }
        assertNotNull(captured.get(0).requestId());
        assertFalse(captured.get(0).requestId().isBlank());
        assertEquals(captured.get(0).requestId(), captured.get(1).requestId(),
                "inspect/decrypt for one file must share X-Request-Id");
    }

    @Test
    void ac8_legacyFieldDecryptsPlainFieldSkipsAndMissingFieldFailsClosed() throws Exception {
        byte[] plain = Task261026CryptoTestSupport.workbook("T261026-CRYPTO-LEGACY");

        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"isEncrypted\":true}"), 200, plain);
        Response legacy = Task261026CryptoTestSupport.uploadHeaders(CIPHER, "legacy.xlsx");
        assertEquals(200, legacy.statusCode(), legacy.asString());
        assertEquals(1, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));

        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":false}"), 200, plain);
        Response plaintext = Task261026CryptoTestSupport.uploadHeaders(plain, "plain.xlsx");
        assertEquals(200, plaintext.statusCode(), plaintext.asString());
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));

        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"sizeBytes\":19}"), 200, plain);
        Response missing = Task261026CryptoTestSupport.uploadHeaders(CIPHER, "missing-field.xlsx");
        assertError(missing, 502, "FILE_CRYPTO_BAD_GATEWAY");
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));
    }

    @Test
    void ac9_upstreamStatusAndMalformedResponseMatrixFailsClosedWithoutLeakingBody() {
        record Failure(int upstream, int expectedHttp, String bizCode) {
        }
        List<Failure> failures = List.of(
                new Failure(400, 400, "FILE_CRYPTO_REJECTED"),
                new Failure(401, 502, "FILE_CRYPTO_AUTH_FAILED"),
                new Failure(413, 413, "FILE_CRYPTO_FILE_TOO_LARGE"),
                new Failure(422, 422, "FILE_CRYPTO_REJECTED"),
                new Failure(500, 502, "FILE_CRYPTO_BAD_GATEWAY"),
                new Failure(503, 503, "FILE_CRYPTO_UNAVAILABLE"));

        for (Failure failure : failures) {
            String secretUpstreamBody = "{\"code\":\"INTERNAL_ERROR\",\"message\":"
                    + "\"T261026-UPSTREAM-BODY-MUST-NOT-LEAK\"}";
            Task261026CryptoTestSupport.FakeCryptoResource.plan(
                    failure.upstream(), json(secretUpstreamBody), 200, new byte[]{1});
            Response response = Task261026CryptoTestSupport.uploadHeaders(
                    CIPHER, "failure-" + failure.upstream() + ".xlsx");
            assertError(response, failure.expectedHttp(), failure.bizCode());
            assertFalse(response.asString().contains("T261026-UPSTREAM-BODY-MUST-NOT-LEAK"),
                    "Raw upstream body leaked for HTTP " + failure.upstream());
            assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));
        }

        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("not-json-T261026-UPSTREAM-BODY-MUST-NOT-LEAK"), 200, new byte[]{1});
        assertError(Task261026CryptoTestSupport.uploadHeaders(CIPHER, "invalid-inspect.xlsx"),
                502, "FILE_CRYPTO_BAD_GATEWAY");

        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":true}"), 200, new byte[0]);
        assertError(Task261026CryptoTestSupport.uploadHeaders(CIPHER, "empty-decrypt.xlsx"),
                502, "FILE_CRYPTO_BAD_GATEWAY");
    }

    @Test
    void ac10_emptyUploadIsRejectedBeforeExternalCall() {
        Response response = Task261026CryptoTestSupport.uploadHeaders(new byte[0], "empty.xlsx");

        assertError(response, 400, "EMPTY_UPLOAD_FILE");
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size());
    }

    @Test
    void ac11_retiredV6QuoteEndpointRemains410AndMakesNoExternalCall() {
        Response response = io.restassured.RestAssured.given()
                .multiPart("file", "retired.xlsx", CIPHER, Task261026CryptoTestSupport.XLSX_MIME)
                .when().post("/api/cpq/basic-data-import/v6/quote");

        assertEquals(410, response.statusCode(), response.asString());
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size());
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertError(Response response, int status, String bizCode) {
        assertEquals(status, response.statusCode(), response.asString());
        assertEquals(bizCode, response.jsonPath().getString("detail.bizCode"), response.asString());
    }
}
