package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-13: authorization must reject before any file bytes are sent upstream. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdRbacProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026ProdUnauthorizedTest {

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac13_unauthenticatedMultipartRequestIsRejectedBeforeInspect() {
        Response response = Task261026CryptoTestSupport.uploadHeaders(
                "not-a-workbook".getBytes(StandardCharsets.UTF_8), "unauthorized.xlsx");

        assertTrue(response.statusCode() == 401 || response.statusCode() == 403, response.asString());
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size(),
                "Permission failure must happen before inspect/decrypt");
    }
}
