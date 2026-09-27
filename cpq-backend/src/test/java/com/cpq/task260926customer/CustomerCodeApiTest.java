package com.cpq.task260926customer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-API-1: AC-1..AC-6 and AC-15 for customer create/update.
 *
 * This is a private-write slice.  Every row is intentionally prefixed with
 * CUST-260926- (or the equivalent name prefix); no cleanup or global count
 * assertions are used because the test database is shared with other slices.
 */
@QuarkusTest
class CustomerCodeApiTest {

    private static final String PREFIX = "CUST-260926-";
    private static final String PHONE = "13826092601";

    @Test
    @DisplayName("AC-1: omitted customer code receives a CUST- generated code and is stable in detail/list")
    void omittedCode_generatesAndPersistsCode() {
        String name = PREFIX + "AC1-" + System.nanoTime();
        String id = createCustomer(name, null);

        String code = RestAssured.given()
                .when().get("/api/cpq/customers/" + id)
                .then().statusCode(200)
                .body("data.name", equalTo(name))
                .body("data.code", startsWith("CUST-"))
                .extract().jsonPath().getString("data.code");
        assertNotNull(code);

        RestAssured.given().queryParam("keyword", name)
                .when().get("/api/cpq/customers")
                .then().statusCode(200)
                .body("data.totalElements", equalTo(1))
                .body("data.content[0].code", equalTo(code));
    }

    @Test
    @DisplayName("AC-2: a legal hand-entered code is persisted without sequence replacement")
    void manualCode_isPersisted() {
        String code = PREFIX + "AC2-" + System.nanoTime();
        String name = PREFIX + "AC2-NAME-" + System.nanoTime();
        String id = createCustomer(name, code);

        RestAssured.given().when().get("/api/cpq/customers/" + id)
                .then().statusCode(200)
                .body("data.code", equalTo(code))
                .body("data.name", equalTo(name));
        RestAssured.given().queryParam("keyword", code)
                .when().get("/api/cpq/customers")
                .then().statusCode(200)
                .body("data.totalElements", equalTo(1))
                .body("data.content[0].code", equalTo(code));
    }

    @Test
    @DisplayName("AC-3: code uniqueness is case-insensitive and rejects duplicate without a second row")
    void duplicateCode_caseInsensitive_returns409() {
        String code = PREFIX + "AC3-" + System.nanoTime();
        String name = PREFIX + "AC3-BASE-" + System.nanoTime();
        createCustomer(name, code);

        Response duplicate = createResponse(
                PREFIX + "AC3-DUP-" + System.nanoTime(), code.toLowerCase());
        assertEquals(409, duplicate.statusCode(), duplicate.asString());
        assertTrue(duplicate.asString().toLowerCase().contains("编号")
                        || duplicate.asString().toLowerCase().contains("code")
                        || duplicate.asString().toLowerCase().contains("exist"),
                "409 response should identify code conflict: " + duplicate.asString());

        assertEquals(1, searchTotal(name), "duplicate code must not create another customer");
    }

    @Test
    @DisplayName("AC-4: customer name uniqueness is case-insensitive")
    void duplicateName_caseInsensitive_returns409() {
        String name = PREFIX + "Ac4-Name-" + System.nanoTime();
        createCustomer(name, PREFIX + "AC4-BASE-" + System.nanoTime());

        Response duplicate = createResponse(
                name.toLowerCase(), PREFIX + "AC4-DUP-" + System.nanoTime());
        assertEquals(409, duplicate.statusCode(), duplicate.asString());
        assertEquals(1, searchTotal(name), "duplicate name must not create another customer");
    }

