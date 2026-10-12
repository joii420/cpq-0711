package com.cpq.engine.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.http.ContentType.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only API contract tests for AC-2/AC-3/AC-12/AC-13/AC-14.
 *
 * The suite never creates, edits, disables or deletes shared units.  The
 * write checks deliberately use an unauthenticated request, so they cannot
 * alter global state while still proving the management API is protected.
 */
@QuarkusTest
class UnitManagementApiContractTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final List<String> QUANTITY = List.of("EA", "PCS", "KPCS");
    private static final List<String> WEIGHT = List.of("g", "kg", "t", "盎司");

    @Test
    void unauthenticatedManagementWrites_areRejected() {
        assertForbidden(RestAssured.given().contentType(JSON)
                .body("{\"code\":\"UM-S-NEW\",\"category\":\"QUANTITY\"}")
                .post("/api/cpq/units"));
        assertForbidden(RestAssured.given().contentType(JSON)
                .body("{\"code\":\"PCS\",\"sortOrder\":1,\"version\":1}")
                .patch("/api/cpq/units/1"));
        assertForbidden(RestAssured.given().contentType(JSON)
                .body("{\"version\":1}")
                .post("/api/cpq/units/1/disable"));
        assertForbidden(RestAssured.given().contentType(JSON)
                .body("{\"sourceVersion\":1,\"targets\":[]}")
                .put("/api/cpq/units/1/conversions"));
    }

    @Test
    void presetUnitsAndDirectedRelationships_arePresent() throws Exception {
        Response unitsResponse = RestAssured.given()
                .cookie("CPQ_SESSION", adminSession())
                .queryParam("enabled", true)
                .when().get("/api/cpq/units");
        assertEquals(200, unitsResponse.statusCode(), unitsResponse.asString());

        JsonNode root = JSON_MAPPER.readTree(unitsResponse.asString());
        Map<String, JsonNode> units = findUnitObjects(root);
        for (String code : QUANTITY) {
            assertTrue(units.containsKey(code.toUpperCase()), "缺少预置数量单位 " + code);
        }
        for (String code : WEIGHT) {
            assertTrue(units.containsKey(code.toUpperCase()), "缺少预置重量单位 " + code);
        }
        assertFalse(units.containsKey("克"), "汉字别名不应作为单位定义");
        assertFalse(units.containsKey("KG/KPCS"), "复合单位不应作为单位定义");

        int relationshipCount = 0;
        for (JsonNode source : units.values()) {
            String sourceCode = source.path("code").asText();
            String category = source.path("category").asText();
            Response conversions = RestAssured.given()
                    .cookie("CPQ_SESSION", adminSession())
                    .when().get("/api/cpq/units/{id}/conversions", source.path("id").asText());
            assertEquals(200, conversions.statusCode(), sourceCode + ": " + conversions.asString());
            JsonNode targetsNode = findField(JSON_MAPPER.readTree(conversions.asString()), "targets");
            assertTrue(targetsNode != null && targetsNode.isArray(), sourceCode + " 缺少 targets");
            int expectedTargets = "QUANTITY".equalsIgnoreCase(category) ? 2 : 3;
            assertEquals(expectedTargets, targetsNode.size(), sourceCode + " 的有向目标数");
            Set<String> targetCodes = new HashSet<>();
            for (JsonNode target : targetsNode) {
                String targetCode = target.path("targetUnitCode").asText().toUpperCase();
                assertNotEquals(sourceCode.toUpperCase(), targetCode, "不得存在自关系");
                assertTrue(target.path("factor").isNumber() || target.path("factor").isTextual(),
                        sourceCode + " → " + targetCode + " 缺少系数");
                assertTrue(targetCodes.add(targetCode), "同一来源目标重复: " + sourceCode + " → " + targetCode);
                relationshipCount++;
            }
        }
        assertEquals(18, relationshipCount, "七个预置单位应有 18 条同类型有向关系");
    }

    @Test
    void customerPartsEndpoint_isNotBrokenBySettlementUnitContract() {
        Response response = RestAssured.given()
                .cookie("CPQ_SESSION", adminSession())
                .queryParam("customerNo", "UM-S-API")
                .when().get("/api/cpq/dataset/quote/customer-parts");
        assertNotEquals(500, response.statusCode(), response.asString());
        assertNotEquals(404, response.statusCode(), response.asString());
    }

    private static void assertForbidden(Response response) {
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                "管理写接口未被保护: HTTP " + response.statusCode() + " " + response.asString());
    }

    private static String adminSession() {
        return RestAssured.given().contentType(JSON)
                .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                .when().post("/api/cpq/auth/login")
                .then().statusCode(200)
                .extract().cookie("CPQ_SESSION");
    }

    private static Map<String, JsonNode> findUnitObjects(JsonNode root) {
        Map<String, JsonNode> result = new HashMap<>();
        collectUnitObjects(root, result);
        return result;
    }

    private static void collectUnitObjects(JsonNode node, Map<String, JsonNode> result) {
        if (node == null) return;
        if (node.isObject() && node.has("code") && node.has("category") && node.has("id")) {
            String code = node.path("code").asText();
            if (!code.isBlank()) result.put(code.toUpperCase(), node);
        }
        if (node.isObject() || node.isArray()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) collectUnitObjects(children.next(), result);
        }
    }

    private static JsonNode findField(JsonNode root, String name) {
        if (root == null) return null;
        if (root.isObject() && root.has(name)) return root.get(name);
        if (root.isObject() || root.isArray()) {
            Iterator<JsonNode> children = root.elements();
            while (children.hasNext()) {
                JsonNode found = findField(children.next(), name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
