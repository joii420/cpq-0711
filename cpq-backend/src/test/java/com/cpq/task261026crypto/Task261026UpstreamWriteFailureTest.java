package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** AC-9/12 status matrix on a real write-capable entry, with safe-body assertions. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026UpstreamWriteFailureTest extends Task261026CryptoFailureWriteBase {

    private static final String UPSTREAM_BODY = "T261026-UPSTREAM-ERROR-MUST-NOT-LEAK";

    @Test
    void ac9_allDocumentedUpstreamFailuresCreateNoBusinessRecord() {
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
            Task261026CryptoTestSupport.FakeCryptoResource.plan(
                    failure.upstream(), json("{\"message\":\"" + UPSTREAM_BODY + "\"}"),
                    200, new byte[]{1});

            Response response = postEncryptedCommit();

            assertFailureAndNoBusinessWrite(response, failure.expectedHttp(), failure.bizCode());
            assertFalse(response.asString().contains(UPSTREAM_BODY),
                    "The upstream error body must never be reflected");
            assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"));
        }
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
