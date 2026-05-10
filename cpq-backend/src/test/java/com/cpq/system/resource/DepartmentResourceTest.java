package com.cpq.system.resource;

import com.cpq.system.entity.Department;
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
class DepartmentResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createQuery("DELETE FROM Department d WHERE d.code = 'SALES_DEPT_4'").executeUpdate();
    }

    @Test
    @Order(1)
    void listDepartments() {
        RestAssured.given()
            .when()
                .get("/api/cpq/departments")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(3));
    }

    @Test
    @Order(2)
    void createDepartment() {
        String body = """
                {
                  "code": "SALES_DEPT_4",
                  "name": "销售四部",
                  "sortOrder": 4,
                  "status": "ACTIVE"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/departments")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.code", equalTo("SALES_DEPT_4"))
                .body("data.name", equalTo("销售四部"));
    }

    @Test
    @Order(3)
    void createDepartmentDuplicateCodeFails() {
        String body = """
                {
                  "code": "SALES_DEPT_1",
                  "name": "销售一部重复",
                  "sortOrder": 99
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/departments")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void disableDepartment() {
        // Create SALES_DEPT_4 first (cleanup already ran)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                    {"code":"SALES_DEPT_4","name":"销售四部","sortOrder":4,"status":"ACTIVE"}
                    """)
            .post("/api/cpq/departments");

        String id = RestAssured.given()
            .when()
                .get("/api/cpq/departments?page=0&size=100")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.content.find { it.code == 'SALES_DEPT_4' }.id");

        String body = """
                {
                  "status": "DISABLED"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .patch("/api/cpq/departments/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("DISABLED"));
    }
}
