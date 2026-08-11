package com.cpq.pricing.resource;

import com.cpq.pricing.entity.PricingRule;
import com.cpq.pricing.entity.PricingStrategy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(PricingStrategyResourceTest.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PricingStrategyResourceTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    private static String testCustomerId;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        // Ensure a test customer exists
        if (testCustomerId == null) {
            testCustomerId = createTestCustomer();
        }
        // Clean up pricing data for this customer
        em.createQuery("DELETE FROM PricingRule r WHERE r.strategy IN " +
                "(SELECT s FROM PricingStrategy s WHERE s.customerId = :cid)")
                .setParameter("cid", java.util.UUID.fromString(testCustomerId))
                .executeUpdate();
        em.createQuery("DELETE FROM PricingStrategy s WHERE s.customerId = :cid")
                .setParameter("cid", java.util.UUID.fromString(testCustomerId))
                .executeUpdate();
    }

    private String createTestCustomer() {
        String body = """
                {
                  "name": "Pricing Test Customer",
                  "level": "GOLD",
                  "contacts": [
                    {"name": "Test Contact", "phone": "13900139000", "isPrimary": true}
                  ]
                }
                """;
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/customers")
                .then()
                .statusCode(200)
                .extract().path("data.id");
    }

    @Test
    @Order(1)
    void listStrategies_emptyForNewCustomer() {
        RestAssured.given()
            .queryParam("customerId", testCustomerId)
            .when()
                .get("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content", hasSize(0))
                .body("data.totalElements", equalTo(0));
    }

    @Test
    @Order(2)
    void createStrategyWithRules() {
        String body = """
                {
                  "customerId": "%s",
                  "name": "Test Gold Strategy",
                  "type": "DISCOUNT",
                  "baseDiscount": "95.00",
                  "minOrderAmount": "10000.00",
                  "effectiveDate": "2026-01-01",
                  "expirationDate": "2026-12-31",
                  "priority": 1,
                  "rules": [
                    {
                      "ruleType": "BULK_DISCOUNT",
                      "thresholdAmount": "50000.00",
                      "discountRate": "92.00",
                      "sortOrder": 1
                    },
                    {
                      "ruleType": "BULK_DISCOUNT",
                      "thresholdAmount": "100000.00",
                      "discountRate": "88.00",
                      "sortOrder": 2
                    }
                  ]
                }
                """.formatted(testCustomerId);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Gold Strategy"))
                .body("data.baseDiscount", equalTo("95"))
                .body("data.status", equalTo("ACTIVE"))
                .body("data.rules", hasSize(2))
                .body("data.rules[0].sortOrder", equalTo(1))
                .body("data.rules[1].discountRate", equalTo("88"));
    }

    @Test
    @Order(3)
    void listByCustomerId_returnsStrategy() {
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "Test List Strategy",
                  "baseDiscount": "90.00",
                  "priority": 2,
                  "rules": []
                }
                """.formatted(testCustomerId);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/pricing-strategies");

        RestAssured.given()
            .queryParam("customerId", testCustomerId)
            .when()
                .get("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1))
                .body("data.content[0].customerId", equalTo(testCustomerId));
    }

    @Test
    @Order(4)
    void updateStrategy_replacesRules() {
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "Test Update Strategy",
                  "baseDiscount": "95.00",
                  "priority": 1,
                  "rules": [
                    {"ruleType": "BULK_DISCOUNT", "thresholdAmount": "10000.00", "discountRate": "90.00", "sortOrder": 1}
                  ]
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        String updateBody = """
                {
                  "customerId": "%s",
                  "name": "Test Update Strategy Modified",
                  "baseDiscount": "88.00",
                  "priority": 1,
                  "rules": [
                    {"ruleType": "BULK_DISCOUNT", "thresholdAmount": "20000.00", "discountRate": "85.00", "sortOrder": 1},
                    {"ruleType": "BULK_DISCOUNT", "thresholdAmount": "50000.00", "discountRate": "80.00", "sortOrder": 2}
                  ]
                }
                """.formatted(testCustomerId);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(updateBody)
            .when()
                .put("/api/cpq/pricing-strategies/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Update Strategy Modified"))
                .body("data.baseDiscount", equalTo("88"))
                .body("data.rules", hasSize(2));
    }

    @Test
    @Order(5)
    void updateStatus_changesStatus() {
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "Test Status Strategy",
                  "baseDiscount": "95.00",
                  "priority": 1,
                  "rules": []
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{\"status\": \"DISABLED\"}")
            .when()
                .patch("/api/cpq/pricing-strategies/" + id)
            .then()
                .statusCode(200)
                .body("data.status", equalTo("DISABLED"));
    }

    @Test
    @Order(6)
    void deleteStrategy_removesIt() {
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "Test Delete Strategy",
                  "baseDiscount": "95.00",
                  "priority": 1,
                  "rules": [
                    {"ruleType": "BULK_DISCOUNT", "thresholdAmount": "10000.00", "discountRate": "90.00", "sortOrder": 1}
                  ]
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/pricing-strategies")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        RestAssured.given()
            .when()
                .delete("/api/cpq/pricing-strategies/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        RestAssured.given()
            .when()
                .get("/api/cpq/pricing-strategies/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    @Order(7)
    void createStrategy_missingCustomerIdFails() {
        String body = """
                {
                  "name": "No Customer Strategy",
                  "baseDiscount": "95.00"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/pricing-strategies")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(8)
    void precisionJsonNumbersAreRejectedWithoutPartialWrites() {
        String decimal = "98765431.123456789012";
        Map<String, String> payloads = Map.of(
                "baseDiscount", "\"baseDiscount\":" + decimal + ",\"rules\":[]",
                "minOrderAmount", "\"baseDiscount\":\"95\",\"minOrderAmount\":"
                        + decimal + ",\"rules\":[]",
                "thresholdAmount", "\"baseDiscount\":\"95\",\"rules\":[{\"ruleType\":\"BULK_DISCOUNT\","
                        + "\"thresholdAmount\":" + decimal + ",\"discountRate\":\"90\",\"sortOrder\":1}]",
                "discountRate", "\"baseDiscount\":\"95\",\"rules\":[{\"ruleType\":\"BULK_DISCOUNT\","
                        + "\"thresholdAmount\":\"10000\",\"discountRate\":" + decimal
                        + ",\"sortOrder\":1}]");
        Map<String, String> expectedPaths = Map.of(
                "baseDiscount", "baseDiscount",
                "minOrderAmount", "minOrderAmount",
                "thresholdAmount", "rules[0].thresholdAmount",
                "discountRate", "rules[0].discountRate");
        long before = pricingStrategyCount();

        payloads.forEach((field, precisionFields) -> {
            String body = "{\"customerId\":\"" + testCustomerId
                    + "\",\"name\":\"Reject " + field + "\"," + precisionFields + "}";
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(body)
                    .post("/api/cpq/pricing-strategies")
                    .then()
                    .statusCode(400)
                    .body("attributeName", equalTo(expectedPaths.get(field)))
                    .body("value", equalTo(decimal));
            assertEquals(before, pricingStrategyCount(),
                    field + " numeric token must not persist strategy or rules");
        });
    }

    private long pricingStrategyCount() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM pricing_strategy WHERE customer_id = :customerId")
                .setParameter("customerId", java.util.UUID.fromString(testCustomerId))
                .getSingleResult()).longValue();
    }
}
