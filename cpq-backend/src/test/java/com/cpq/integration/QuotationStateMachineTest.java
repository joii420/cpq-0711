package com.cpq.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import static org.hamcrest.Matchers.*;

/**
 * Tests for quotation status transitions (state machine rules).
 *
 * Valid transitions:
 *   DRAFT -> deleted (hard delete allowed)
 *   DRAFT -> SUBMITTED
 *   Non-DRAFT -> delete rejected
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotationStateMachineTest {

    private static String testCustomerId;

    @BeforeEach
    void ensureCustomer() {
        if (testCustomerId == null) {
            String body = """
                    {
                      "name": "StateMachine Test Customer",
                      "level": "STANDARD",
                      "contacts": [
                        {"name": "Test Contact", "phone": "13900001234", "isPrimary": true}
                      ]
                    }
                    """;
            testCustomerId = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(body)
                    .post("/api/cpq/customers")
                    .then()
                        .statusCode(200)
                    .extract().path("data.id");
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: Create a quotation — it starts in DRAFT status
    // -------------------------------------------------------------------------
    @Test
    @Order(1)
    void createQuotation_initialStatusIsDraft() {
        String body = """
                {
                  "customerId": "%s",
                  "name": "SM Test Quotation",
                  "quoteType": "STANDARD"
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
                    .body("data.status", equalTo("DRAFT"));
    }

    // -------------------------------------------------------------------------
    // Test 2: Delete works for DRAFT quotation
    // -------------------------------------------------------------------------
    @Test
    @Order(2)
    void deleteDraftQuotation_succeeds() {
        // Create a DRAFT quotation
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "SM Delete Test Quotation",
                  "quoteType": "STANDARD"
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .post("/api/cpq/quotations")
                .then()
                    .statusCode(200)
                    .body("data.status", equalTo("DRAFT"))
                .extract().path("data.id");

        // Delete the DRAFT quotation — should succeed
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

    // -------------------------------------------------------------------------
    // Test 3: Only DRAFT quotations can be deleted
    // -------------------------------------------------------------------------
    @Test
    @Order(3)
    void deleteSubmittedQuotation_rejected() {
        // Create a DRAFT quotation
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "SM Submit Then Delete",
                  "quoteType": "STANDARD"
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .post("/api/cpq/quotations")
                .then()
                    .statusCode(200)
                .extract().path("data.id");

        // Submit the quotation -> status becomes SUBMITTED
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                    .post("/api/cpq/quotations/" + id + "/submit")
                .then()
                    .statusCode(200)
                    .body("data.status", equalTo("SUBMITTED"));

        // Attempt to delete a SUBMITTED quotation — should be rejected (400 or 409)
        RestAssured.given()
                .when()
                    .delete("/api/cpq/quotations/" + id)
                .then()
                    .statusCode(anyOf(equalTo(400), equalTo(409)));
    }

    // -------------------------------------------------------------------------
    // Test 4: Submitted quotation is not accessible by delete but still findable
    // -------------------------------------------------------------------------
    @Test
    @Order(4)
    void submittedQuotation_stillRetrievableAfterFailedDelete() {
        // Create and submit a quotation
        String createBody = """
                {
                  "customerId": "%s",
                  "name": "SM Persist After Failed Delete",
                  "quoteType": "STANDARD"
                }
                """.formatted(testCustomerId);

        String id = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .post("/api/cpq/quotations")
                .then()
                    .statusCode(200)
                .extract().path("data.id");

        // Submit
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/api/cpq/quotations/" + id + "/submit")
                .then()
                    .statusCode(200);

        // Attempt delete (should fail)
        RestAssured.given()
                .delete("/api/cpq/quotations/" + id)
                .then()
                    .statusCode(anyOf(equalTo(400), equalTo(409)));

        // Verify quotation still exists with SUBMITTED status
        RestAssured.given()
                .when()
                    .get("/api/cpq/quotations/" + id)
                .then()
                    .statusCode(200)
                    .body("data.status", equalTo("SUBMITTED"));
    }
}
