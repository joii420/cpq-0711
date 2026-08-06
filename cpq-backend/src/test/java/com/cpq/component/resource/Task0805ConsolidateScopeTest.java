package com.cpq.component.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
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
 * task-0805 · 测试用例.md §5.4 —— {@code /admin/formula-binding/consolidate} 收窄（AC-4）。
 *
 * <p>覆盖 I-CSD-01~07：不传参=全库；directoryId 限定；componentIds 限定；两者交集；
 * dryRun 零写库；dryRun=false 精确写库范围；响应形状不变。
 *
 * <p>{@code @RoleAllowed({"SYSTEM_ADMIN"})} 端点，本测试环境登录会话写 Redis 稳定
 * CONNECTION_CLOSED（既有基线问题，见 {@code Task0805ExportBindingReportTest} 类注释），
 * 用 {@code @TestProfile} 局部关闭 RBAC 验证业务逻辑本身。
 */
@QuarkusTest
@TestProfile(Task0805ConsolidateScopeTest.RbacOffProfile.class)
class Task0805ConsolidateScopeTest {

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

    private UUID dirX;
    private UUID dirOutside;
    private UUID compA;
    private UUID compB;
    private UUID compOutside;
    private String compAField;
    private String compOutsideField;

    @BeforeEach
    void setup() throws Exception {
        dirX = UUID.randomUUID();
        dirOutside = UUID.randomUUID();
        compA = UUID.randomUUID();
        compB = UUID.randomUUID();
        compOutside = UUID.randomUUID();
        compAField = "字段A-" + dirX.toString().substring(0, 8);
        compOutsideField = "字段Outside-" + dirOutside.toString().substring(0, 8);

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirX).setParameter("name", "T0805-CSD-X-" + dirX.toString().substring(0, 8))
                .executeUpdate();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirOutside).setParameter("name", "T0805-CSD-OUT-" + dirOutside.toString().substring(0, 8))
                .executeUpdate();

        // compA: dirX 内，未绑定但可按名解析（字段名==公式名）
        insertComponent(compA, dirX, "T0805-CSD-A-" + dirX.toString().substring(0, 8),
                "[{\"name\":\"" + compAField + "\",\"field_type\":\"FORMULA\"}]",
                "[{\"name\":\"" + compAField + "\",\"expression\":[]}]");
        // compB: dirX 内，已全部绑定
        insertComponent(compB, dirX, "T0805-CSD-B-" + dirX.toString().substring(0, 8),
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_id\":\"id-B\"}]",
                "[{\"id\":\"id-B\",\"name\":\"公式B\",\"expression\":[]}]");
        // compOutside: dirOutside 内，未绑定但可按名解析（哨兵——不应被 dirX/compA scope 误伤）
        insertComponent(compOutside, dirOutside, "T0805-CSD-OUT-" + dirOutside.toString().substring(0, 8),
                "[{\"name\":\"" + compOutsideField + "\",\"field_type\":\"FORMULA\"}]",
                "[{\"name\":\"" + compOutsideField + "\",\"expression\":[]}]");
        utx.commit();
    }

    private void insertComponent(UUID id, UUID dir, String code, String fieldsJson, String formulasJson) {
        em.createNativeQuery("INSERT INTO component(id, directory_id, name, code, column_count, fields, " +
                "formulas, excel_columns, component_type, status, created_at, updated_at) VALUES " +
                "(:id, :dir, :code, :code, 0, CAST(:f AS jsonb), CAST(:fm AS jsonb), '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", id).setParameter("dir", dir).setParameter("code", code)
                .setParameter("f", fieldsJson).setParameter("fm", formulasJson)
                .executeUpdate();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        for (UUID dir : Set.of(dirX, dirOutside)) {
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                    "(SELECT id FROM component WHERE directory_id = :dir)")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dir).executeUpdate();
        }
        utx.commit();
    }

    private boolean itemsContainField(JsonNode items, String fieldName) {
        for (JsonNode it : items) {
            if (fieldName.equals(it.path("fieldName").asText())) return true;
        }
        return false;
    }

    // ── I-CSD-01：不传参 = 全库，与 componentIds=[compA] 单独跑逐字段相等 ──────

    @Test
    @DisplayName("I-CSD-01: 不传参覆盖全库（含 T0805 夹具）；与仅传 componentIds=[compA] 时该条目逐字段相等")
    void noParams_coversFullDb_matchesScopedResultForSameComponent() {
        String allResp = given().contentType(ContentType.JSON)
            .when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true")
            .then().statusCode(200).extract().asString();
        JsonNode allItems = parse(allResp);

        assertTrue(itemsContainField(allItems.path("items"), compAField),
            "不传参应覆盖到 T0805 夹具（全库扫描）");

        JsonNode scopedResp = parse(given().contentType(ContentType.JSON)
            .when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&componentIds={id}", compA)
            .then().statusCode(200).extract().asString());

        JsonNode fullEntry = findByField(allItems.path("items"), compAField);
        JsonNode scopedEntry = findByField(scopedResp.path("items"), compAField);
        // 注意：resolvedFormulaId 不对拍——dryRun 每次调用都对无 id 的公式现算一个新 UUID
        // （ensureFormulaIds 在未持久化的深拷贝上跑，两次 dry-run 天然生成不同的随机 id，
        // 这不是 bug，是 dry-run「只读不落库」的必然结果）。可确定性对拍的是 name/status。
        assertEquals(fullEntry.path("resolvedFormulaName").asText(), scopedEntry.path("resolvedFormulaName").asText());
        assertEquals(fullEntry.path("status").asText(), scopedEntry.path("status").asText());
    }

    // ── I-CSD-02：directoryId 限定 ──────────────────────────────────────────────

    @Test
    @DisplayName("I-CSD-02: directoryId 限定 —— 只含 dirX 内组件，不含 compOutside")
    void directoryIdScope_excludesOutside() {
        JsonNode resp = parse(given().contentType(ContentType.JSON)
            .when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&directoryId={id}", dirX)
            .then().statusCode(200).extract().asString());

        assertTrue(itemsContainField(resp.path("items"), compAField));
        assertFalse(itemsContainField(resp.path("items"), compOutsideField),
            "directoryId 限定不该带出目录外的哨兵组件");
    }

    // ── I-CSD-03：componentIds 限定 ──────────────────────────────────────────────

    @Test
    @DisplayName("I-CSD-03: componentIds 限定 —— 只含指定组件")
    void componentIdsScope_onlyContainsSpecified() {
        JsonNode resp = parse(given().contentType(ContentType.JSON)
            .when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&componentIds={id}", compA)
            .then().statusCode(200).extract().asString());

        assertTrue(itemsContainField(resp.path("items"), compAField));
        assertFalse(itemsContainField(resp.path("items"), compOutsideField));
    }

    // ── I-CSD-04：directoryId + componentIds 同传 —— 取交集 ─────────────────────

    @Test
    @DisplayName("I-CSD-04: directoryId + componentIds 同传 —— 取交集（compOutside 因不在 dirX 被排除）")
    void bothParams_intersection() {
        JsonNode resp = parse(given().contentType(ContentType.JSON)
            .when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&directoryId={dir}&componentIds={a},{o}",
                dirX, compA, compOutside)
            .then().statusCode(200).extract().asString());

        assertTrue(itemsContainField(resp.path("items"), compAField));
        assertFalse(itemsContainField(resp.path("items"), compOutsideField),
            "compOutside 不在 dirX 内，应被交集排除，即使显式传了它的 id");
    }

    // ── I-CSD-05：dryRun=true 零写库（三种 scope）───────────────────────────────

    @Test
    @DisplayName("I-CSD-05: dryRun=true（不传参/directoryId/componentIds 三种 scope）均零写库")
    void dryRun_noneWritesDatabase() {
        String before = snapshotFields();

        given().contentType(ContentType.JSON).when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true")
            .then().statusCode(200);
        given().contentType(ContentType.JSON).when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&directoryId={id}", dirX)
            .then().statusCode(200);
        given().contentType(ContentType.JSON).when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true&componentIds={id}", compA)
            .then().statusCode(200);

        assertEquals(before, snapshotFields(), "三次 dryRun=true 调用后 T0805 夹具的 fields 内容必须逐字节不变");
    }

    private String snapshotFields() {
        Object f = em.createNativeQuery("SELECT fields::text FROM component WHERE id = :id")
                .setParameter("id", compA).getSingleResult();
        return String.valueOf(f);
    }

    // ── I-CSD-06：dryRun=false + directoryId scope —— 精确写库范围 ─────────────

    @Test
    @DisplayName("I-CSD-06: dryRun=false + directoryId 限定 —— 只写 dirX 内组件，compOutside 不受影响")
    void dryRunFalse_directoryScope_onlyWritesInScope() {
        given().contentType(ContentType.JSON).when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=false&directoryId={id}", dirX)
            .then().statusCode(200);

        String compAFields = String.valueOf(em.createNativeQuery(
                "SELECT fields::text FROM component WHERE id = :id").setParameter("id", compA).getSingleResult());
        assertTrue(compAFields.contains("formula_id"), "dirX 内的 compA 应被固化: " + compAFields);

        String compOutsideFields = String.valueOf(em.createNativeQuery(
                "SELECT fields::text FROM component WHERE id = :id").setParameter("id", compOutside).getSingleResult());
        assertFalse(compOutsideFields.contains("formula_id"),
            "目录外 compOutside 不应被越权固化: " + compOutsideFields);
    }

    // ── I-CSD-07：响应形状不变 ───────────────────────────────────────────────

    @Test
    @DisplayName("I-CSD-07: 响应顶层键恰好是 {dryRun, componentsUpdated, itemCount, items}")
    void responseShape_unchanged() {
        String resp = given().contentType(ContentType.JSON).when().post("/api/cpq/admin/formula-binding/consolidate?dryRun=true")
            .then().statusCode(200).extract().asString();
        JsonNode node = parse(resp);
        Set<String> keys = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("dryRun", "componentsUpdated", "itemCount", "items"), keys);
    }

    private JsonNode findByField(JsonNode items, String fieldName) {
        for (JsonNode it : items) {
            if (fieldName.equals(it.path("fieldName").asText())) return it;
        }
        throw new AssertionError("未找到 fieldName=" + fieldName + " 的条目");
    }

    private JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
