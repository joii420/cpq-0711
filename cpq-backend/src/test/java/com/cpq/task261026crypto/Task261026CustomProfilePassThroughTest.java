package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** AC-1: an arbitrary profile name other than the exact string "prod" is pass-through. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.CustomProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026CustomProfilePassThroughTest extends Task261026NonProdPassThroughBase {
}
