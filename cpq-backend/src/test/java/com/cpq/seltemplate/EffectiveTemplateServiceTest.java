package com.cpq.seltemplate;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * EffectiveTemplateService — 有效模板解析（task-0712 update-071501 换轴到「客户产品分类」）。
 *
 * <p>2026-07-16 由行业维度重写为产品分类维度：选配模板归属列已从行业码换成产品分类 id，
 * 客户绑定改走 {@code customer.product_category_id}；原 {@code __DEFAULT__} 哨兵换成真实的
 * {@code name='默认分类'} 产品分类（该分类由 V337 迁移幂等 seed 保障存在）。
 *
 * <p>自建自清测试分类（code=TEST-EFF-CAT），customer 绑定指向它；沿用原测试对"默认分类模板"
 * 的护栏——若"默认分类"已挂真实选配模板则 {@link Assumptions#assumeTrue} 跳过相关用例，
 * 避免误删共享库生产数据。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("EffectiveTemplateService — 有效模板解析（产品分类轴）")
class EffectiveTemplateServiceTest {
    private static final String EFF = "/api/cpq/sel-templates/effective";
    private static final String TPL = "/api/cpq/sel-templates";
    private static final String CUST = "TEST-EFF-CUST";
    private static final String TEST_CATEGORY_CODE = "TEST-EFF-CAT";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private UUID testCategoryId;
    private UUID defaultCategoryId;

    @BeforeAll
    void setupFixtures() throws Exception {
        utx.begin(); em.joinTransaction();

        // 自建测试分类（幂等：按 code 查，无则插）——供本类全部用例的客户绑定用
        @SuppressWarnings("unchecked")
        List<Object> found = em.createNativeQuery("SELECT id FROM product_category WHERE code = :code")
                .setParameter("code", TEST_CATEGORY_CODE).getResultList();
        if (!found.isEmpty()) {
            testCategoryId = (UUID) found.get(0);
        } else {
            testCategoryId = (UUID) em.createNativeQuery(
                    "INSERT INTO product_category (id, code, name, status, sort_order, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), :code, '有效模板测试分类', 'ACTIVE', 999, NOW(), NOW()) RETURNING id")
                    .setParameter("code", TEST_CATEGORY_CODE)
                    .getSingleResult();
        }

        // "默认分类"由 V337 迁移幂等 seed 保障存在
        defaultCategoryId = (UUID) em.createNativeQuery(
                "SELECT id FROM product_category WHERE name = '默认分类' LIMIT 1")
                .getSingleResult();

        // 造客户：code=CUST，product_category_id=testCategoryId
        em.createNativeQuery("DELETE FROM customer WHERE code = :c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO customer (id,name,code,level,product_category_id,accumulated_amount,status,version,created_at,updated_at) " +
                "VALUES (gen_random_uuid(),'有效模板测试客户',:c,'STANDARD',:cat,0,'ACTIVE',0,NOW(),NOW())")
                .setParameter("c", CUST).setParameter("cat", testCategoryId).executeUpdate();

        utx.commit();
    }

    @BeforeEach
    void cleanupTestCategoryTemplates() throws Exception {
        utx.begin(); em.joinTransaction();
        em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN " +
                "(SELECT i.id FROM sel_template_item i JOIN sel_template t ON i.template_id=t.id WHERE t.product_category_id = :cat)")
                .setParameter("cat", testCategoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id IN " +
                "(SELECT id FROM sel_template WHERE product_category_id = :cat)")
                .setParameter("cat", testCategoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template WHERE product_category_id = :cat")
                .setParameter("cat", testCategoryId).executeUpdate();
        utx.commit();
    }

    @AfterAll
    void cleanupFixtures() throws Exception {
        utx.begin(); em.joinTransaction();
        em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN " +
                "(SELECT i.id FROM sel_template_item i JOIN sel_template t ON i.template_id=t.id WHERE t.product_category_id = :cat)")
                .setParameter("cat", testCategoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id IN " +
                "(SELECT id FROM sel_template WHERE product_category_id = :cat)")
                .setParameter("cat", testCategoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template WHERE product_category_id = :cat")
                .setParameter("cat", testCategoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE code = :c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM product_category WHERE code = :code")
                .setParameter("code", TEST_CATEGORY_CODE).executeUpdate();
        utx.commit();
    }

    @Test @Order(1)
    @DisplayName("T1: 客户分类有模板 → 命中该模板, 材质限2值(有效值只2个), 元素含量启用(effectiveValues空)")
    void hitsCategoryTemplate() {
        // 建测试分类模板：材质限 ["304","H62"] + 元素含量启用
        String body = "{\"productCategoryId\":\"" + testCategoryId + "\",\"name\":\"测试\",\"items\":["
            + "{\"paramTypeCode\":\"MATERIAL\",\"enabled\":true,\"allowedValues\":[\"304\",\"H62\"]},"
            + "{\"paramTypeCode\":\"ELEMENT\",\"enabled\":true,\"allowedValues\":[]}]}";
        given().contentType("application/json").body(body).when().post(TPL).then().statusCode(200);

        given().queryParam("customerNo", CUST).when().get(EFF).then().statusCode(200)
            .body("data.hasTemplate", equalTo(true))
            .body("data.usedDefault", equalTo(false))
            .body("data.resolvedCategoryId", equalTo(testCategoryId.toString()))
            .body("data.params.find { it.paramTypeCode=='MATERIAL' }.valueMode", equalTo("single"))
            // 有效材质值 ⊆ {304,H62}（取决于库里 material_recipe 是否含这些 code；至少不含 allowedValues 以外的）
            .body("data.params.find { it.paramTypeCode=='MATERIAL' }.effectiveValues.key", everyItem(anyOf(equalTo("304"), equalTo("H62"))))
            .body("data.params.find { it.paramTypeCode=='ELEMENT' }.effectiveValues", hasSize(0));
    }

    @Test @Order(2)
    @DisplayName("T2: 客户分类无模板但「默认分类」有 → 回退默认分类 usedDefault=true")
    void fallsBackToDefaultCategory() {
        // 防误删护栏：「默认分类」模板全局唯一，若库中已存在真实模板，本用例的 upsert 会覆盖它、
        // finally 再整条删掉 → 误删生产数据。因此先探测：已存在则跳过（不建不删，绝不碰真实默认模板）；
        // 不存在才走 create+finally delete。
        Number existingDefaultCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM sel_template WHERE product_category_id = :cat")
            .setParameter("cat", defaultCategoryId)
            .getSingleResult();
        Assumptions.assumeTrue(existingDefaultCount.longValue() == 0,
            "跳过: 库中「默认分类」已有真实选配模板, 避免误删生产数据");

        // 客户所属测试分类不建模板（@BeforeEach 已清空），只建「默认分类」模板（全局唯一——
        // 测试自建自清，finally 中按创建返回的 id 精确删除，不影响其它会话）
        String body = "{\"productCategoryId\":\"" + defaultCategoryId + "\",\"name\":\"默认\",\"items\":[{\"paramTypeCode\":\"PROCESS\",\"enabled\":true,\"allowedValues\":[]}]}";
        String defaultTplId = given().contentType("application/json").body(body)
            .when().post(TPL).then().statusCode(200)
            .extract().path("data.id");
        try {
            given().queryParam("customerNo", CUST).when().get(EFF).then().statusCode(200)
                .body("data.usedDefault", equalTo(true))
                .body("data.resolvedCategoryId", equalTo(defaultCategoryId.toString()))
                .body("data.params.paramTypeCode", hasItem("PROCESS"));
        } finally {
            // 精确按 id 删除本测试自建的「默认分类」模板，避免污染共享库其它会话
            given().when().delete(TPL + "/" + defaultTplId).then().statusCode(200);
        }
    }

    @Test @Order(3)
    @DisplayName("T3: customerNo 缺失 → 400")
    void missingCustomer() {
        given().when().get(EFF).then().statusCode(400);
    }

    @Test @Order(4)
    @DisplayName("T4: 客户分类无模板且「默认分类」也无模板 → hasTemplate=false, templateId=null")
    void noTemplateAtAll() {
        // 同 T2 的护栏：若共享库已存在真实「默认分类」模板，本用例无法验证"两者皆无"这一态，
        // 跳过而非误删生产数据。
        Number existingDefaultCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM sel_template WHERE product_category_id = :cat")
            .setParameter("cat", defaultCategoryId)
            .getSingleResult();
        Assumptions.assumeTrue(existingDefaultCount.longValue() == 0,
            "跳过: 库中「默认分类」已有真实选配模板, 无法验证 hasTemplate=false 态");

        // @BeforeEach 已清空测试分类下模板；CUST 的 productCategoryId=testCategoryId 无对应模板。
        given().queryParam("customerNo", CUST).when().get(EFF).then().statusCode(200)
            .body("data.hasTemplate", equalTo(false))
            .body("data.templateId", nullValue())
            .body("data.usedDefault", equalTo(false))
            .body("data.params", hasSize(0));
    }
}
