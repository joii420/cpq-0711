package com.cpq.task261026crypto;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** AC-9: refused upstream connection maps to safe 502 with no business write. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdConnectionFailureProfile.class)
class Task261026ConnectionFailureTest extends Task261026CryptoFailureWriteBase {

    @Test
    void ac9_connectionFailureIsBadGatewayAndCreatesNoBusinessRecord() {
        assertFailureAndNoBusinessWrite(
                postEncryptedCommit(), 502, "FILE_CRYPTO_BAD_GATEWAY");
    }
}
