package com.cpq.component.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportPreviewResult;
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
 * task-0805 · 测试用例.md §5.2/§5.9 —— preview 绑定校验（AC-2）。
 *
 * <p>覆盖 I-PRV-01~08、U-PRV-07（preview 只读，本类以 @QuarkusTest 直调服务层实现，
 * 理由见 U-PRV-07 用例注释）、I-RO-DB-02（preview 的 DB 级只读断言）。
 */
@QuarkusTest
@TestProfile(Task0805PreviewBindingTest.RbacOffProfile.class)
class Task0805PreviewBindingTest {

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

    @Inject
    ComponentImportService importService;

    private UUID dirId;

    @BeforeEach
    void setupDirectory() throws Exception {
        dirId = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirId)
                .setParameter("name", "T0805-PRV-" + dirId.toString().substring(0, 8))
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
        return "T0805-PRV-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String bundleJson(String... itemsJson) {
        StringBuilder sb = new StringBuilder("{\"bundleVersion\":\"1.0\",\"components\":[");
        sb.append(String.join(",", itemsJson));
        sb.append("]}");
        return sb.toString();
    }

    private String item(String code, String fieldsJson, String formulasJson) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + "名\",\"componentType\":\"NORMAL\","
                + "\"fields\":" + fieldsJson + ",\"formulas\":" + formulasJson + "}";
    }

    // ── I-PRV-01：BOUND ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("I-PRV-01: 显式 formula_id 指向存在的公式 → BOUND, canCommit=true")
    void bound_field_reportsBoundAndCanCommit() {
        String code = uniq("01");
        String body = bundleJson(item(code,
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_id\":\"id-A\"}]",
                "[{\"id\":\"id-A\",\"name\":\"公式A\",\"expression\":[]}]"));

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200)
                .body("data.components[0].formulaBinding[0].status", equalTo("BOUND"))
                .body("data.canCommit", equalTo(true));
    }

    // ── I-PRV-02：CREATE 组件的 UNRESOLVABLE → blockers 点名 ────────────────────

    @Test
    @DisplayName("I-PRV-02: CREATE 组件 UNRESOLVABLE → blockers 点名组件+字段, canCommit=false")
    void createComponent_unresolvable_blocksWithNaming() {
        String code = uniq("02");
        String body = bundleJson(item(code,
                "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]"));

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200)
                .body("data.canCommit", equalTo(false))
                .extract().asString();

        JsonNode blockers = readData(resp).path("blockers");
        boolean named = false;
        for (JsonNode b : blockers) {
            String s = b.asText();
            if (s.contains(code) && s.contains("公式测试")) named = true;
        }
        assertTrue(named, "blockers 必须有一条同时点名组件 code 与字段名: " + blockers);
    }

    // ── I-PRV-03：SKIP 组件的 UNRESOLVABLE 不进 blockers ────────────────────────

    @Test
    @DisplayName("I-PRV-03: 冲突+SKIP 策略下 UNRESOLVABLE 组件 —— formulaBinding 仍展示但不进 blockers")
    void skipComponent_unresolvable_notInBlockers() throws Exception {
        String code = uniq("03");
        // 先在另一个目录插入同 code 组件制造冲突
        UUID dummyDir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dummyDir).setParameter("name", "T0805-PRV03-dummy").executeUpdate();
        em.createNativeQuery("INSERT INTO component(id, directory_id, name, code, column_count, fields, " +
                "formulas, excel_columns, component_type, status, created_at, updated_at) VALUES " +
                "(:id, :dir, 'existing', :code, 0, '[]', '[]', '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", UUID.randomUUID()).setParameter("dir", dummyDir).setParameter("code", code)
                .executeUpdate();
        utx.commit();

        try {
            String body = bundleJson(item(code,
                    "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]"));

            String resp = given().contentType(ContentType.JSON).body(body)
                .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=SKIP", dirId)
                .then().statusCode(200)
                    .body("data.components[0].action", equalTo("SKIP"))
                    .body("data.components[0].formulaBinding[0].status", equalTo("UNRESOLVABLE"))
                    .extract().asString();

            JsonNode data = readData(resp);
            for (JsonNode b : data.path("blockers")) {
                assertFalse(b.asText().contains(code), "SKIP 组件不该出现在 blockers 里: " + b.asText());
            }
        } finally {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dummyDir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dummyDir).executeUpdate();
            utx.commit();
        }
    }

    // ── I-PRV-04：RESOLVED_BY_POSITION → warnings ───────────────────────────────

    @Test
    @DisplayName("I-PRV-04: 按位置推导的绑定 → warnings 提示，不单独阻断")
    void resolvedByPosition_addsWarning() {
        String code = uniq("04");
        String body = bundleJson(item(code,
                "[{\"name\":\"列0\",\"field_type\":\"FORMULA\"},{\"name\":\"列1\",\"field_type\":\"FORMULA\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]},{\"name\":\"公式B\",\"expression\":[]}]"));

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200).extract().asString();

        JsonNode data = readData(resp);
        boolean warned = false;
        for (JsonNode w : data.path("warnings")) {
            if (w.asText().contains("位置")) warned = true;
        }
        assertTrue(warned, "warnings 应包含按位置推导的提示: " + data.path("warnings"));
        assertEquals("RESOLVED_BY_POSITION", data.path("components").get(0).path("formulaBinding").get(0).path("status").asText());
    }

    // ── I-PRV-05：条件公式内部引用逐条出报告 ──────────────────────────────────

    @Test
    @DisplayName("I-PRV-05: 条件公式字段 → formulaBinding 含「› 规则1」「› 默认」两条")
    void conditionalFormula_producesTwoItems() {
        String code = uniq("05");
        String body = bundleJson(item(code,
                "[{\"name\":\"材料成本\",\"field_type\":\"FORMULA\",\"conditional_formula\":{"
                    + "\"rules\":[{\"when\":{},\"formula\":\"非银点类材料成本公式\"}],"
                    + "\"default\":\"银点材料成本公式\"}}]",
                "[{\"name\":\"非银点类材料成本公式\",\"expression\":[]},{\"name\":\"银点材料成本公式\",\"expression\":[]}]"));

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200)
                .body("data.components[0].formulaBinding.size()", equalTo(2))
                .body("data.components[0].formulaBinding[0].fieldName", equalTo("材料成本 › 规则1"))
                .body("data.components[0].formulaBinding[0].status", equalTo("RESOLVED_BY_NAME"))
                .body("data.components[0].formulaBinding[1].fieldName", equalTo("材料成本 › 默认"))
                .body("data.components[0].formulaBinding[1].status", equalTo("RESOLVED_BY_NAME"));
    }

    // ── I-PRV-06：bindingSummary 聚合数值核对 ────────────────────────────────────

    @Test
    @DisplayName("I-PRV-06: bindingSummary 聚合 4 组件(各一种 status) 数值逐项核对")
    void bindingSummary_aggregatesFourStatuses() {
        String cBound = uniq("06BOUND");
        String cName = uniq("06NAME");
        String cPos = uniq("06POS");
        String cUnres = uniq("06UNRES");

        String body = bundleJson(
            item(cBound, "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_id\":\"id-A\"}]",
                "[{\"id\":\"id-A\",\"name\":\"公式A\",\"expression\":[]}]"),
            item(cName, "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式A\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]}]"),
            item(cPos, "[{\"name\":\"列0\",\"field_type\":\"FORMULA\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]}]"),
            item(cUnres, "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]")
        );

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200)
                .body("data.bindingSummary.totalFormulaRefs", equalTo(4))
                .body("data.bindingSummary.bound", equalTo(1))
                .body("data.bindingSummary.resolvedByName", equalTo(1))
                .body("data.bindingSummary.resolvedByPosition", equalTo(1))
                .body("data.bindingSummary.unresolvable", equalTo(1))
                .body("data.canCommit", equalTo(false)); // 含 1 个 UNRESOLVABLE 的 CREATE 组件
    }

    // ── I-PRV-08（2026-08-05 追加）：conditional_formula 只有 default 无 rules —— 不假阻断 ──

    @Test
    @DisplayName("I-PRV-08: conditional_formula 只有 default 无 rules —— 不进 blockers，canCommit 不受影响")
    void conditionalFormula_defaultOnlyNoRules_notFalselyBlocked() {
        String code = uniq("08");
        String body = bundleJson(item(code,
                "[{\"name\":\"综合费率\",\"field_type\":\"FORMULA\",\"conditional_formula\":{\"default\":\"标准费率\"}}]",
                "[{\"name\":\"标准费率\",\"expression\":[]}]"));

        String resp = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200)
                .body("data.components[0].formulaBinding.size()", equalTo(1))
                .body("data.components[0].formulaBinding[0].fieldName", equalTo("综合费率 › 默认"))
                .body("data.components[0].formulaBinding[0].status", equalTo("RESOLVED_BY_NAME"))
                .extract().asString();

        JsonNode data = readData(resp);
        for (JsonNode b : data.path("blockers")) {
            assertFalse(b.asText().contains(code), "假阻断回归：该组件不应出现在 blockers 里: " + b.asText());
        }
        assertTrue(data.path("canCommit").asBoolean(), "不应因 rules 缺失被判 canCommit=false");
    }

    // ── U-PRV-07：preview 只读（内存 bundle 对象不变）──────────────────────────
    //
    // 说明：测试用例.md 原设计标为「U」（纯 JUnit），但 ComponentImportService.preview 依赖
    // EntityManager（查目标目录是否存在、查依赖/code 冲突），无法脱离 CDI/DB 单独实例化。
    // 本类以 @QuarkusTest 直接注入服务调用（不经 HTTP），仍然是「preview 侧独立于 U-B2-01
    // 验证只读铁律」的原意，只是运行时层级从纯单测调整为服务层集成测试，断言语义不变。

    @Test
    @DisplayName("U-PRV-07: preview() 前后 bundle.components[].fields/formulas 内容不变")
    void preview_doesNotMutateInputBundle() throws Exception {
        String rawFields = "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式B\"}]";
        String rawFormulas = "[{\"name\":\"公式A\",\"expression\":[]},{\"name\":\"公式B\",\"expression\":[]}]";

        ComponentExportBundle bundle = new ComponentExportBundle();
        ComponentExportBundle.Item it = new ComponentExportBundle.Item();
        it.code = uniq("U07");
        it.name = "只读测试组件";
        it.componentType = "NORMAL";
        it.fields = M.readTree(rawFields);
        it.formulas = M.readTree(rawFormulas);
        it.excelColumns = M.createArrayNode();
        bundle.components = java.util.List.of(it);

        String fieldsBefore = it.fields.toString();
        String formulasBefore = it.formulas.toString();

        importService.preview(dirId, bundle, "RENAME");

        assertEquals(fieldsBefore, it.fields.toString(), "preview 不得原地修改 bundle 的 fields");
        assertEquals(formulasBefore, it.formulas.toString(), "preview 不得原地修改 bundle 的 formulas");
    }

    // ── I-RO-DB-02：preview 的 DB 级只读断言 ────────────────────────────────────

    @Test
    @DisplayName("I-RO-DB-02: preview 前后 DB 里已有组件的 fields/formulas/updated_at 逐行不变")
    void preview_doesNotWriteToDatabase() throws Exception {
        // 目标目录里先放一个组件（preview 的 code 冲突查询会 SELECT 到它，但不该改它）
        utx.begin();
        em.joinTransaction();
        UUID existingId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO component(id, directory_id, name, code, column_count, fields, " +
                "formulas, excel_columns, component_type, status, created_at, updated_at) VALUES " +
                "(:id, :dir, 'existing', :code, 0, CAST(:f AS jsonb), CAST(:fm AS jsonb), '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", existingId).setParameter("dir", dirId)
                .setParameter("code", uniq("RODB2"))
                .setParameter("f", "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_id\":\"id-A\"}]")
                .setParameter("fm", "[{\"id\":\"id-A\",\"name\":\"公式A\",\"expression\":[]}]")
                .executeUpdate();
        utx.commit();

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> before = em.createNativeQuery(
                "SELECT code, md5(fields::text), md5(formulas::text), updated_at " +
                "FROM component WHERE directory_id = :dir ORDER BY code")
                .setParameter("dir", dirId).getResultList();

        String body = bundleJson(item(uniq("RODB2-other"),
                "[{\"name\":\"乙\",\"field_type\":\"FORMULA\"}]", "[]"));
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", dirId)
            .then().statusCode(200);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> after = em.createNativeQuery(
                "SELECT code, md5(fields::text), md5(formulas::text), updated_at " +
                "FROM component WHERE directory_id = :dir ORDER BY code")
                .setParameter("dir", dirId).getResultList();

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[1], after.get(i)[1], "fields md5 前后必须相等（preview 零写库）");
            assertEquals(before.get(i)[2], after.get(i)[2], "formulas md5 前后必须相等（preview 零写库）");
            assertEquals(before.get(i)[3], after.get(i)[3], "updated_at 前后必须相等");
        }
    }

    private JsonNode readData(String httpBody) {
        try {
            return M.readTree(httpBody).path("data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
