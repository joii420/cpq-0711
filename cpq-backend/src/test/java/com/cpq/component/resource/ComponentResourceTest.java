package com.cpq.component.resource;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentDirectory;
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
class ComponentResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM Component c WHERE c.code LIKE 'TEST-%'").executeUpdate();
        em.createQuery("DELETE FROM ComponentDirectory d WHERE d.name LIKE 'Test Dir%'").executeUpdate();
    }

    @Test
    @Order(1)
    void createDirectoryAndComponent() {
        // Create directory
        String dirBody = """
            {"name": "Test Dir Alpha", "sortOrder": 1}
            """;
        String dirId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(dirBody)
            .post("/api/cpq/component-directories")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Dir Alpha"))
                .extract().path("data.id");

        // Create component with fields and formulas
        String compBody = """
            {
              "name": "Test Component A",
              "code": "TEST-COMP-001",
              "directoryId": "%s",
              "fields": [
                {"name": "weight", "field_type": "INPUT"},
                {"name": "length", "field_type": "INPUT"},
                {"name": "total", "field_type": "FORMULA"}
              ],
              "formulas": [
                {"name": "total", "expression": [{"type": "field_ref", "ref": "weight"}, {"type": "operator", "value": "*"}, {"type": "field_ref", "ref": "length"}]}
              ]
            }
            """.formatted(dirId);

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(compBody)
            .post("/api/cpq/components")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Component A"))
                .body("data.code", equalTo("TEST-COMP-001"))
                .body("data.columnCount", equalTo(3))
                .body("data.fields.size()", equalTo(3));
    }

    @Test
    @Order(2)
    void columnCountAutoCalculated() {
        String body = """
            {
              "name": "Test Component B",
              "code": "TEST-COMP-002",
              "fields": [
                {"name": "f1", "field_type": "INPUT"},
                {"name": "f2", "field_type": "FIXED_VALUE"},
                {"name": "f3", "field_type": "INPUT"},
                {"name": "f4", "field_type": "INPUT"}
              ],
              "formulas": []
            }
            """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/components")
            .then()
                .statusCode(200)
                .body("data.columnCount", equalTo(4));
    }

    @Test
    @Order(3)
    void codeUniquenessCheck() {
        String body = """
            {
              "name": "Test Component C",
              "code": "TEST-COMP-DUP",
              "fields": []
            }
            """;
        // First create succeeds
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/components")
            .then()
                .statusCode(200);

        // Second create with same code fails
        String body2 = """
            {
              "name": "Test Component C Dup",
              "code": "TEST-COMP-DUP",
              "fields": []
            }
            """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body2)
            .post("/api/cpq/components")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void listComponentsAndGetById() {
        // Create a component
        String body = """
            {
              "name": "Test Component D",
              "code": "TEST-COMP-004",
              "fields": [
                {"name": "qty", "field_type": "INPUT", "is_subtotal": true}
              ]
            }
            """;
        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/components")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // List
        RestAssured.given()
            .queryParam("keyword", "Test Component D")
            .get("/api/cpq/components")
            .then()
                .statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(1));

        // Get by id
        RestAssured.given()
            .get("/api/cpq/components/" + id)
            .then()
                .statusCode(200)
                .body("data.code", equalTo("TEST-COMP-004"))
                .body("data.columnCount", equalTo(1));
    }
}
