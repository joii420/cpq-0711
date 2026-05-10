package com.cpq.system.resource;

import com.cpq.system.entity.User;
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
class UserResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createQuery("DELETE FROM User u WHERE u.username = 'testuser1'").executeUpdate();
    }

    @Test
    @Order(1)
    void listUsers() {
        RestAssured.given()
            .when()
                .get("/api/cpq/users")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(2)
    void createUser() {
        String body = """
                {
                  "username": "testuser1",
                  "fullName": "Test User One",
                  "email": "testuser1@example.com",
                  "role": "SALES_REP"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/users")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.username", equalTo("testuser1"))
                .body("data.initialPassword", notNullValue())
                .body("data.isFirstLogin", equalTo(true));
    }

    @Test
    @Order(3)
    void createUserDuplicateUsernameFails() {
        String body = """
                {
                  "username": "admin",
                  "fullName": "Duplicate Admin",
                  "email": "duplicate@example.com",
                  "role": "SYSTEM_ADMIN"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/users")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void updateUser() {
        // Get admin's ID
        String adminId = RestAssured.given()
            .when()
                .get("/api/cpq/users?keyword=admin")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.content.find { it.username == 'admin' }.id");

        String body = """
                {
                  "fullName": "System Administrator Updated"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .put("/api/cpq/users/" + adminId)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.fullName", equalTo("System Administrator Updated"));

        // Restore original name
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                    {"fullName": "系统管理员"}
                    """)
            .put("/api/cpq/users/" + adminId);
    }

    @Test
    @Order(5)
    void disableLastAdminFails() {
        // Get admin's ID
        String adminId = RestAssured.given()
            .when()
                .get("/api/cpq/users?keyword=admin")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.content.find { it.username == 'admin' }.id");

        String body = """
                {
                  "status": "INACTIVE"
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .patch("/api/cpq/users/" + adminId)
            .then()
                .statusCode(400)
                .body("message", containsString("管理员"));
    }
}
