package com.cpq.health;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class HealthResourceTest {

    @Test
    void healthEndpointReturnsOk() {
        RestAssured.given()
            .when()
                .get("/api/cpq/health")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("UP"))
                .body("data.service", equalTo("CPQ Quotation System"));
    }
}
