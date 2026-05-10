package com.cpq.product.resource;

import com.cpq.product.entity.Product;
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
class ProductResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createQuery("DELETE FROM Product p WHERE p.name LIKE 'Test Product%'").executeUpdate();
    }

    @Test
    @Order(1)
    void listProducts() {
        RestAssured.given()
            .when()
                .get("/api/cpq/products")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content", notNullValue())
                .body("data.totalElements", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    void createProduct() {
        String body = """
                {
                  "name": "Test Product Alpha",
                  "partNo": "TEST-SKU-001",
                  "category": "STANDARD",
                  "specification": "10x20x30mm",
                  "tags": ["tag1", "tag2"]
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/products")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Product Alpha"))
                .body("data.partNo", equalTo("TEST-SKU-001"))
                .body("data.category", equalTo("STANDARD"))
                .body("data.status", equalTo("ACTIVE"))
                .body("data.tags.size()", equalTo(2));
    }

    @Test
    @Order(3)
    void createProductDuplicatePartNoFails() {
        String body = """
                {
                  "name": "Test Product Beta",
                  "partNo": "TEST-SKU-DUP",
                  "category": "STANDARD"
                }
                """;

        // First create
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products");

        // Attempt duplicate
        String body2 = """
                {
                  "name": "Test Product Beta Dup",
                  "partNo": "TEST-SKU-DUP",
                  "category": "CUSTOM"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body2)
            .when()
                .post("/api/cpq/products")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void searchProductsByKeyword() {
        // Create a product first
        String body = """
                {
                  "name": "Test Product Gamma",
                  "partNo": "TEST-SKU-GAMMA",
                  "category": "CUSTOM"
                }
                """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products");

        RestAssured.given()
            .queryParam("keyword", "Test Product Gamma")
            .when()
                .get("/api/cpq/products")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(5)
    void softDeleteProduct() {
        // Create a product first
        String body = """
                {
                  "name": "Test Product Delta",
                  "partNo": "TEST-SKU-DELTA",
                  "category": "RAW_MATERIAL"
                }
                """;

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products")
            .then()
                .statusCode(200)
                .extract()
                .path("data.id");

        // Delete (soft)
        RestAssured.given()
            .when()
                .delete("/api/cpq/products/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Verify status is INACTIVE in DB
        RestAssured.given()
            .queryParam("keyword", "Test Product Delta")
            .queryParam("status", "INACTIVE")
            .when()
                .get("/api/cpq/products")
            .then()
                .statusCode(200)
                .body("data.content.size()", greaterThanOrEqualTo(1))
                .body("data.content[0].status", equalTo("INACTIVE"));
    }
}
