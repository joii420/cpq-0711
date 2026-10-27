package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** AC-9: the real production timeout is exercised without overriding it in test config. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026TimeoutFailureTest extends Task261026CryptoFailureWriteBase {

    @Test
    void ac9_slowInspectTimesOutAndCreatesNoBusinessRecord() {
        Task261026CryptoTestSupport.FakeCryptoResource.planWithDelay(35_000);

        assertFailureAndNoBusinessWrite(
                postEncryptedCommit(), 504, "FILE_CRYPTO_TIMEOUT");
    }
}
