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

import java.util.UUID;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductTemplateBindingResourceTest {

    @Inject
    EntityManager em;

    private static String productId;
    private static String publishedTemplateId;
    private static String bindingId;
    private static String processId1;
    private static String processId2;

    @BeforeEach
    @Transactional
    void setup() {
        // Clean up previous bindings and templates for our test product part_no
        em.createNativeQuery("DELETE FROM product_template_binding ptb USING product p WHERE ptb.product_id = p.id AND p.part_no = 'BIND-TEST-SKU'").executeUpdate();
        em.createNativeQuery("DELETE FROM template_component tc USING template t WHERE tc.template_id = t.id AND t.name LIKE 'Bind-Test-Template%'").executeUpdate();
        em.createQuery("DELETE FROM Template t WHERE t.name LIKE 'Bind-Test-Template%'").executeUpdate();
        em.createQuery("DELETE FROM Component c WHERE c.code LIKE 'BIND-TEST-%'").executeUpdate();
        em.createQuery("DELETE FROM Product p WHERE p.partNo = 'BIND-TEST-SKU'").executeUpdate();

        // Create product
        em.createNativeQuery("INSERT INTO product (id, name, part_no, category, status, tags, created_at, updated_at) VALUES (gen_random_uuid(), 'Bind Test Product', 'BIND-TEST-SKU', 'CUSTOM', 'ACTIVE', '[]', now(), now())")
          .executeUpdate();
        Object row = em.createNativeQuery("SELECT id FROM product WHERE part_no = 'BIND-TEST-SKU'").getSingleResult();
        productId = row.toString();

        // Create component
        Component comp = new Component();
        comp.name = "Bind Test Component";
        comp.code = "BIND-TEST-001";
        comp.fields = "[{\"name\":\"qty\",\"field_type\":\"INPUT\"}]";
        comp.formulas = "[]";
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        em.persist(comp);
        em.flush();

        // Create and publish template
        Template t = new Template();
        t.templateSeriesId = UUID.randomUUID();
        t.name = "Bind-Test-Template-Published";
        t.category = "STANDARD_PARTS";
        t.subtotalFormula = "[{\"type\":\"subtotal\"}]";
        t.status = "DRAFT";
        em.persist(t);
        em.flush();

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = t.id;
        tc.componentId = comp.id;
        tc.tabName = "投料";
        tc.sortOrder = 0;
        em.persist(tc);
        em.flush();

        // Build snapshot and publish
        t.componentsSnapshot = "[{\"tabName\":\"投料\",\"componentId\":\"" + comp.id + "\",\"fields\":[{\"name\":\"qty\",\"field_type\":\"INPUT\"}]}]";
        t.version = "v1.0";
        t.status = "PUBLISHED";
        em.merge(t);
        em.flush();

        publishedTemplateId = t.id.toString();
        processId1 = UUID.randomUUID().toString();
        processId2 = UUID.randomUUID().toString();
    }

    @Test
    @Order(1)
    void createBinding() {
        String body = String.format("""
            {
              "templateId": "%s",
              "processIds": ["%s", "%s"],
              "isDefault": false
            }
            """, publishedTemplateId, processId1, processId2);

        bindingId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.productId", equalTo(productId))
                .body("data.templateId", equalTo(publishedTemplateId))
                .body("data.isDefault", equalTo(false))
                .body("data.processIdsHash", notNullValue())
                .extract().path("data.id");
    }

    @Test
    @Order(2)
    void listBindings() {
        // Create one first
        String body = String.format("{\"templateId\": \"%s\", \"processIds\": [\"%s\"]}", publishedTemplateId, processId1);
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then().statusCode(200);

        RestAssured.given()
            .get("/api/cpq/products/" + productId + "/template-bindings")
            .then()
                .statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(3)
    void setDefaultBinding() {
        // Create binding
        String body = String.format("{\"templateId\": \"%s\", \"processIds\": [\"%s\", \"%s\"]}", publishedTemplateId, processId1, processId2);
        String bid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Set as default
        RestAssured.given()
            .contentType(ContentType.JSON)
            .put("/api/cpq/products/" + productId + "/template-bindings/" + bid + "/set-default")
            .then()
                .statusCode(200)
                .body("data.isDefault", equalTo(true));
    }

    @Test
    @Order(4)
    void matchTemplates() {
        // Create binding with specific process combo
        String body = String.format("{\"templateId\": \"%s\", \"processIds\": [\"%s\", \"%s\"]}", publishedTemplateId, processId1, processId2);
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then().statusCode(200);

        // Match with same process ids (order may differ)
        RestAssured.given()
            .queryParam("processIds", processId2 + "," + processId1)
            .get("/api/cpq/products/" + productId + "/template-bindings/match")
            .then()
                .statusCode(200)
                .body("data.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(5)
    void rejectDraftTemplateBinding() {
        // Create a draft template
        String createBody = "{\"name\": \"Bind-Test-Template-Draft\"}";
        String draftId = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .post("/api/cpq/templates")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Try to bind to a draft template — should fail
        String body = String.format("{\"templateId\": \"%s\", \"processIds\": []}", draftId);
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(6)
    void deleteBinding() {
        // Create binding
        String body = String.format("{\"templateId\": \"%s\", \"processIds\": []}", publishedTemplateId);
        String bid = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/cpq/products/" + productId + "/template-bindings")
            .then()
                .statusCode(200)
                .extract().path("data.id");

        // Delete
        RestAssured.given()
            .delete("/api/cpq/products/" + productId + "/template-bindings/" + bid)
            .then()
                .statusCode(200);
    }
}
