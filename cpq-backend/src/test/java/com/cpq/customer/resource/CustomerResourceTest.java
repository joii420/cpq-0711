package com.cpq.customer.resource;

import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;
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
class CustomerResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createQuery("DELETE FROM CustomerContact c WHERE c.customerId IN " +
                "(SELECT cu.id FROM Customer cu WHERE cu.name LIKE 'Test Customer%')").executeUpdate();
        em.createQuery("DELETE FROM Customer c WHERE c.name LIKE 'Test Customer%'").executeUpdate();
    }

    @Test
    @Order(1)
    void listCustomers() {
        RestAssured.given()
            .when()
                .get("/api/cpq/customers")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content", notNullValue())
                .body("data.totalElements", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    void createCustomerWithContacts() {
        String body = """
                {
                  "name": "Test Customer Alpha",
                  "level": "GOLD",
                  "industry": "Manufacturing",
                  "region": "East",
                  "contacts": [
                    {
                      "name": "Zhang San",
                      "role": "Procurement",
                      "phone": "13800138001",
                      "email": "zhangsan@test.com",
                      "isPrimary": true
                    }
                  ]
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/customers")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Customer Alpha"))
                .body("data.level", equalTo("GOLD"))
                .body("data.code", startsWith("CUST-"))
                .body("data.status", equalTo("ACTIVE"))
                .body("data.contacts.size()", equalTo(1))
                .body("data.contacts[0].isPrimary", equalTo(true));
    }

    @Test
    @Order(3)
    void createCustomerWithoutContactFails() {
        String body = """
                {
                  "name": "Test Customer NoPrimary",
                  "contacts": []
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/customers")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(4)
    void createCustomerWithoutPrimaryContactFails() {
        String body = """
                {
                  "name": "Test Customer NoPrimary2",
                  "contacts": [
                    {
                      "name": "Li Si",
                      "phone": "13900139001",
                      "isPrimary": false
                    }
                  ]
                }
                """;

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/customers")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(5)
    void searchCustomersByKeyword() {
        // First create a customer
        String body = """
                {
                  "name": "Test Customer Beta",
                  "level": "VIP",
                  "contacts": [
                    {"name": "Wang Wu", "phone": "13700137001", "isPrimary": true}
                  ]
                }
                """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/customers");

        // Search by keyword
        RestAssured.given()
            .queryParam("keyword", "Test Customer Beta")
            .when()
                .get("/api/cpq/customers")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(6)
    void getCustomerById() {
        // Create a customer
        String body = """
                {
                  "name": "Test Customer Gamma",
                  "level": "SILVER",
                  "contacts": [
                    {"name": "Zhao Liu", "phone": "13600136001", "isPrimary": true}
                  ]
                }
                """;
        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/customers")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.id");

        RestAssured.given()
            .when()
                .get("/api/cpq/customers/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.id", equalTo(id))
                .body("data.name", equalTo("Test Customer Gamma"))
                .body("data.contacts.size()", equalTo(1));
    }

    @Test
    @Order(7)
    void softDeleteCustomer() {
        // Create a customer
        String body = """
                {
                  "name": "Test Customer Delta",
                  "contacts": [
                    {"name": "Chen Qi", "phone": "13500135001", "isPrimary": true}
                  ]
                }
                """;
        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/customers")
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.id");

        // Delete it
        RestAssured.given()
            .when()
                .delete("/api/cpq/customers/" + id)
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Verify it's INACTIVE
        RestAssured.given()
            .when()
                .get("/api/cpq/customers/" + id)
            .then()
                .statusCode(200)
                .body("data.status", equalTo("INACTIVE"));
    }

    @Test
    @Order(8)
    void batchDeleteCustomers() {
        // Create two customers
        String body1 = """
                {"name": "Test Customer Epsilon", "contacts": [{"name": "A", "phone": "13400134001", "isPrimary": true}]}
                """;
        String body2 = """
                {"name": "Test Customer Zeta", "contacts": [{"name": "B", "phone": "13300133001", "isPrimary": true}]}
                """;

        String id1 = RestAssured.given().contentType(ContentType.JSON).body(body1)
                .post("/api/cpq/customers").then().extract().jsonPath().getString("data.id");
        String id2 = RestAssured.given().contentType(ContentType.JSON).body(body2)
                .post("/api/cpq/customers").then().extract().jsonPath().getString("data.id");

        String batchBody = String.format("{\"ids\": [\"%s\", \"%s\"]}", id1, id2);
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(batchBody)
            .when()
                .post("/api/cpq/customers/batch-delete")
            .then()
                .statusCode(200)
                .body("code", equalTo(200));

        // Verify both are INACTIVE
        RestAssured.given().get("/api/cpq/customers/" + id1)
            .then().body("data.status", equalTo("INACTIVE"));
        RestAssured.given().get("/api/cpq/customers/" + id2)
            .then().body("data.status", equalTo("INACTIVE"));
    }

    @Test
    @Order(9)
    void filterByLevel() {
        // Create a DIAMOND customer
        String body = """
                {
                  "name": "Test Customer Diamond",
                  "level": "DIAMOND",
                  "contacts": [
                    {"name": "VIP Contact", "phone": "13200132001", "isPrimary": true}
                  ]
                }
                """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/customers");

        RestAssured.given()
            .queryParam("level", "DIAMOND")
            .when()
                .get("/api/cpq/customers")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(10)
    void invalidPhoneNumberFails() {
        String body = """
                {
                  "name": "Test Customer BadPhone",
                  "contacts": [
                    {"name": "Bad Phone", "phone": "123", "isPrimary": true}
                  ]
                }
                """;
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
                .post("/api/cpq/customers")
            .then()
                .statusCode(400);
    }
}
