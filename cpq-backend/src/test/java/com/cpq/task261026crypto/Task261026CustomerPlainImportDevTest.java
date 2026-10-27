package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AC-1/13 non-prod side of the representative real-import A/B contract. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.DevProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026CustomerPlainImportDevTest extends Task261026CustomerImportBase {

    @BeforeEach
    void resetFake() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @Test
    void ac1_ac13_devPlaintextRealImportHasBaselineShapeValuesAndZeroExternalCalls() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = PREFIX + "DEV-PLAIN-" + suffix;
        String name = PREFIX + "DEV-PLAIN-NAME-" + suffix;
        byte[] plaintext = legalMinimumCustomerWorkbook(code, name);

        Response response = importCustomer(plaintext, "T261026-dev-plain.xlsx");

        assertSuccessfulReport(response);
        assertEquals(1, customerCount(code, name));
        assertEquals(1, contactCount(code));
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size(),
                "A real non-prod import must make no crypto-service request");
    }
}
