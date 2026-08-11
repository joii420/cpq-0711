package com.cpq.formula;

import com.cpq.common.PrecisionHttpContractSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(FormulaPrecisionHttpContractTest.RbacOffProfile.class)
class FormulaPrecisionHttpContractTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Test
    void p2_01_evaluateReturnsDecimalStringAndAcceptsStringBindings() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "expression":"a + b",
                          "bindings":{"a":"0.1","b":"0.2"}
                        }
                        """)
                .post("/api/cpq/formulas/evaluate");

        assertEquals(200, response.statusCode(), response.asString());
        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        assertTrue(json.at("/data/success").asBoolean(), response.asString());
        PrecisionHttpContractSupport.assertTextualOrNull(json, "/data/result");
        assertEquals("0.3", json.at("/data/result").textValue());
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, Set.of("code"));
    }

    @Test
    void p2_01_evaluateRejectsNumericBindingWithPathAndOriginalValue() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "expression":"amount + 1",
                          "bindings":{"amount":98765431.123456789012}
                        }
                        """)
                .post("/api/cpq/formulas/evaluate");

        PrecisionHttpContractSupport.assertBadRequest(
                response, "bindings.amount", PrecisionHttpContractSupport.DECIMAL_12);
    }

    @Test
    void p2_01_evaluateRejectsNumericDriverRowWithPathAndOriginalValue() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "expression":"1 + 1",
                          "driverRow":{"rate":0.000000000001}
                        }
                        """)
                .post("/api/cpq/formulas/evaluate");

        PrecisionHttpContractSupport.assertBadRequest(
                response, "driverRow.rate", PrecisionHttpContractSupport.SMALL_DECIMAL_12);
    }

    @Test
    void p2_01_evaluateRejectsScientificDecimalStringWithPathAndOriginalValue() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "expression":"amount + 1",
                          "bindings":{"amount":"1e-3"}
                        }
                        """)
                .post("/api/cpq/formulas/evaluate");

        PrecisionHttpContractSupport.assertBadRequest(response, "bindings.amount", "1e-3");
    }

    @Test
    void p2_02_batchKeepsHttpSuccessAndIsolatesNumericItemErrorInOrder() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "tasks":[
                            {"expression":"0.1 + 0.2"},
                            {"expression":"amount + 1","bindings":{"amount":98765431.123456789012}},
                            {"expression":"1 / 3"}
                          ]
                        }
                        """)
                .post("/api/cpq/formulas/batch-evaluate");

        assertEquals(200, response.statusCode(), response.asString());
        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        JsonNode results = json.at("/data/results");
        assertEquals(3, results.size(), response.asString());
        assertEquals("OK", results.get(0).path("status").asText());
        assertEquals("ERROR", results.get(1).path("status").asText());
        assertEquals("OK", results.get(2).path("status").asText());
        PrecisionHttpContractSupport.assertTextualOrNull(
                json, "/data/results/0/data/result", "/data/results/2/data/result");
        assertEquals("0.3", results.get(0).at("/data/result").asText());
        assertEquals("0.333333333333", results.get(2).at("/data/result").asText());
        assertTrue(results.get(1).path("error").asText().contains("bindings.amount"), response.asString());
        assertTrue(results.get(1).path("error").asText().contains(PrecisionHttpContractSupport.DECIMAL_12),
                response.asString());
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, Set.of("code"));
    }

    @Test
    void p2_02_batchIsolatesScientificStringErrorWithoutChangingNeighborOrder() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "tasks":[
                            {"expression":"1 + 1"},
                            {"expression":"amount + 1","bindings":{"amount":"1e-3"}},
                            {"expression":"2 + 2"}
                          ]
                        }
                        """)
                .post("/api/cpq/formulas/batch-evaluate");

        assertEquals(200, response.statusCode(), response.asString());
        JsonNode results = PrecisionHttpContractSupport.readJson(response).at("/data/results");
        assertEquals(3, results.size(), response.asString());
        assertEquals("OK", results.get(0).path("status").asText());
        assertEquals("ERROR", results.get(1).path("status").asText());
        assertEquals("OK", results.get(2).path("status").asText());
        assertTrue(results.get(1).path("error").asText().contains("bindings.amount"), response.asString());
        assertTrue(results.get(1).path("error").asText().contains("1e-3"), response.asString());
    }
}
