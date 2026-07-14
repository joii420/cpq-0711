package com.cpq.modelconfig;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * ModelConfigResource 集成测试（task-0712 B5，`/api/cpq/model-configs`）。
 *
 * <p>覆盖 dev-docs/task-0712-选配模板和报价单选配功能/test.md B501~B510：
 * 首版上传 / 版本递增+旧版降级 / 仅历史版本 / 设为当前(部分唯一索引不冲突) / 历史版本查询 /
 * 运行端查当前(非陈旧) / 缺失占位(data=null，非 404/500) / 删除级联清文件 / SALES_PART 类型 /
 * 以及本实现新增的字段校验 + subjectLabel 批量关联。
 *
 * <p>subjectKey 用运行期随机后缀隔离，避免与共享 DB 上其他并发 worktree 测试撞键
 * （见 docs 历史教训 cpq-shared-flyway-history-churn / cpq-worktree-maven-test-tree）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ModelConfigResource — 3D 模型配置（task-0712 B5）")
class ModelConfigResourceTest {

    private static final String BASE = "/api/cpq/model-configs";
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String MAT_KEY = "TEST-MC-MAT-" + RUN_ID;
    private static final String SP_KEY = "TEST-MC-SP-" + RUN_ID;

    // 跨 @Order 测试传递的版本 id（同一 subject 的多版本生命周期）
    private static String v1Id;
    private static String v2Id;
    private static String v3Id;

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void ensureMaterialRecipeSeed() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO material_recipe(id, code, symbol, name, recipe_type, status) " +
                "VALUES (gen_random_uuid(), :code, 'SS304', :name, 'locked', 'ACTIVE') " +
                "ON CONFLICT (code) DO NOTHING")
                .setParameter("code", MAT_KEY)
                .setParameter("name", "测试材质-" + RUN_ID)
                .executeUpdate();
        utx.commit();
    }

    // ─── 上传首版（B501） ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("B501: 上传首版(setCurrent=true) → version=1, isCurrent=true, thumbnailUrl 缺省为 null")
    void uploadFirstVersion() throws Exception {
        File glb = tempFile("model-v1", ".glb", "GLB-BYTES-V1");
        String id = given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", MAT_KEY)
                .multiPart("label", "测试材质v1")
                .multiPart("glbFile", glb, "model/gltf-binary")
                .multiPart("setCurrent", "true")
            .when().post(BASE)
            .then().statusCode(200)
                .body("data.version", equalTo(1))
                .body("data.isCurrent", equalTo(true))
                .body("data.glbUrl", notNullValue())
                .body("data.thumbnailUrl", nullValue())
                .extract().path("data.id");
        v1Id = id;
        Assertions.assertNotNull(v1Id);
    }

    // ─── 版本递增 + 旧版降级（B502） ─────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("B502: 再次上传同 subject(setCurrent=true) → version=2, v1 自动降级")
    void uploadSecondVersionPromotes() throws Exception {
        File glb = tempFile("model-v2", ".glb", "GLB-BYTES-V2");
        File thumb = tempFile("thumb-v2", ".png", "PNG-BYTES-V2");
        String id = given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", MAT_KEY)
                .multiPart("label", "测试材质v2")
                .multiPart("glbFile", glb, "model/gltf-binary")
                .multiPart("thumbnailFile", thumb, "image/png")
                .multiPart("setCurrent", "true")
            .when().post(BASE)
            .then().statusCode(200)
                .body("data.version", equalTo(2))
                .body("data.isCurrent", equalTo(true))
                .body("data.thumbnailUrl", notNullValue())
                .extract().path("data.id");
        v2Id = id;

        given().when().get(BASE + "/versions?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data.find { it.version == 1 }.isCurrent", equalTo(false))
                .body("data.find { it.version == 2 }.isCurrent", equalTo(true));
    }

    // ─── 仅上传为历史版本（B503） ────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("B503: setCurrent=false → version=3 isCurrent=false, v2 仍是 current")
    void uploadThirdVersionAsHistory() throws Exception {
        File glb = tempFile("model-v3", ".glb", "GLB-BYTES-V3");
        String id = given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", MAT_KEY)
                .multiPart("glbFile", glb, "model/gltf-binary")
                .multiPart("setCurrent", "false")
            .when().post(BASE)
            .then().statusCode(200)
                .body("data.version", equalTo(3))
                .body("data.isCurrent", equalTo(false))
                .extract().path("data.id");
        v3Id = id;

        given().when().get(BASE + "/versions?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data.find { it.version == 2 }.isCurrent", equalTo(true))
                .body("data.find { it.version == 3 }.isCurrent", equalTo(false));
    }

    // ─── 设为当前，部分唯一索引不冲突（B504） ───────────────────────────

    @Test
    @Order(4)
    @DisplayName("B504: PUT set-current(v3) → 仅 v3 isCurrent=true，v1/v2 降级，索引不冲突")
    void setCurrentFlipsOthers() {
        given().when().put(BASE + "/" + v3Id + "/set-current")
            .then().statusCode(200)
                .body("data.isCurrent", equalTo(true))
                .body("data.version", equalTo(3));

        given().when().get(BASE + "/versions?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data.find { it.version == 1 }.isCurrent", equalTo(false))
                .body("data.find { it.version == 2 }.isCurrent", equalTo(false))
                .body("data.find { it.version == 3 }.isCurrent", equalTo(true));

        // 部分唯一索引 uq_model_config_current 未冲突：DB 内该 subject 仅 1 条 is_current
        Number currentCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM model_config WHERE subject_type='MATERIAL' AND subject_key=:k AND is_current=true")
                .setParameter("k", MAT_KEY)
                .getSingleResult();
        Assertions.assertEquals(1L, currentCount.longValue());
    }

    // ─── 历史版本查询（B505） ────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("B505: GET versions 返回 3 条，version 1/2/3 齐全")
    void versionsListComplete() {
        given().when().get(BASE + "/versions?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data", hasSize(3))
                .body("data.version", hasItems(1, 2, 3));
    }

    // ─── 运行端查当前（B506，D15 核心契约） ─────────────────────────────

    @Test
    @Order(6)
    @DisplayName("B506: GET current 返回 v3(非 v1/v2 陈旧值)")
    void currentReturnsLatestNotStale() {
        given().when().get(BASE + "/current?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data.version", equalTo(3))
                .body("data.id", equalTo(v3Id));
    }

    // ─── 缺失占位，非阻断（B507） ────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("B507: subjectKey 从未上传过 → HTTP 200, data=null（非 404/500）")
    void currentMissingReturnsNullNotError() {
        given().when().get(BASE + "/current?subjectType=MATERIAL&subjectKey=NOT_EXIST_" + RUN_ID)
            .then().statusCode(200)
                .body("data", nullValue());
    }

    // ─── 删除 + 级联清文件（B508） ───────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("B508: DELETE 非当前版本(v1) → versions 剩 2 条，model_config_file 级联清 0")
    void deleteCascadesFiles() {
        given().when().delete(BASE + "/" + v1Id)
            .then().statusCode(200);

        given().when().get(BASE + "/versions?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
            .then().statusCode(200)
                .body("data", hasSize(2))
                .body("data.version", not(hasItem(1)));

        Number fileCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM model_config_file WHERE model_config_id = CAST(:id AS uuid)")
                .setParameter("id", v1Id)
                .getSingleResult();
        Assertions.assertEquals(0L, fileCount.longValue());
    }

    // ─── SALES_PART 类型（B510） ─────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("B510: subjectType=SALES_PART 上传 → 可被 list keyword 检索到")
    void salesPartTypeUploadAndSearch() throws Exception {
        File glb = tempFile("sp-model", ".glb", "GLB-BYTES-SP");
        given()
                .multiPart("subjectType", "SALES_PART")
                .multiPart("subjectKey", SP_KEY)
                .multiPart("glbFile", glb, "model/gltf-binary")
                .multiPart("setCurrent", "true")
            .when().post(BASE)
            .then().statusCode(200)
                .body("data.subjectType", equalTo("SALES_PART"));

        given().when().get(BASE + "?subjectType=SALES_PART&keyword=" + SP_KEY)
            .then().statusCode(200)
                .body("data.content.subjectKey", hasItem(SP_KEY));
    }

    // ─── 列表 subjectLabel 批量关联（本实现新增，非纯 backtask 硬性列举，但 api.md §4.1 示例含此字段） ─

    @Test
    @Order(10)
    @DisplayName("列表 MATERIAL Tab 关联材质库 subjectLabel = 材质名(批量, 非逐行)")
    void listEnrichesSubjectLabelForMaterial() {
        given().when().get(BASE + "?subjectType=MATERIAL&keyword=" + MAT_KEY)
            .then().statusCode(200)
                .body("data.content.find { it.subjectKey == '" + MAT_KEY + "' }.subjectLabel",
                        equalTo("测试材质-" + RUN_ID));
    }

    // ─── 字段/文件校验（fail-fast） ────────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("校验: subjectType 非法 → 400；subjectKey 空 → 400；缺 glbFile → 400；非 .glb 后缀 → 400")
    void validationFailFast() throws Exception {
        File glb = tempFile("bad", ".glb", "X");
        File notGlb = tempFile("bad", ".txt", "X");

        given()
                .multiPart("subjectType", "BOGUS")
                .multiPart("subjectKey", "whatever")
                .multiPart("glbFile", glb, "model/gltf-binary")
            .when().post(BASE)
            .then().statusCode(400);

        given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", "")
                .multiPart("glbFile", glb, "model/gltf-binary")
            .when().post(BASE)
            .then().statusCode(400);

        given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", "TEST-MC-NOGLB-" + RUN_ID)
            .when().post(BASE)
            .then().statusCode(400);

        given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", "TEST-MC-BADEXT-" + RUN_ID)
                .multiPart("glbFile", notGlb, "text/plain")
            .when().post(BASE)
            .then().statusCode(400);
    }

    // ─── 文件回源（本实现新增：本地磁盘存储 + serving 端点自测） ─────────

    @Test
    @Order(12)
    @DisplayName("文件回源: GET files/{fileId} 按 glbUrl 取回原始字节")
    void fileServingRoundTrip() {
        String glbUrl = given().when()
                .get(BASE + "/current?subjectType=MATERIAL&subjectKey=" + MAT_KEY)
                .then().statusCode(200)
                .extract().path("data.glbUrl");
        Assertions.assertNotNull(glbUrl);

        given().when().get(glbUrl)
            .then().statusCode(200)
                .body(equalTo("GLB-BYTES-V3"));
    }

    // ─── 收尾：清理本次运行(按 RUN_ID)留下的测试数据，避免共享 DB 长期堆积 ─

    @AfterAll
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM model_config WHERE subject_key LIKE :pat")
                .setParameter("pat", "TEST-MC-%-" + RUN_ID)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM material_recipe WHERE code = :code")
                .setParameter("code", MAT_KEY)
                .executeUpdate();
        utx.commit();
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────

    private File tempFile(String prefix, String suffix, String content) throws Exception {
        File f = File.createTempFile(prefix, suffix);
        f.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }
}
