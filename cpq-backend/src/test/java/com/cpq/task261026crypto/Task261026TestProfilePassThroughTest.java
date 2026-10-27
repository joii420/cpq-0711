package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/** AC-1: built-in test profile is pass-through. */
@QuarkusTest
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026TestProfilePassThroughTest extends Task261026NonProdPassThroughBase {
}
