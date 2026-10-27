package com.cpq.task261026crypto;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** AC-9: a server value containing the business path is rejected as configuration. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdInvalidServerProfile.class)
class Task261026InvalidServerFailureTest extends Task261026CryptoFailureWriteBase {

    @Test
    void ac9_serverWithInspectPathIsConfigErrorAndCreatesNoBusinessRecord() {
        assertFailureAndNoBusinessWrite(
                postEncryptedCommit(), 503, "FILE_CRYPTO_CONFIG_INVALID");
    }
}
