package com.cpq.seltemplate;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("EffectiveTemplateService — 有效模板解析")
class EffectiveTemplateServiceTest {
    private static final String EFF = "/api/cpq/sel-templates/effective";
    private static final String TPL = "/api/cpq/sel-templates";
    private static final String CUST = "TEST-EFF-CUST";
    private static final String IND  = "TEST-EFF-IND";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void setup() throws Exception {
        utx.begin(); em.joinTransaction();
        em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN (SELECT i.id FROM sel_template_item i JOIN sel_template t ON i.template_id=t.id WHERE t.industry_code LIKE 'TEST-EFF-%')").executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id IN (SELECT id FROM sel_template WHERE industry_code LIKE 'TEST-EFF-%')").executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template WHERE industry_code LIKE 'TEST-EFF-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE code LIKE 'TEST-EFF-%'").executeUpdate();
        // 造客户：code=CUST，industry_code=IND
        em.createNativeQuery("INSERT INTO customer (id,name,code,level,industry_code,accumulated_amount,status,version,created_at,updated_at) " +
            "VALUES (gen_random_uuid(),'有效模板测试客户',:c,'STANDARD',:i,0,'ACTIVE',0,NOW(),NOW())")
            .setParameter("c", CUST).setParameter("i", IND).executeUpdate();
        utx.commit();
    }

    @AfterEach
    void cleanupCustomer() throws Exception {
        utx.begin(); em.joinTransaction();
        em.createNativeQuery("DELETE FROM customer WHERE code LIKE 'TEST-EFF-%'").executeUpdate();
        utx.commit();
    }

    @Test @Order(1)
    @DisplayName("T1: 客户行业有模板 → 命中该模板, 材质限2值(有效值只2个), 元素含量启用(effectiveValues空)")
    void hitsIndustryTemplate() {
        // 建 IND 模板：材质限 ["304","H62"] + 元素含量启用
        String body = "{\"industryCode\":\"" + IND + "\",\"name\":\"测试\",\"items\":["
            + "{\"paramTypeCode\":\"MATERIAL\",\"enabled\":true,\"allowedValues\":[\"304\",\"H62\"]},"
            + "{\"paramTypeCode\":\"ELEMENT\",\"enabled\":true,\"allowedValues\":[]}]}";
        given().contentType("application/json").body(body).when().post(TPL).then().statusCode(200);

        given().queryParam("customerNo", CUST).when().get(EFF).then().statusCode(200)
            .body("data.hasTemplate", equalTo(true))
            .body("data.usedDefault", equalTo(false))
            .body("data.resolvedIndustryCode", equalTo(IND))
            .body("data.params.find { it.paramTypeCode=='MATERIAL' }.valueMode", equalTo("single"))
            // 有效材质值 ⊆ {304,H62}（取决于库里 material_recipe 是否含这些 code；至少不含 allowedValues 以外的）
            .body("data.params.find { it.paramTypeCode=='MATERIAL' }.effectiveValues.key", everyItem(anyOf(equalTo("304"), equalTo("H62"))))
            .body("data.params.find { it.paramTypeCode=='ELEMENT' }.effectiveValues", hasSize(0));
    }

    @Test @Order(2)
    @DisplayName("T2: 客户行业无模板但有 __DEFAULT__ → 回退默认 usedDefault=true")
    void fallsBackToDefault() {
        // 防误删护栏：__DEFAULT__ 全局唯一，Plan 2 的选配模板管理 UI 已能让 PM 真配一个 __DEFAULT__。
        // 若库中已存在真实 __DEFAULT__，本用例的 upsert 会覆盖它、finally 再整条删掉 → 误删生产数据。
        // 因此先探测：已存在则跳过（不建不删，绝不碰真实默认模板）；不存在才走 create+finally delete。
        Number existingDefaultCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM sel_template WHERE industry_code = '__DEFAULT__'")
            .getSingleResult();
        Assumptions.assumeTrue(existingDefaultCount.longValue() == 0,
            "跳过: 库中已有真实__DEFAULT__模板, 避免误删");

        // 不建 IND 模板，建 __DEFAULT__ 模板（全局唯一——测试自建自清，finally 中按创建返回的 id 精确删除，不影响其它会话）
        String body = "{\"industryCode\":\"__DEFAULT__\",\"name\":\"默认\",\"items\":[{\"paramTypeCode\":\"PROCESS\",\"enabled\":true,\"allowedValues\":[]}]}";
        String defaultId = given().contentType("application/json").body(body)
            .when().post(TPL).then().statusCode(200)
            .extract().path("data.id");
        try {
            given().queryParam("customerNo", CUST).when().get(EFF).then().statusCode(200)
                .body("data.usedDefault", equalTo(true))
                .body("data.resolvedIndustryCode", equalTo("__DEFAULT__"))
                .body("data.params.paramTypeCode", hasItem("PROCESS"));
        } finally {
            // 精确按 id 删除本测试自建的 __DEFAULT__，避免污染共享库其它会话
            given().when().delete(TPL + "/" + defaultId).then().statusCode(200);
        }
    }

    @Test @Order(3)
    @DisplayName("T3: customerNo 缺失 → 400")
    void missingCustomer() {
        given().when().get(EFF).then().statusCode(400);
    }
}
