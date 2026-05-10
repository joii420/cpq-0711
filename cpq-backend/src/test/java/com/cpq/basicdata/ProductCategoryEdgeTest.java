package com.cpq.basicdata;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.hamcrest.Matchers.*;

/**
 * Edge-case tests for ProductCategory:
 *   CAT-CYCLE-10 : setting a circular parent reference must return 400
 *   CAT-DELETE-11: deleting a category that has children must return 400
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductCategoryEdgeTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        // Remove child categories first (FK parent_id dependency)
        em.createNativeQuery(
                "DELETE FROM product_category WHERE code LIKE 'EDGE-CAT-%'")
                .executeUpdate();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CAT-CYCLE-10
    // PRD: creating a circular parent chain must be rejected.
    // Scenario: create A (no parent), create B (parent=A),
    //           then PUT A with parentId=B → must return 400 with a message
    //           indicating "Circular" or "cycle" or "循环".
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("CAT-CYCLE-10 循环父级引用 PUT 返回 400 含循环/cycle 关键词")
    void catCycle10_circularParent_returns400() {
        // Step 1: create category A (root)
        String bodyA = """
                {
                  "code": "EDGE-CAT-A",
                  "name": "Edge Category A"
                }
                """;
        String idA = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(bodyA)
                .when()
                    .post("/api/cpq/product-categories")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .extract()
                    .path("data.id");

        // Step 2: create category B with parentId = A
        String bodyB = String.format("""
                {
                  "code": "EDGE-CAT-B",
                  "name": "Edge Category B",
                  "parentId": "%s"
                }
                """, idA);
        String idB = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(bodyB)
                .when()
                    .post("/api/cpq/product-categories")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .extract()
                    .path("data.id");

        // Step 3: PUT A with parentId = B → must fail with circular reference error
        String updateA = String.format("""
                {
                  "code": "EDGE-CAT-A",
                  "name": "Edge Category A",
                  "parentId": "%s"
                }
                """, idB);
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(updateA)
                .when()
                    .put("/api/cpq/product-categories/" + idA)
                .then()
                    .statusCode(400)
                    .body("message", anyOf(
                            containsStringIgnoringCase("Circular"),
                            containsStringIgnoringCase("cycle"),
                            containsStringIgnoringCase("循环")));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CAT-DELETE-11
    // PRD: deleting a category that still has child categories must be rejected
    // with HTTP 400 and a message indicating "child" / "children" / "子分类".
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("CAT-DELETE-11 有子分类时 DELETE 返回 400 含 child/子分类 关键词")
    void catDelete11_categoryWithChildren_returns400() {
        // Step 1: create parent category P
        String bodyP = """
                {
                  "code": "EDGE-CAT-P",
                  "name": "Edge Category P (parent)"
                }
                """;
        String idP = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(bodyP)
                .when()
                    .post("/api/cpq/product-categories")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .extract()
                    .path("data.id");

        // Step 2: create child category P1 under P
        String bodyP1 = String.format("""
                {
                  "code": "EDGE-CAT-P1",
                  "name": "Edge Category P1 (child)",
                  "parentId": "%s"
                }
                """, idP);
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(bodyP1)
                .when()
                    .post("/api/cpq/product-categories")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200));

        // Step 3: attempt DELETE P — must fail because P1 exists
        RestAssured.given()
                .when()
                    .delete("/api/cpq/product-categories/" + idP)
                .then()
                    .statusCode(400)
                    .body("message", anyOf(
                            containsStringIgnoringCase("child"),
                            containsStringIgnoringCase("children"),
                            containsStringIgnoringCase("子分类")));
    }
}
