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
@DisplayName("SelTemplateResource — 选配模板管理")
class SelTemplateResourceTest {
    private static final String TPL = "/api/cpq/sel-templates";
    private static final String PT  = "/api/cpq/sel-param-types";
    private static final String IND = "TEST-SEL-IND";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void cleanup() throws Exception {
        utx.begin(); em.joinTransaction();
        em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN " +
            "(SELECT i.id FROM sel_template_item i JOIN sel_template t ON i.template_id=t.id WHERE t.industry_code LIKE 'TEST-SEL-%')").executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id IN " +
            "(SELECT id FROM sel_template WHERE industry_code LIKE 'TEST-SEL-%')").executeUpdate();
        em.createNativeQuery("DELETE FROM sel_template WHERE industry_code LIKE 'TEST-SEL-%'").executeUpdate();
        utx.commit();
    }

    @Test @Order(1)
    @DisplayName("T1: 参数池种子含 材质/元素含量/工序 三类")
    void paramPoolSeed() {
        given().when().get(PT).then().statusCode(200)
            .body("data.code", hasItems("MATERIAL", "ELEMENT", "PROCESS"))
            .body("data.find { it.code=='MATERIAL' }.valueMode", equalTo("single"))
            .body("data.find { it.code=='PROCESS' }.valueMode", equalTo("multi"));
    }

    @Test @Order(2)
    @DisplayName("T2: upsert 建模板(启用材质+限2值, 启用工序不限) → 回读 items/allowedValues 正确")
    void upsertCreate() {
        String body = "{\"industryCode\":\"" + IND + "\",\"name\":\"测试行业模板\",\"items\":["
            + "{\"paramTypeCode\":\"MATERIAL\",\"enabled\":true,\"allowedValues\":[\"304\",\"H62\"]},"
            + "{\"paramTypeCode\":\"PROCESS\",\"enabled\":true,\"allowedValues\":[]}]}";
        given().contentType("application/json").body(body)
            .when().post(TPL).then().statusCode(200)
            .body("data.industryCode", equalTo(IND))
            .body("data.items.find { it.paramTypeCode=='MATERIAL' }.allowedValues", hasItems("304","H62"))
            .body("data.items.find { it.paramTypeCode=='PROCESS' }.allowedValues", hasSize(0));
    }

    @Test @Order(3)
    @DisplayName("T3: 同行业再 upsert = 更新(不新建), 全量替换 items")
    void upsertUpdateReplaces() {
        String b1 = "{\"industryCode\":\"" + IND + "\",\"name\":\"v1\",\"items\":[{\"paramTypeCode\":\"MATERIAL\",\"enabled\":true,\"allowedValues\":[\"304\"]}]}";
        given().contentType("application/json").body(b1).when().post(TPL).then().statusCode(200);
        String b2 = "{\"industryCode\":\"" + IND + "\",\"name\":\"v2\",\"items\":[{\"paramTypeCode\":\"PROCESS\",\"enabled\":true,\"allowedValues\":[]}]}";
        given().contentType("application/json").body(b2).when().post(TPL).then().statusCode(200)
            .body("data.name", equalTo("v2"))
            .body("data.items", hasSize(1))
            .body("data.items[0].paramTypeCode", equalTo("PROCESS"));
        // 全库该行业仍只 1 条模板
        given().when().get(TPL).then().statusCode(200)
            .body("data.findAll { it.industryCode=='" + IND + "' }", hasSize(1));
    }

    @Test @Order(4)
    @DisplayName("T4: @RoleAllowed 存在")
    void roleAnn() {
        Assertions.assertTrue(com.cpq.seltemplate.resource.SelTemplateResource.class
            .isAnnotationPresent(com.cpq.common.security.RoleAllowed.class));
    }

    @Test @Order(5)
    @DisplayName("T5: 候选端点冒烟 — MATERIAL/PROCESS 真实取数, ELEMENT 空, 未知类型 400")
    void candidatesSmoke() {
        given().when().get(PT + "/MATERIAL/candidates").then().statusCode(200)
            .body("data", notNullValue())
            .body("data.every { it.key != null && it.label != null }", equalTo(true));

        given().when().get(PT + "/PROCESS/candidates").then().statusCode(200)
            .body("data", notNullValue())
            .body("data.every { it.key != null && it.label != null }", equalTo(true));

        given().when().get(PT + "/ELEMENT/candidates").then().statusCode(200)
            .body("data", hasSize(0));

        given().when().get(PT + "/NOSUCH/candidates").then().statusCode(400);
    }

    @Test @Order(6)
    @DisplayName("T6: delete 级联清 items/values, 不留孤儿")
    void deleteCascade() {
        String body = "{\"industryCode\":\"" + IND + "\",\"name\":\"待删模板\",\"items\":["
            + "{\"paramTypeCode\":\"MATERIAL\",\"enabled\":true,\"allowedValues\":[\"304\"]},"
            + "{\"paramTypeCode\":\"PROCESS\",\"enabled\":true,\"allowedValues\":[]}]}";
        String id = given().contentType("application/json").body(body)
            .when().post(TPL).then().statusCode(200)
            .extract().path("data.id");

        given().when().delete(TPL + "/" + id).then().statusCode(200);

        given().when().get(TPL).then().statusCode(200)
            .body("data.findAll { it.industryCode=='" + IND + "' }", hasSize(0));

        Number itemCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM sel_template_item WHERE template_id = '" + id + "'")
            .getSingleResult();
        Assertions.assertEquals(0L, itemCount.longValue());

        Number valueCount = (Number) em.createNativeQuery(
                "SELECT count(*) FROM sel_template_item_value v " +
                "JOIN sel_template_item i ON v.item_id = i.id WHERE i.template_id = '" + id + "'")
            .getSingleResult();
        Assertions.assertEquals(0L, valueCount.longValue());
    }
}