    @Test
    @DisplayName("AC-5: leading/trailing whitespace in code or name is rejected, never silently trimmed")
    void leadingTrailingWhitespace_returns400() {
        String codeCandidate = PREFIX + "AC5-CODE-" + System.nanoTime();
        Response codeWhitespace = createResponse(
                PREFIX + "AC5-CODE-NAME-" + System.nanoTime(), " " + codeCandidate);
        assertEquals(400, codeWhitespace.statusCode(), codeWhitespace.asString());
        assertTrue(codeWhitespace.asString().contains("首尾")
                        || codeWhitespace.asString().toLowerCase().contains("whitespace")
                        || codeWhitespace.asString().toLowerCase().contains("space"),
                "code whitespace error should be explicit: " + codeWhitespace.asString());
        assertEquals(0, searchTotal(codeCandidate));

        String nameCandidate = PREFIX + "AC5-NAME-" + System.nanoTime();
        Response nameWhitespace = createResponse(
                " " + nameCandidate + " ", PREFIX + "AC5-NAME-CODE-" + System.nanoTime());
        assertEquals(400, nameWhitespace.statusCode(), nameWhitespace.asString());
        assertTrue(nameWhitespace.asString().contains("首尾")
                        || nameWhitespace.asString().toLowerCase().contains("whitespace")
                        || nameWhitespace.asString().toLowerCase().contains("space"),
                "name whitespace error should be explicit: " + nameWhitespace.asString());
        assertEquals(0, searchTotal(nameCandidate));
    }

    @Test
    @DisplayName("AC-6: renaming a customer to another customer's name returns 409 and keeps original")
    void updateToExistingName_returns409AndPreservesOriginal() {
        String firstName = PREFIX + "AC6-FIRST-" + System.nanoTime();
        String secondName = PREFIX + "AC6-SECOND-" + System.nanoTime();
        String firstId = createCustomer(firstName, PREFIX + "AC6-FIRST-CODE-" + System.nanoTime());
        createCustomer(secondName, PREFIX + "AC6-SECOND-CODE-" + System.nanoTime());

        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + secondName + "\"}")
                .when().put("/api/cpq/customers/" + firstId)
                .then().extract().response();
        assertEquals(409, response.statusCode(), response.asString());

        RestAssured.given().when().get("/api/cpq/customers/" + firstId)
                .then().statusCode(200)
                .body("data.name", equalTo(firstName));
    }

    @Test
    @DisplayName("AC-15: concurrent normalized-name creates allow at most one new row")
    void concurrentSameName_atMostOneSucceeds() throws Exception {
        String sharedName = PREFIX + "AC15-CONCURRENT-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Response>> requests = List.of(
                    () -> createResponse(sharedName, PREFIX + "AC15-A-" + System.nanoTime()),
                    () -> createResponse(sharedName.toLowerCase(), PREFIX + "AC15-B-" + System.nanoTime())
            );
            List<Future<Response>> futures = pool.invokeAll(requests);
            List<Integer> statuses = new ArrayList<>();
            for (Future<Response> future : futures) {
                statuses.add(future.get().statusCode());
            }
            long successes = statuses.stream().filter(s -> s == 200).count();
            long conflicts = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, successes, "exactly one concurrent create should succeed: " + statuses);
            assertEquals(1, conflicts, "the losing concurrent create should be a conflict: " + statuses);
            assertEquals(1, searchTotal(sharedName), "concurrent writes must leave one new normalized name");
        } finally {
            pool.shutdownNow();
        }
    }

    private static String createCustomer(String name, String code) {
        return createResponse(name, code)
                .then().statusCode(200)
                .body("code", equalTo(200))
                .extract().jsonPath().getString("data.id");
    }

    private static Response createResponse(String name, String code) {
        String codeJson = code == null ? "" : ",\"code\":\"" + code + "\"";
        String body = "{\"name\":\"" + name + "\"" + codeJson
                + ",\"level\":\"STANDARD\",\"contacts\":["
                + "{\"name\":\"" + PREFIX + "CONTACT-" + System.nanoTime()
                + "\",\"phone\":\"" + PHONE + "\",\"isPrimary\":true}]}";
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/cpq/customers")
                .then().extract().response();
    }

    private static int searchTotal(String keyword) {
        return RestAssured.given().queryParam("keyword", keyword)
                .when().get("/api/cpq/customers")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.totalElements");
    }
}
