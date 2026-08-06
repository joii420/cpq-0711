package com.cpq.component.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-0805 · 测试用例.md §5.3 —— commit {@code ignoreUnboundFormulas}（AC-3）。
 *
 * <p>覆盖 I-CMT-01~05：默认 400 + 整包回滚零残留；{@code ignoreUnboundFormulas=true} 放行 +
 * 记录 {@code unboundWarnings} + 落库组件 {@code hasUnboundFormula=true}；全部可解析时不误报。
 */
@QuarkusTest
@TestProfile(Task0805CommitIgnoreUnboundTest.RbacOffProfile.class)
class Task0805CommitIgnoreUnboundTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private UUID dirId;

    @BeforeEach
    void setupDirectory() throws Exception {
        dirId = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirId)
                .setParameter("name", "T0805-CMT-" + dirId.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                "(SELECT id FROM component WHERE directory_id = :dir)")
                .setParameter("dir", dirId).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId).executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", dirId).executeUpdate();
        utx.commit();
    }

    private String uniq(String tag) {
        return "T0805-CMT-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String bundleJson(String code, String fieldsJson, String formulasJson) {
        return "{\"bundleVersion\":\"1.0\",\"components\":[{\"code\":\"" + code + "\",\"name\":\"" + code
                + "名\",\"componentType\":\"NORMAL\",\"fields\":" + fieldsJson + ",\"formulas\":" + formulasJson
                + "}]}";
    }

    private long componentCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId).getSingleResult()).longValue();
    }

    // ── I-CMT-01：默认 false → 400，整包回滚零残留 ─────────────────────────────

    @Test
    @DisplayName("I-CMT-01: 默认 ignoreUnboundFormulas=false —— 400 且文案不变，DB 零残留")
    void default_blocksAndRollsBack() {
        String code = uniq("01");
        String body = bundleJson(code, "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]");

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import/commit?conflictPolicy=RENAME", dirId)
            .then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString(
                    "以下公式字段未绑定公式，请在字段配置中显式选择：公式测试（BL-0098：系统不再按位置自动匹配公式）"));

        assertEquals(0, componentCount(), "默认行为必须整包回滚，DB 零残留");
    }

    // ── I-CMT-02：ignoreUnboundFormulas=true → 放行 + unboundWarnings ──────────

    @Test
    @DisplayName("I-CMT-02: ignoreUnboundFormulas=true —— 放行，落库，unboundWarnings 点名")
    void ignoreTrue_allowsAndRecordsWarning() {
        String code = uniq("02");
        String body = bundleJson(code, "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]");

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import/commit"
                + "?conflictPolicy=RENAME&ignoreUnboundFormulas=true", dirId)
            .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.unboundCount", equalTo(1))
                .body("data.unboundWarnings[0].componentCode", equalTo(code))
                .body("data.unboundWarnings[0].fieldName", equalTo("公式测试"))
                .extract().asString();

        assertEquals(1, componentCount(), "放行后组件应落库");
    }

    // ── I-CMT-03：ignoreUnboundFormulas=true 但全部可解析 → 不误报 ─────────────

    @Test
    @DisplayName("I-CMT-03: ignoreUnboundFormulas=true 且全部可解析 —— unboundCount=0，不误报")
    void ignoreTrue_allResolvable_noFalsePositive() {
        String code = uniq("03");
        String body = bundleJson(code,
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式A\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]}]");

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import/commit"
                + "?conflictPolicy=RENAME&ignoreUnboundFormulas=true", dirId)
            .then().statusCode(200)
                .body("data.unboundCount", equalTo(0))
                .body("data.unboundWarnings.size()", equalTo(0));
    }

    // ── I-CMT-04：hasUnboundFormula=true ────────────────────────────────────────

    @Test
    @DisplayName("I-CMT-04: 放行后 GET 组件 —— hasUnboundFormula=true")
    void afterIgnore_hasUnboundFormulaTrue() throws Exception {
        String code = uniq("04");
        String body = bundleJson(code, "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]");

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import/commit"
                + "?conflictPolicy=RENAME&ignoreUnboundFormulas=true", dirId)
            .then().statusCode(200).extract().asString();

        String componentId = M.readTree(resp).path("data").path("created").get(0).path("componentId").asText();

        given().when().get("/api/cpq/components/{id}", componentId)
            .then().statusCode(200)
                .body("data.hasUnboundFormula", equalTo(true));
    }

    // ── I-CMT-05：全绑定组件 —— hasUnboundFormula=false（防误判）───────────────

    @Test
    @DisplayName("I-CMT-05: 全部字段已绑定 —— GET 组件 hasUnboundFormula=false")
    void allBound_hasUnboundFormulaFalse() throws Exception {
        String code = uniq("05");
        String body = bundleJson(code,
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式A\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]}]");

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import/commit"
                + "?conflictPolicy=RENAME&ignoreUnboundFormulas=true", dirId)
            .then().statusCode(200).extract().asString();

        String componentId = M.readTree(resp).path("data").path("created").get(0).path("componentId").asText();

        given().when().get("/api/cpq/components/{id}", componentId)
            .then().statusCode(200)
                .body("data.hasUnboundFormula", equalTo(false));
    }
}
