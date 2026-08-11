package com.cpq.formula;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Map;

import static org.hamcrest.Matchers.*;

/**
 * Phase A1 — 公式求值 REST 端点测试
 *
 * <p>task-0723 B5: 原 evaluate_bnfPath_withPartNoContext_resolvesAgainstDb 测试依赖
 * BasicDataImportServiceV5 导入 mat_part 数据验证 BNF 路径求值，V5 导入链路已整体退役，随之删除。
 * 其余 3 个测试是纯算术/语法错误场景，与 V5 无关，保留。
 */
@QuarkusTest
@TestProfile(FormulaEvaluateResourceTest.RbacOffProfile.class)
class FormulaEvaluateResourceTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject EntityManager em;

    private static final UUID FE_CUSTOMER = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final UUID FE_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    @Transactional
    void setupFixtures() throws Exception {
        // 测试用户
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'fe-eval-tester', 'FE Eval', 'fe@test.com', 'hash', 'SALES_MANAGER', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", FE_USER).executeUpdate();
        // 测试客户
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'FE Test Customer', 'FE-TEST-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", FE_CUSTOMER).executeUpdate();
    }

    @Test
    void evaluate_simpleArithmetic_noPath_returnsResult() {
        // 不含 BNF 路径,纯算术
        String body = """
            {"expression": "1 + 2 * 3"}
            """;
        RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(true))
                .body("data.result", anyOf(equalTo(7), equalTo("7"), equalTo(7.0F), hasToString(containsString("7"))));
    }

    @Test
    void evaluate_decimalStringBindingsUseBigDecimalArithmetic() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"expression":"a + b","bindings":{"a":"0.1","b":"0.2"}}
                """)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(true))
                .body("data.result", equalTo("0.3"));
    }

    @Test
    void evaluate_smallAndLargeDecimalStringBindingsRemainExact() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "expression":"large + small",
                  "bindings":{
                    "large":"98765431.123456789012",
                    "small":"0.000000000001"
                  }
                }
                """)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(true))
                .body("data.result", equalTo("98765431.123456789013"));
    }

    @Test
    void batchEvaluateReusesDecimalNormalizationAndReportsScientificNotation() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "tasks":[
                    {"expression":"a + b","bindings":{"a":"0.1","b":"0.2"}},
                    {"expression":"label","bindings":{"label":"STANDARD"}},
                    {"expression":"label","bindings":{"label":"1e-3"}}
                  ]
                }
                """)
            .post("/api/cpq/formulas/batch-evaluate")
            .then()
                .statusCode(200)
                .body("data.results[0].status", equalTo("OK"))
                .body("data.results[0].data.result", equalTo("0.3"))
                .body("data.results[1].status", equalTo("OK"))
                .body("data.results[1].data.result", equalTo("STANDARD"))
                .body("data.results[2].status", equalTo("ERROR"))
                .body("data.results[2].error", allOf(
                        containsString("bindings.label"), containsString("1e-3")));
    }

    @Test
    void evaluate_rejectsScientificDecimalStringWithPathAndOriginalValue() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"expression":"rate + 1","bindings":{"rate":"1e-3"}}
                """)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(400)
                .body("message", allOf(
                        containsString("bindings.rate"), containsString("1e-3")));
    }

    @Test
    void evaluate_emptyExpression_returnsParseError() {
        String body = """
            {"expression": ""}
            """;
        RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(false))
                .body("data.errorType", equalTo("PARSE_ERROR"));
    }

    @Test
    void evaluate_invalidBnfSyntax_returnsParseError() {
        String body = """
            {"expression": "{mat_part.[invalid syntax"}
            """;
        RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .post("/api/cpq/formulas/evaluate")
            .then()
                .statusCode(200)
                .body("data.success", equalTo(false));
    }
}
