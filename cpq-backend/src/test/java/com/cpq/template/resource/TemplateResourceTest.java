package com.cpq.template.resource;

import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TemplateResourceTest {

    @Inject
    EntityManager em;

    private static String componentId;
    private static String templateId;
    private static String seriesId;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM template_component tc USING template t WHERE tc.template_id = t.id AND t.name LIKE 'Test Template%'").executeUpdate();
        em.createQuery("DELETE FROM Template t WHERE t.name LIKE 'Test Template%'").executeUpdate();
        em.createQuery("DELETE FROM Component c WHERE c.code LIKE 'TMPL-TEST-%'").executeUpdate();
    }

    @BeforeEach
    @Transactional
    void createComponent() {
        Component comp = new Component();
        comp.name = "Test Component For Template";
        comp.code = "TMPL-TEST-001";
        comp.fields = "[{\"name\":\"qty\",\"field_type\":\"INPUT\"}]";
        comp.formulas = "[]";
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        em.persist(comp);
        em.flush();
        componentId = comp.id.toString();
    }

    @Test
    @Order(1)
    void createTemplate() {
        String body = """
            {
              "name": "Test Template Alpha",
              "category": "STANDARD_PARTS",
              "description": "Test description"
            }
            """;

        templateId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.name", equalTo("Test Template Alpha"))
                .body("data.status", equalTo("DRAFT"))
                .body("data.category", equalTo("STANDARD_PARTS"))
                .extract().path("data.id");

        seriesId = RestAssured.given()
            .get("/api/cpq/templates/" + templateId)
            .then()
                .statusCode(200)
                .extract().path("data.templateSeriesId");
    }

    @Test
    @Order(2)
    void addComponentAndPublish() {
        // Create template
        String createBody = """
            {
              "name": "Test Template Beta",
              "category": "STANDARD_PARTS",
              "subtotalFormula": "[{\\"type\\":\\"component_subtotal\\",\\"ref\\":\\"TMPL-TEST-001\\"}]"
            }
            """;

        String tid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Add component
        String addBody = String.format("{\"componentId\": \"%s\", \"tabName\": \"投料\"}", componentId);
        String tcId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(addBody)
            .post("/api/cpq/templates/" + tid + "/components")
            .then()
                .statusCode(200)
                .body("data.tabName", equalTo("投料"))
                .extract().path("data.id");

        // Publish
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + tid + "/publish")
            .then()
                .statusCode(200)
                .body("data.status", equalTo("PUBLISHED"))
                .body("data.version", notNullValue())
                .body("data.componentsSnapshot", notNullValue());
    }

    @Test
    @Order(3)
    void createNewDraftAndVersionHistory() {
        // Create template with subtotal formula and component
        String createBody = """
            {
              "name": "Test Template Gamma",
              "subtotalFormula": "[{\\"type\\":\\"subtotal\\"}]"
            }
            """;

        String tid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Add component
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\", \"tabName\": \"Tab1\"}", componentId))
            .post("/api/cpq/templates/" + tid + "/components")
            .then()
                .statusCode(200);

        // Publish
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + tid + "/publish")
            .then()
                .statusCode(200)
                .body("data.version", equalTo("v1.0"));

        String sid = RestAssured.given()
            .get("/api/cpq/templates/" + tid)
            .then()
                .statusCode(200)
                .extract().path("data.templateSeriesId");

        // Create new draft
        String newDraftId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + tid + "/new-draft")
            .then()
                .statusCode(200)
                .body("data.status", equalTo("DRAFT"))
                .body("data.templateSeriesId", equalTo(sid))
                .extract().path("data.id");

        // Publish new draft (minor version bump)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + newDraftId + "/publish")
            .then()
                .statusCode(200)
                .body("data.version", equalTo("v1.1"));

        // Version history
        RestAssured.given()
            .get("/api/cpq/templates/series/" + sid + "/versions")
            .then()
                .statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(4)
    void publishValidation_requiresSubtotalFormula() {
        String createBody = """
            {"name": "Test Template Delta"}
            """;
        String tid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Add a component but no subtotal formula
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\"}", componentId))
            .post("/api/cpq/templates/" + tid + "/components")
            .then()
                .statusCode(200);

        // Publish should fail - no subtotal formula
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + tid + "/publish")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(5)
    void listAndDeleteDraft() {
        // Create
        String createBody = """
            {"name": "Test Template Epsilon", "category": "RAW_MATERIALS"}
            """;
        String tid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // List with filter
        RestAssured.given()
            .queryParam("keyword", "Test Template Epsilon")
            .get("/api/cpq/templates")
            .then()
                .statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(1));

        // Delete DRAFT
        RestAssured.given()
            .delete("/api/cpq/templates/" + tid)
            .then()
                .statusCode(200);

        // Confirm deleted
        RestAssured.given()
            .get("/api/cpq/templates/" + tid)
            .then()
                .statusCode(404);
    }

    /**
     * 验证发布校验放宽:有 SUBTOTAL 类型组件即可发布,无需 subtotalFormula JSONB token 数组。
     * 对应前端"拖入小计组件"的新模型(SubtotalDropBar)。
     */
    @Test
    @Order(6)
    void publishValidation_subtotalComponentSatisfiesValidation() {
        // 1. 建一个 SUBTOTAL 类型组件
        String subtotalComponentId = createSubtotalComponent();

        // 2. 建模板,不传 subtotalFormula
        String createBody = """
            {"name": "Test Template Subtotal-Comp"}
            """;
        String tid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // 3. 加一个 SUBTOTAL 组件
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\", \"tabName\": \"小计\"}", subtotalComponentId))
            .post("/api/cpq/templates/" + tid + "/components")
            .then()
                .statusCode(200);

        // 4. 发布应成功(SUBTOTAL 组件满足校验,即便 subtotalFormula 为空)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/cpq/templates/" + tid + "/publish")
            .then()
                .statusCode(200)
                .body("data.status", equalTo("PUBLISHED"));
    }

    /**
     * Phase 1 客户报价模板匹配 — 客户专属优先 → 通用兜底
     * 对应 docs/API.md L100/L643
     */
    @Test
    @Order(9)
    void matchCustomerQuote_specificFound_returnsSpecific() {
        java.util.UUID catId = createProductCategory("CAT_MATCH_SPEC");
        java.util.UUID customerId = createTestCustomer("MATCH-SPEC-CUST");
        // 通用模板(应被客户专属屏蔽)
        publishTestTemplate("Test Template GeneralBackup", catId, null, "[{\"type\":\"subtotal\"}]");
        // 客户专属
        publishTestTemplate("Test Template CustomerSpec", catId, customerId, "[{\"type\":\"subtotal\"}]");

        RestAssured.given()
            .queryParam("customerId", customerId)
            .queryParam("categoryId", catId)
            .get("/api/cpq/templates/match-customer-quote")
            .then()
                .statusCode(200)
                .body("data.matchType", equalTo("CUSTOMER_SPECIFIC"))
                .body("data.templates.size()", equalTo(1))
                .body("data.templates[0].name", equalTo("Test Template CustomerSpec"));
    }

    @Test
    @Order(10)
    void matchCustomerQuote_noSpecific_fallsBackToGeneral() {
        java.util.UUID catId = createProductCategory("CAT_MATCH_GEN");
        java.util.UUID customerId = createTestCustomer("MATCH-GEN-CUST");
        // 仅通用,无客户专属
        publishTestTemplate("Test Template OnlyGeneral", catId, null, "[{\"type\":\"subtotal\"}]");

        RestAssured.given()
            .queryParam("customerId", customerId)
            .queryParam("categoryId", catId)
            .get("/api/cpq/templates/match-customer-quote")
            .then()
                .statusCode(200)
                .body("data.matchType", equalTo("GENERAL_FALLBACK"))
                .body("data.templates.size()", equalTo(1))
                .body("data.templates[0].name", equalTo("Test Template OnlyGeneral"));
    }

    @Test
    @Order(11)
    void matchCustomerQuote_neither_returnsNone() {
        java.util.UUID catId = createProductCategory("CAT_MATCH_NONE");
        java.util.UUID customerId = createTestCustomer("MATCH-NONE-CUST");

        RestAssured.given()
            .queryParam("customerId", customerId)
            .queryParam("categoryId", catId)
            .get("/api/cpq/templates/match-customer-quote")
            .then()
                .statusCode(200)
                .body("data.matchType", equalTo("NONE"))
                .body("data.templates.size()", equalTo(0));
    }

    @Test
    @Order(12)
    void matchCustomerQuote_multipleSpecificVersions_returnsAll() {
        java.util.UUID catId = createProductCategory("CAT_MATCH_MULTI");
        java.util.UUID customerId = createTestCustomer("MATCH-MULTI-CUST");
        // 同 (customer, category) 两个独立 series PUBLISHED — V62 撤销约束后允许
        publishTestTemplate("Test Template MultiA", catId, customerId, "[{\"type\":\"subtotal\"}]");
        publishTestTemplate("Test Template MultiB", catId, customerId, "[{\"type\":\"subtotal\"}]");

        RestAssured.given()
            .queryParam("customerId", customerId)
            .queryParam("categoryId", catId)
            .get("/api/cpq/templates/match-customer-quote")
            .then()
                .statusCode(200)
                .body("data.matchType", equalTo("CUSTOMER_SPECIFIC"))
                .body("data.templates.size()", equalTo(2));
    }

    /**
     * 创建测试用客户(用 NativeQuery 避开 Customer entity 的 prePersist hooks 导致的 NPE 风险)
     */
    @Transactional
    java.util.UUID createTestCustomer(String prefix) {
        java.util.UUID id = java.util.UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO customer(id, code, name, level, status, accumulated_amount, created_at, updated_at) " +
                "VALUES (:id, :code, :name, 'STANDARD', 'ACTIVE', 0, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("code", prefix + "_" + suffix)
                .setParameter("name", prefix + "-" + suffix)
                .executeUpdate();
        return id;
    }

    /**
     * 创建并发布测试模板,自动加 1 个组件 + 1 个 SUBTOTAL 公式占位
     */
    String publishTestTemplate(String name, java.util.UUID categoryId, java.util.UUID customerId, String subtotalFormula) {
        String body = String.format(
                "{\"name\": \"%s\", \"categoryId\": \"%s\"%s, \"subtotalFormula\": \"%s\"}",
                name, categoryId,
                customerId != null ? ", \"customerId\": \"" + customerId + "\"" : "",
                subtotalFormula.replace("\"", "\\\""));
        String tid = RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .post("/api/cpq/templates")
            .then().statusCode(200).extract().path("data.id");
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\"}", componentId))
            .post("/api/cpq/templates/" + tid + "/components")
            .then().statusCode(200);
        RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tid + "/publish")
            .then().statusCode(200);
        return tid;
    }

    @Transactional
    java.util.UUID createProductCategory(String prefix) {
        java.util.UUID id = java.util.UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO product_category(id, code, name, status, sort_order, created_at, updated_at) " +
                "VALUES (:id, :code, :name, 'ACTIVE', 0, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("code", prefix + "_" + suffix)
                .setParameter("name", prefix + "-" + suffix)
                .executeUpdate();
        return id;
    }

    @Transactional
    String createSubtotalComponent() {
        Component subtotalComp = new Component();
        subtotalComp.name = "Test Subtotal Component";
        subtotalComp.code = "TMPL-TEST-SUBTOTAL";
        subtotalComp.fields = "[]";
        subtotalComp.formulas = "[{\"name\":\"total\",\"expression\":[]}]";
        subtotalComp.componentType = "SUBTOTAL";
        subtotalComp.columnCount = 1;
        subtotalComp.status = "ACTIVE";
        em.persist(subtotalComp);
        em.flush();
        return subtotalComp.id.toString();
    }

    /**
     * V62 撤销 V28 唯一约束后,同 (customer_id, category_id) 可并存多个 PUBLISHED 版本。
     * 此测试验证:同 series 升级 v1.0 → v1.1 时,旧版保持 PUBLISHED 状态,
     * 由用户后续主动归档(对应 PRD.md L744 多版本设计)。
     */
    @Test
    @Order(7)
    void publish_sameSeriesUpgrade_oldVersionStaysPublished() {
        java.util.UUID catId = createProductCategory("CAT_MULTIVER");
        // v1.0 with categoryId
        String tid1 = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format(
                "{\"name\": \"Test Template MultiVersion\", \"categoryId\": \"%s\", " +
                "\"subtotalFormula\": \"[{\\\"type\\\":\\\"subtotal\\\"}]\"}",
                catId))
            .post("/api/cpq/templates")
            .then().statusCode(200).extract().path("data.id");
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\", \"tabName\": \"Tab1\"}", componentId))
            .post("/api/cpq/templates/" + tid1 + "/components")
            .then().statusCode(200);
        RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tid1 + "/publish")
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));

        // new-draft (同 series)
        String tid2 = RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tid1 + "/new-draft")
            .then().statusCode(200).extract().path("data.id");

        // v1.1 publish — 应成功(不再因 V28 唯一约束阻塞)
        RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tid2 + "/publish")
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));

        // 验证 v1.0 仍然 PUBLISHED(不再自动归档)
        RestAssured.given()
            .get("/api/cpq/templates/" + tid1)
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));
    }

    /**
     * V62 撤销约束后,跨 series 同 categoryId 多个 PUBLISHED 也允许并存。
     */
    @Test
    @Order(8)
    void publish_crossSeriesSameCategory_bothPublished() {
        java.util.UUID catId = createProductCategory("CAT_CROSS_OK");

        // Series A 发布 v1.0
        String tidA = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format(
                "{\"name\": \"Test Template MultiSeriesA\", \"categoryId\": \"%s\", " +
                "\"subtotalFormula\": \"[{\\\"type\\\":\\\"subtotal\\\"}]\"}",
                catId))
            .post("/api/cpq/templates")
            .then().statusCode(200).extract().path("data.id");
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\"}", componentId))
            .post("/api/cpq/templates/" + tidA + "/components")
            .then().statusCode(200);
        RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tidA + "/publish")
            .then().statusCode(200);

        // Series B(独立 templateSeriesId) — 同 categoryId
        String tidB = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format(
                "{\"name\": \"Test Template MultiSeriesB\", \"categoryId\": \"%s\", " +
                "\"subtotalFormula\": \"[{\\\"type\\\":\\\"subtotal\\\"}]\"}",
                catId))
            .post("/api/cpq/templates")
            .then().statusCode(200).extract().path("data.id");
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(String.format("{\"componentId\": \"%s\"}", componentId))
            .post("/api/cpq/templates/" + tidB + "/components")
            .then().statusCode(200);

        // Series B 发布 — V62 后应允许成功(同 category 多 PUBLISHED 并存)
        RestAssured.given()
            .contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/templates/" + tidB + "/publish")
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));

        // 双方都仍是 PUBLISHED
        RestAssured.given()
            .get("/api/cpq/templates/" + tidA)
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));
        RestAssured.given()
            .get("/api/cpq/templates/" + tidB)
            .then().statusCode(200).body("data.status", equalTo("PUBLISHED"));
    }
}
