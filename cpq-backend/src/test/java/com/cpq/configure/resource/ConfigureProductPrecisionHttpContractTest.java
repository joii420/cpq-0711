package com.cpq.configure.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ConfigureProductPrecisionHttpContractTest.RbacOffProfile.class)
class ConfigureProductPrecisionHttpContractTest {

    private static final String DECIMAL = "98765431.123456789012";

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Test
    void elementOverrideNumericPctIsRejectedBeforeAnyQuotationLineWrite() {
        long before = quotationLineCount();
        String body = """
                {
                  "productType":"SIMPLE",
                  "parts":[{
                    "name":"Precision contract part",
                    "partMode":"custom",
                    "recipeCode":"PRECISION-CONTRACT",
                    "unitWeightGrams":"1",
                    "elements":[{"elementCode":"Ag","pct":98765431.123456789012}]
                  }]
                }
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/configure-product/quotations/" + UUID.randomUUID())
                .then()
                .statusCode(400)
                .body("objectName", equalTo("ConfigureProductRequest"))
                .body("attributeName", equalTo("parts[0].elements[0].pct"))
                .body("value", equalTo(DECIMAL));

        assertEquals(before, quotationLineCount(),
                "rejected ElementOverride.pct numeric token must not write quotation lines");
    }

    private long quotationLineCount() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM quotation_line_item")
                .getSingleResult()).longValue();
    }
}
