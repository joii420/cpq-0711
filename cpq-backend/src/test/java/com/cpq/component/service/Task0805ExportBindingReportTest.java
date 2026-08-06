package com.cpq.component.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.util.Map;

/**
 * task-0805 · 测试用例.md §5.1 —— 导出 {@code bindingReport}（AC-1）。
 *
 * <p>覆盖 I-EXP-01/02/03、G-CKSUM-01（checksum 不受 bindingReport 影响的反向门禁式正向锁定）、
 * I-CKSUM-OLD-01（18 份真实老 bundle 原始 checksum 在新代码下仍 checksumValid=true，走 preview()）、
 * I-RO-DB-01（导出的 DB 级只读断言——铁律1 真正要守的是不写库，不是内存对象没被原地改）。
 *
 * <p>目录/组件名一律 T0805 前缀，@AfterEach 按 directory_id 级联清理（见测试用例.md §10.1）。
 *
 * <p><b>RBAC</b>：{@code application-test.properties} 当前 {@code cpq.security.rbac.enabled=true}，
 * 而登录端点 {@code POST /api/cpq/auth/login} 在本测试环境写 Redis session 会稳定
 * {@code CONNECTION_CLOSED}（既有基线 {@code PermissionTest} 同样复现，与本任务无关，见
 * {@code QuotationCopyValueSnapshotInheritanceTest} T3 的同款记录）。本类用 {@code @TestProfile}
 * 局部关闭 RBAC（不改共享配置文件，不影响其它测试类），直连 REST 端点验证业务逻辑本身。
 */
