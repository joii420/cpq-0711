package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** AC-1: built-in test profile is pass-through. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.TestProfileProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026TestProfilePassThroughTest extends Task261026NonProdPassThroughBase {
}
