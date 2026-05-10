package com.cpq.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.hamcrest.Matchers.*;

/**
 * E2E golden-path integration test covering the core CPQ business flow:
 * Customer -> Product -> PricingStrategy -> Template -> Binding -> Quotation -> Discount
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotationLifecycleTest {

    // Unique suffix to prevent conflicts with data from previous test runs
    private static final String RUN_ID = String.valueOf(System.currentTimeMillis() % 100000);

    // Shared state across ordered test methods
    private static String customerId;
    private static String productId;
    private static String strategyId;
    private static String templateId;
    private static String quotationId;
    private static String quotationNumber;

    // -------------------------------------------------------------------------
    // Step 1: Create a customer with a contact
    // -------------------------------------------------------------------------
    @Test
    @Order(1)
    void step1_createCustomerWithContact() {
        String body = """
                {
                  "name": "Lifecycle Test Customer",
                  "level": "GOLD",
                  "region": "华南",
                  "contacts": [
                    {"name": "李明", "phone": "13800001111", "email": "liming@test.com", "isPrimary": true}
                  ]
                }
                """;

        customerId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/customers")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.name", equalTo("Lifecycle Test Customer"))
                    .body("data.level", equalTo("GOLD"))
                    .body("data.contacts.size()", equalTo(1))
                .extract().path("data.id");

        assert customerId != null : "customerId must not be null after creation";
    }

    // -------------------------------------------------------------------------
    // Step 2: Create a product
    // -------------------------------------------------------------------------
    @Test
    @Order(2)
    void step2_createProduct() {
        String body = """
                {
                  "name": "Lifecycle Test Product",
                  "partNo": "LC-SKU-%s",
                  "category": "STANDARD",
                  "specification": "100x200x50mm"
                }
                """.formatted(RUN_ID);

        productId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/products")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.name", equalTo("Lifecycle Test Product"))
                    .body("data.status", equalTo("ACTIVE"))
                .extract().path("data.id");

        assert productId != null : "productId must not be null after creation";
    }

    // -------------------------------------------------------------------------
    // Step 3: Create a pricing strategy with a bulk discount rule for the customer
    // -------------------------------------------------------------------------
    @Test
    @Order(3)
    void step3_createPricingStrategyWithBulkDiscount() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String nextYear = LocalDate.now().plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String body = """
                {
                  "customerId": "%s",
                  "name": "Lifecycle Gold Strategy",
                  "type": "DISCOUNT",
                  "baseDiscount": 95.00,
                  "minOrderAmount": 5000.00,
                  "effectiveDate": "%s",
                  "expirationDate": "%s",
                  "priority": 1,
                  "rules": [
                    {
                      "ruleType": "BULK_DISCOUNT",
                      "thresholdAmount": 50000.00,
                      "discountRate": 90.00,
                      "sortOrder": 1
                    }
                  ]
                }
                """.formatted(customerId, today, nextYear);

        strategyId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/pricing-strategies")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.name", equalTo("Lifecycle Gold Strategy"))
                    .body("data.baseDiscount", equalTo(95.00f))
                    .body("data.rules.size()", equalTo(1))
                    .body("data.rules[0].ruleType", equalTo("BULK_DISCOUNT"))
                .extract().path("data.id");

        assert strategyId != null : "strategyId must not be null after creation";
    }

    // -------------------------------------------------------------------------
    // Step 4a: Create a template in DRAFT status (with subtotalFormula for publish)
    // -------------------------------------------------------------------------
    @Test
    @Order(4)
    void step4a_createDraftTemplate() {
        String body = """
                {
                  "name": "Lifecycle Test Template",
                  "description": "Template for lifecycle testing",
                  "subtotalFormula": "[{\\"type\\":\\"field_ref\\",\\"ref\\":\\"weight\\"}]"
                }
                """;

        templateId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/templates")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.name", equalTo("Lifecycle Test Template"))
                    .body("data.status", equalTo("DRAFT"))
                .extract().path("data.id");

        assert templateId != null : "templateId must not be null after creation";
    }

    // -------------------------------------------------------------------------
    // Step 4b: Add a component to the template
    // -------------------------------------------------------------------------
    @Test
    @Order(5)
    void step4b_addComponentToTemplate() {
        // First create a component directory
        String dirBody = """
                {"name": "Lifecycle Dir", "sortOrder": 99}
                """;
        String dirId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(dirBody)
                .post("/api/cpq/component-directories")
                .then()
                    .statusCode(200)
                .extract().path("data.id");

        // Create a component
        String compBody = """
                {
                  "name": "Lifecycle Component",
                  "code": "LC-COMP-%s",
                  "directoryId": "%s",
                  "fields": [
                    {"name": "weight", "field_type": "INPUT"}
                  ],
                  "formulas": []
                }
                """.formatted(RUN_ID, dirId);
        String componentId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(compBody)
                .post("/api/cpq/components")
                .then()
                    .statusCode(200)
                .extract().path("data.id");

        // Add the component to the template
        String addBody = """
                {
                  "componentId": "%s",
                  "tabName": "基本参数"
                }
                """.formatted(componentId);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(addBody)
                .when()
                    .post("/api/cpq/templates/" + templateId + "/components")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.tabName", equalTo("基本参数"));
    }

    // -------------------------------------------------------------------------
    // Step 4c: Publish the template
    // -------------------------------------------------------------------------
    @Test
    @Order(6)
    void step4c_publishTemplate() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                    .post("/api/cpq/templates/" + templateId + "/publish")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.status", equalTo("PUBLISHED"));
    }

    // -------------------------------------------------------------------------
    // Step 5: Create a product-template binding
    // -------------------------------------------------------------------------
    @Test
    @Order(7)
    void step5_createProductTemplateBinding() {
        String body = """
                {
                  "templateId": "%s",
                  "isDefault": true,
                  "processIds": []
                }
                """.formatted(templateId);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/products/" + productId + "/template-bindings")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.templateId", equalTo(templateId))
                    .body("data.productId", equalTo(productId));
    }

    // -------------------------------------------------------------------------
    // Step 6: Create a quotation for the customer
    // -------------------------------------------------------------------------
    @Test
    @Order(8)
    void step6_createQuotationForCustomer() {
        String body = """
                {
                  "customerId": "%s",
                  "name": "Lifecycle Quotation 001",
                  "projectName": "Lifecycle Integration Test Project",
                  "quoteType": "STANDARD",
                  "priority": "MEDIUM"
                }
                """.formatted(customerId);

        var response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/quotations")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.status", equalTo("DRAFT"))
                    .body("data.quotationNumber", notNullValue())
                    .body("data.customerId", equalTo(customerId))
                .extract().response();

        quotationId = response.path("data.id");
        quotationNumber = response.path("data.quotationNumber");

        assert quotationId != null : "quotationId must not be null after creation";
        assert quotationNumber != null : "quotationNumber must not be null after creation";
    }

    // -------------------------------------------------------------------------
    // Step 7: Verify quotation number format (QT-YYYYMMDD-XXXX)
    // -------------------------------------------------------------------------
    @Test
    @Order(9)
    void step7_verifyQuotationNumberFormat() {
        // Format: QT-YYYYMMDD-XXXX where XXXX is a zero-padded sequence
        String expectedDatePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String expectedPrefix = "QT-" + expectedDatePart + "-";

        assert quotationNumber != null : "quotationNumber must be set by step6";
        assert quotationNumber.startsWith(expectedPrefix)
                : "Quotation number '" + quotationNumber + "' should start with '" + expectedPrefix + "'";
        assert quotationNumber.length() > expectedPrefix.length()
                : "Quotation number should have a sequence suffix after '" + expectedPrefix + "'";
    }

    // -------------------------------------------------------------------------
    // Step 8+9: Calculate discount and verify it was applied correctly
    // -------------------------------------------------------------------------
    @Test
    @Order(10)
    void step8_calculateDiscountAndVerifyApplied() {
        // Use 30000 which is below the bulk discount threshold of 50000
        // -> should use baseDiscount = 95.00
        String body = """
                {"originalAmount": 30000.00}
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/quotations/" + quotationId + "/calculate-discount")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.originalAmount", equalTo(30000.00f))
                    // baseDiscount is 95%, so systemDiscountRate should be 95.00
                    .body("data.systemDiscountRate", equalTo(95.00f))
                    .body("data.finalDiscountRate", equalTo(95.00f))
                    // totalAmount = 30000 * 95/100 = 28500
                    .body("data.totalAmount", equalTo(28500.00f));
    }

    @Test
    @Order(11)
    void step9_calculateDiscountAboveThresholdAppliesBulkRule() {
        // Use 60000 which exceeds the bulk discount threshold of 50000
        // -> should use discountRate = 90.00
        String body = """
                {"originalAmount": 60000.00}
                """;

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/quotations/" + quotationId + "/calculate-discount")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.originalAmount", equalTo(60000.00f))
                    // bulk discount rule: discountRate = 90.00
                    .body("data.systemDiscountRate", equalTo(90.00f))
                    .body("data.finalDiscountRate", equalTo(90.00f))
                    // totalAmount = 60000 * 90/100 = 54000
                    .body("data.totalAmount", equalTo(54000.00f));
    }
}