@QuarkusTest
@TestProfile(Task0805ExportBindingReportTest.RbacOffProfile.class)
class Task0805ExportBindingReportTest {

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
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirId)
                .setParameter("name", "T0805-EXP-" + dirId.toString().substring(0, 8))
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

    private void insertComponent(String code, String name, String fieldsJson, String formulasJson) throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, " +
                "excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (:id, :dir, :name, :code, 0, CAST(:fields AS jsonb), CAST(:formulas AS jsonb), " +
                "'[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("dir", dirId)
                .setParameter("name", name)
                .setParameter("code", code)
                .setParameter("fields", fieldsJson)
                .setParameter("formulas", formulasJson)
                .executeUpdate();
        utx.commit();
    }

    // ── I-EXP-01：空目录导出 ────────────────────────────────────────────────────

    @Test
    @DisplayName("I-EXP-01: 空目录导出 —— bindingReport 全零，HTTP 200")
    void export_emptyDirectory_bindingReportAllZero() {
        given()
            .when().get("/api/cpq/component-directories/{id}/export", dirId)
            .then()
                .statusCode(200)
                .body("bindingReport.unboundCount", equalTo(0))
                .body("bindingReport.totalFormulaRefs", equalTo(0))
                .body("bindingReport.items.size()", equalTo(0));
    }

    // ── I-EXP-02：含 UNRESOLVABLE 字段的组件 —— 导出不阻断 ─────────────────────

    @Test
    @DisplayName("I-EXP-02: 含 UNRESOLVABLE 字段的组件 —— HTTP 200 不阻断，且被点名")
    void export_unresolvableField_notBlocked_isNamed() throws Exception {
        String code = "T0805-EXP02-" + dirId.toString().substring(0, 8);
        insertComponent(code, "T0805坏组件",
                "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]");

        given()
            .when().get("/api/cpq/component-directories/{id}/export", dirId)
            .then()
                .statusCode(200)
                .body("bindingReport.unboundCount", equalTo(1))
                .body("bindingReport.items[0].status", equalTo("UNRESOLVABLE"))
                .body("bindingReport.items[0].componentCode", equalTo(code))
                .body("bindingReport.items[0].fieldName", equalTo("公式测试"));
    }

    // ── I-EXP-03：健康组件 —— RESOLVED_BY_NAME ────────────────────────────────

    @Test
    @DisplayName("I-EXP-03: 健康组件（formula_name 显式绑定且可解析）—— unboundCount=0, RESOLVED_BY_NAME")
    void export_healthyComponent_resolvedByName() throws Exception {
        insertComponent("T0805-EXP03-" + dirId.toString().substring(0, 8), "T0805好组件",
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式B\"}]",
                "[{\"name\":\"公式A\",\"expression\":[]},{\"name\":\"公式B\",\"expression\":[]}]");

        given()
            .when().get("/api/cpq/component-directories/{id}/export", dirId)
            .then()
                .statusCode(200)
                .body("bindingReport.unboundCount", equalTo(0))
                .body("bindingReport.items[0].status", equalTo("RESOLVED_BY_NAME"))
                .body("bindingReport.items[0].resolvedFormulaName", equalTo("公式B"));
    }

    // ── G-CKSUM-01：checksum 不受 bindingReport 影响（正向锁定，等价反向门禁）──────

    @Test
    @DisplayName("G-CKSUM-01: 非空 bindingReport 的导出，checksum 仍只覆盖 source+components+dependencies")
    void export_checksumUnaffectedByBindingReport() throws Exception {
        insertComponent("T0805-CKSUM-" + dirId.toString().substring(0, 8), "T0805坏组件",
                "[{\"name\":\"公式测试\",\"field_type\":\"FORMULA\"}]", "[]");

        String body = given()
            .when().get("/api/cpq/component-directories/{id}/export", dirId)
            .then()
                .statusCode(200)
                .extract().asString();

        JsonNode bundle = M.readTree(body);
        assertTrue(bundle.path("bindingReport").path("unboundCount").asInt() > 0,
            "前置条件：本用例的 bundle 必须有非空 bindingReport，否则测不出问题");

        // 本地重算 sha256(source+components+dependencies)，规则与 ComponentExportService.computeChecksum
        // 同款：只装这三个字段，显式排除 bindingReport / checksum 自身。
        var payload = M.createObjectNode();
        payload.set("source", bundle.path("source"));
        payload.set("components", bundle.path("components"));
        payload.set("dependencies", bundle.path("dependencies"));
        byte[] bytes = M.writeValueAsBytes(payload);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : digest) sb.append(String.format("%02x", b));

        assertEquals(sb.toString(), bundle.path("checksum").asText(),
            "checksum 必须与本地重算值一致——若 bindingReport 被误塞进 computeChecksum 的 payload，此断言必须变红");
    }

    // ── I-CKSUM-OLD-01：18 份真实老 bundle 原始 checksum 在新代码 preview() 下仍 valid ──

    @ParameterizedTest(name = "I-CKSUM-OLD-01[{0}]")
    @ValueSource(strings = {
        "bundle-01.json", "bundle-02.json", "bundle-03.json", "bundle-04.json", "bundle-05.json",
        "bundle-06.json", "bundle-07.json", "bundle-08.json", "bundle-09.json", "bundle-10.json",
        "bundle-11.json", "bundle-12.json", "bundle-13.json", "bundle-14.json", "bundle-15.json",
        "bundle-16.json", "bundle-17.json", "bundle-18.json"
    })
    @DisplayName("I-CKSUM-OLD-01: 18 份真实老 bundle 原始 checksum 经 preview() 仍 checksumValid=true")
    void oldBundle_checksumStillValid(String fixtureName) throws Exception {
        UUID targetDir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) VALUES (:id, :name, 0, NOW())")
                .setParameter("id", targetDir)
                .setParameter("name", "T0805-CKSUMOLD-" + targetDir.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
        try {
            String raw = new String(Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("fixtures/bundles/" + fixtureName).readAllBytes());

            given()
                .contentType(ContentType.JSON)
                .body(raw)
                .when().post("/api/cpq/component-directories/{id}/import?conflictPolicy=RENAME", targetDir)
                .then()
                    .statusCode(200)
                    .body("data.checksumValid", equalTo(true));
        } finally {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", targetDir).executeUpdate();
            utx.commit();
        }
    }

    // ── I-RO-DB-01：导出的 DB 级只读断言（铁律1 真正要守的是不写库）───────────────

    @Test
    @DisplayName("I-RO-DB-01: 导出前后 DB 里 fields/formulas/updated_at 逐行不变（三种 status 都触碰到）")
    void export_doesNotWriteToDatabase() throws Exception {
        insertComponent("T0805-RODB-BOUND-" + dirId.toString().substring(0, 8), "T0805已绑定",
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_id\":\"id-A\"}]",
                "[{\"id\":\"id-A\",\"name\":\"公式A\",\"expression\":[]}]");
        insertComponent("T0805-RODB-NAME-" + dirId.toString().substring(0, 8), "T0805按名解析",
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\",\"formula_name\":\"公式B\"}]",
                "[{\"name\":\"公式B\",\"expression\":[]}]");
        insertComponent("T0805-RODB-UNRES-" + dirId.toString().substring(0, 8), "T0805未绑定",
                "[{\"name\":\"甲\",\"field_type\":\"FORMULA\"}]", "[]");

        List<Object[]> before = snapshot();

        given().when().get("/api/cpq/component-directories/{id}/export", dirId)
            .then().statusCode(200);

        List<Object[]> after = snapshot();

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], "code 顺序应一致（ORDER BY code）");
            assertEquals(before.get(i)[1], after.get(i)[1], "fields md5 前后必须相等（导出零写库）");
            assertEquals(before.get(i)[2], after.get(i)[2], "formulas md5 前后必须相等（导出零写库）");
            assertEquals(before.get(i)[3], after.get(i)[3], "updated_at 前后必须相等（未触发任何 flush）");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> snapshot() {
        return em.createNativeQuery(
                "SELECT code, md5(fields::text), md5(formulas::text), updated_at " +
                "FROM component WHERE directory_id = :dir ORDER BY code")
                .setParameter("dir", dirId)
                .getResultList();
    }
}
