package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AC-3 and AC-13 representative real customer-import write paths under prod. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026CustomerEncryptedImportTest extends Task261026CustomerImportBase {

    private static final byte[] CIPHER =
            "T261026-CUSTOMER-CIPHERTEXT-MUST-NOT-BE-PARSED".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac3_encryptedCustomerImportPersistsOnlyDecryptedBusinessValues() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = PREFIX + suffix;
        String name = PREFIX + "NAME-" + suffix;
        byte[] plaintext = legalMinimumCustomerWorkbook(code, name);
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":true}"), 200, plaintext);

        Response response = importCustomer(CIPHER, "T261026-customer-cipher.xlsx");

        assertSuccessfulReport(response);
        assertEquals(List.of("/v1/inspect", "/v1/decrypt"),
                Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                        .map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
        assertEquals(1, customerCount(code, name),
                "Exactly the decrypted customer code/name must be persisted");
        assertEquals(1, contactCount(code), "The decrypted contact must be persisted");
        assertEquals(0, customerCount("T261026-CUSTOMER-CIPHERTEXT-MUST-NOT-BE-PARSED",
                        "T261026-CUSTOMER-CIPHERTEXT-MUST-NOT-BE-PARSED"),
                "Ciphertext must never be interpreted as customer data");
    }

    @Test
    void ac13_prodPlaintextInspectFalseKeepsCustomerImportResponseAndValues() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = PREFIX + "PROD-PLAIN-" + suffix;
        String name = PREFIX + "PROD-PLAIN-NAME-" + suffix;
        byte[] plaintext = legalMinimumCustomerWorkbook(code, name);
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, json("{\"encrypted\":false}"), 200, new byte[]{1});

        Response response = importCustomer(plaintext, "T261026-prod-plain.xlsx");

        assertSuccessfulReport(response);
        assertEquals(List.of("/v1/inspect"),
                Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                        .map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
        assertEquals(1, customerCount(code, name));
        assertEquals(1, contactCount(code));
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
