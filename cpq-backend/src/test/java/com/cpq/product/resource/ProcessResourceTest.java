package com.cpq.product.resource;

import com.cpq.product.entity.Product;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessResourceTest {

    @Inject
    EntityManager em;

    private static String testProductId;

    @BeforeEach
    @Transactional
    void setup() {
        // Ensure a test product exists (create if needed)
        if (testProductId == null) {
            // Find or create a product for binding tests
            String partNo = "PROC-TEST-SKU-001";
            long count = Product.count("partNo", partNo);
            if (count == 0) {
                Product p = new Product();
                p.name = "Process Test Product";
                p.partNo = partNo;
                p.category = "STANDARD";
                p.status = "ACTIVE";
                p.tags = "[]";
                p.persist();
                testProductId = p.id.toString();
            } else {
                testProductId = Product.<Product>find("partNo", partNo).firstResult().id.toString();
            }
        }
        // Clean up any existing bindings for the test product
        em.createNativeQuery("DELETE FROM product_process WHERE product_id = :pid")
          .setParameter("pid", UUID.fromString(testProductId))
          .executeUpdate();
    }

    @Test
    @Order(1)
    void listAllReturnsAtLeast26Items() {
        RestAssured.given()
            .when()
                .get("/api/cpq/processes")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.size()", greaterThanOrEqualTo(26));
    }

    @Test
    @Order(2)
    void filterByCategoryMachiningReturns5() {
        RestAssured.given()
            .queryParam("category", "MACHINING")
            .when()
                .get("/api/cpq/processes")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.size()", equalTo(5));
    }

    @Test
    @Order(3)
    void bindGetUnbindCycle() {
        // First get all process IDs for MACHINING
        io.restassured.response.Response allProcesses = RestAssured.given()
            .queryParam("category", "MACHINING")
            .when()
                .get("/api/cpq/processes")
            .then()
                .statusCode(200)
                .extract().response();

        // Extract first two process IDs
        String processId1 = allProcesses.path("data[0].id");
        String processId2 = allProcesses.path("data[1].id");

        // Bind two processes to the test product
        String bindBody = String.format("""
                {"processIds": ["%s", "%s"]}
                """, processId1, processId2);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(bindBody)
            .when()
                .post("/api/cpq/products/{productId}/processes", testProductId)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Get bound processes
        RestAssured.given()
            .when()
                .get("/api/cpq/products/{productId}/processes", testProductId)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.size()", equalTo(2));

        // Unbind all
        RestAssured.given()
            .when()
                .delete("/api/cpq/products/{productId}/processes", testProductId)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Verify empty after unbind
        RestAssured.given()
            .when()
                .get("/api/cpq/products/{productId}/processes", testProductId)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.size()", equalTo(0));
    }
}
