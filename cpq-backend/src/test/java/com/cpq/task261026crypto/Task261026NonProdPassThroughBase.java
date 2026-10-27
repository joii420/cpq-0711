package com.cpq.task261026crypto;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-1 assertion shared by the three non-production profile boot tests. */
abstract class Task261026NonProdPassThroughBase {

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac1_nonProdProfilePassesPlainFileThroughWithoutAnyExternalCall() throws Exception {
        Task261026CryptoTestSupport.FakeCryptoResource.assertObserverOperational();
        byte[] workbook = Task261026CryptoTestSupport.workbook("T261026-CRYPTO-NONPROD");
        Response response = Task261026CryptoTestSupport.uploadHeaders(workbook, "non-prod.xlsx");

        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(response.asString().contains(Task261026CryptoTestSupport.HEADER_MARKER),
                "The original workbook must reach the existing header parser unchanged: " + response.asString());
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size(),
                "A non-prod profile must make exactly zero crypto-service requests");
    }
}
