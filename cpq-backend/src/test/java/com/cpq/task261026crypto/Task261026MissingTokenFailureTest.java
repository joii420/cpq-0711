package com.cpq.task261026crypto;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** AC-9: prod token is mandatory and failure is closed before a write. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdMissingTokenProfile.class)
class Task261026MissingTokenFailureTest extends Task261026CryptoFailureWriteBase {

    @Test
    void ac9_missingTokenIsConfigErrorAndCreatesNoBusinessRecord() {
        assertFailureAndNoBusinessWrite(
                postEncryptedCommit(), 503, "FILE_CRYPTO_CONFIG_INVALID");
    }
}
