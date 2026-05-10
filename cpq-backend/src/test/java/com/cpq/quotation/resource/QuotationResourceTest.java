package com.cpq.quotation.resource;

import com.cpq.quotation.entity.*;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotationResourceTest {

    @Inject
    EntityManager em;

    private static String testCustomerId;

    @BeforeEach
    @Transactional
    void ensureTestData() {
        if (testCustomerId == null) {
            testCustomerId = createTestCustomer();
        }
    }

    private String createTestCustomer() {
        String body = """
                {
                  "name": "Quotation Test Customer",
                  "level": "GOLD",
                  "contacts": [
                    {"name": "QT Contact", "phone": "13800138000", "isPrimary": true}
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
    void createQuotation_returnsNewDraft() {
        String body = """
                {
                  "customerId": "%s",
                  "name": "Test Quotation 001",
                  "projectName": "Test Project",
                  "quoteType": "STANDARD",
                  "priority": "HIGH"
                }
                """.formatted(testCustomerId);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/quotations")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("DRAFT"))
                .body("data.quotationNumber", startsWith("QT-"))
                .body("data.name", equalTo("Test Quotation 001"))
                .body("data.snapshotCustomerName", equalTo("Quotation Test Customer"))
                .body("data.expiryDate", notNullValue());
    }

    @Test
    @Order(2)
    void saveDraft_updatesHeaderFields() {
        String id = createQuotationAndGetId();

        String draftBody = """
                {
                  "name": "Updated Quotation Name",
                  "projectName": "Updated Project",
                  "paymentTerms": "Net 30",
                  "deliveryCycle": 14
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(draftBody)
            .when()
                .put("/api/cpq/quotations/" + id + "/draft")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Updated Quotation Name"))
                .body("data.paymentTerms", equalTo("Net 30"))
                .body("data.deliveryCycle", equalTo(14));
    }

    @Test
    @Order(3)
    void listQuotations_returnsPaged() {
        // Ensure at least one quotation exists
        createQuotationAndGetId();

        RestAssured.given()
            .queryParam("page", 0)
            .queryParam("size", 10)
            .when()
                .get("/api/cpq/quotations")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1))
                .body("data.totalElements", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(4)
    void deleteQuotation_removesDraft() {
        String id = createQuotationAndGetId();

        // Delete it
        RestAssured.given()
            .when()
                .delete("/api/cpq/quotations/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Verify it's gone
        RestAssured.given()
            .when()
                .get("/api/cpq/quotations/" + id)
            .then()
                .statusCode(404);
    }

    private String createQuotationAndGetId() {
        String body = """
                {
                  "customerId": "%s",
                  "name": "Helper Quotation",
                  "quoteType": "STANDARD"
                }
                """.formatted(testCustomerId);

        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/quotations")
                .then()
                .statusCode(200)
                .extract().path("data.id");
    }
}
