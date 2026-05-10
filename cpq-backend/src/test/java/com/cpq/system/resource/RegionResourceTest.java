package com.cpq.system.resource;

import com.cpq.system.entity.Region;
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
class RegionResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createQuery("DELETE FROM Region r WHERE r.code = 'SOUTHWEST'").executeUpdate();
    }

    @Test
    @Order(1)
    void listRegions() {
        RestAssured.given()
            .when()
                .get("/api/cpq/regions")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(4));
    }

    @Test
    @Order(2)
    void createRegion() {
        String body = """
                {
                  "code": "SOUTHWEST",
                  "name": "西南",
                  "sortOrder": 5,
                  "status": "ACTIVE"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/regions")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.code", equalTo("SOUTHWEST"))
                .body("data.name", equalTo("西南"));
    }

    @Test
    @Order(3)
    void createRegionDuplicateCodeFails() {
        String body = """
                {
                  "code": "SOUTH_CHINA",
                  "name": "华南重复",
                  "sortOrder": 99
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/regions")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void updateRegion() {
        // Create SOUTHWEST first (cleanup already ran)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                    {"code":"SOUTHWEST","name":"西南","sortOrder":5,"status":"ACTIVE"}
                    """)
            .post("/api/cpq/regions");

        String id = RestAssured.given()
            .when()
                .get("/api/cpq/regions?page=0&size=100")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.content.find { it.code == 'SOUTHWEST' }.id");

        String body = """
                {
                  "code": "SOUTHWEST",
                  "name": "西南大区",
                  "sortOrder": 5,
                  "status": "ACTIVE"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .put("/api/cpq/regions/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("西南大区"));
    }

    @Test
    @Order(5)
    void disableRegion() {
        // Create SOUTHWEST first (cleanup already ran)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                    {"code":"SOUTHWEST","name":"西南","sortOrder":5,"status":"ACTIVE"}
                    """)
            .post("/api/cpq/regions");

        String id = RestAssured.given()
            .when()
                .get("/api/cpq/regions?page=0&size=100")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.content.find { it.code == 'SOUTHWEST' }.id");

        String body = """
                {
                  "status": "DISABLED"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .patch("/api/cpq/regions/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("DISABLED"));
    }
}
