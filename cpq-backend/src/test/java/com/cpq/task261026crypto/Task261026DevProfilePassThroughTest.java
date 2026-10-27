package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** AC-1: dev profile is pass-through; its datasource is explicitly redirected to the test DB. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.DevProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026DevProfilePassThroughTest extends Task261026NonProdPassThroughBase {
}
