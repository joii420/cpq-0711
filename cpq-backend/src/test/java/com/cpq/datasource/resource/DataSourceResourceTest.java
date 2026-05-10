package com.cpq.datasource.resource;

import com.cpq.datasource.entity.DataSource;
import com.cpq.datasource.entity.DataSourceParam;
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
class DataSourceResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM DataSourceParam p WHERE p.datasourceId IN " +
                "(SELECT d.id FROM DataSource d WHERE d.code LIKE 'TEST_%')").executeUpdate();
        em.createQuery("DELETE FROM DataSource d WHERE d.code LIKE 'TEST_%'").executeUpdate();
    }

    @Test
    @Order(1)
    void listDatasources() {
        RestAssured.given()
            .when()
                .get("/api/cpq/datasources")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content", notNullValue())
                .body("data.totalElements", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    void createSqlDatasource() {
        String body = """
                {
                  "code": "TEST_SQL_001",
                  "name": "Test SQL DataSource",
                  "type": "SQL",
                  "sqlQuery": "SELECT name FROM customer WHERE code = ?",
                  "sqlResultColumn": "name",
                  "params": [
                    {
                      "paramCode": "customerCode",
                      "paramName": "Customer Code",
                      "sourceType": "USER_FIELD",
                      "isRequired": true
                    }
                  ]
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/datasources")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.code", equalTo("TEST_SQL_001"))
                .body("data.name", equalTo("Test SQL DataSource"))
                .body("data.type", equalTo("SQL"))
                .body("data.status", equalTo("ACTIVE"))
                .body("data.params.size()", equalTo(1))
                .body("data.params[0].paramCode", equalTo("customerCode"));
    }

    @Test
    @Order(3)
    void getById() {
        // First create
        String body = """
                {
                  "code": "TEST_SQL_002",
                  "name": "Test Get By ID",
                  "type": "SQL",
                  "sqlQuery": "SELECT 1"
                }
                """;

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/datasources")
            .then()
                .statusCode(200)
                .extract()
                .path("data.id");

        // Then get
        RestAssured.given()
            .when()
                .get("/api/cpq/datasources/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.id", equalTo(id))
                .body("data.code", equalTo("TEST_SQL_002"));
    }

    @Test
    @Order(4)
    void createDuplicateCodeFails() {
        String body = """
                {
                  "code": "TEST_DUP_001",
                  "name": "Duplicate Test",
                  "type": "SQL",
                  "sqlQuery": "SELECT 1"
                }
                """;

        // First creation should succeed
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/datasources")
            .then()
                .statusCode(200);

        // Second creation with same code should fail
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/datasources")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(5)
    void rejectDeleteStatement() {
        // Create datasource with a DELETE statement — then update it to a DELETE query and test
        String body = """
                {
                  "code": "TEST_BAD_SQL_001",
                  "name": "Bad SQL DataSource",
                  "type": "SQL",
                  "sqlQuery": "SELECT 1"
                }
                """;

        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/datasources")
            .then()
                .statusCode(200)
                .extract()
                .path("data.id");

        // Update to a dangerous DELETE query
        String updateBody = """
                {
                  "name": "Bad SQL DataSource",
                  "sqlQuery": "DELETE FROM customer WHERE 1=1"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(updateBody)
            .when()
                .put("/api/cpq/datasources/" + id)
            .then()
                .statusCode(200);

        // Test endpoint should reject the dangerous SQL at execution time
        String testBody = """
                {
                  "testParams": {}
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(testBody)
            .when()
                .post("/api/cpq/datasources/" + id + "/test")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(false))
                .body("data.errorMessage", containsString("Only SELECT"));
    }
}
