package com.cpq.existingproduct;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * ExistingProductResource REST 集成测试（task-0712 B3，`/api/cpq/quotations/{id}/existing-products`）。
 *
 * <p>与 {@link ExistingProductServiceTest}（DB 断言主力）互补：本类走真实 HTTP，验证
 * 路由 / quotationId 路径参数解析 / 查询参数过滤 / {@code ApiResponse<PageResult<...>>} 信封序列化。
 *
 * <p>REST 调用与测试方法不在同一事务，数据须真实提交，故用 {@link UserTransaction} + RUN_ID 后缀隔离
 * + {@code @AfterAll} 清理（对齐 {@code ModelConfigResourceTest} 同款风格，防共享 DB 并发撞键/长期堆积）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ExistingProductResource — 从已有产品添加（task-0712 B3）")
class ExistingProductResourceTest {

    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 6);

    private static UUID customerId;
    private static String customerCode;
    private static UUID quotationId;
    private static String matA;

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @SuppressWarnings("unchecked")
    @BeforeAll
    void seed() throws Exception {
        utx.begin();
        em.joinTransaction();

        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1").getResultList();
        if (uRows.isEmpty()) {
            utx.rollback();
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        customerId = UUID.randomUUID();
        customerCode = "EPR" + RUN_ID;
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'EP Resource Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", customerId).setParameter("code", customerCode).executeUpdate();

        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'EP Resource Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
                .setParameter("id", quotationId).setParameter("qno", "QT-EPR-" + RUN_ID)
                .setParameter("cid", customerId).setParameter("uid", adminId).executeUpdate();

        matA = "EPRA" + RUN_ID;
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, specification, created_at, updated_at) " +
                "VALUES (:m, 'DN80', NOW(), NOW())")
                .setParameter("m", matA).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO material_customer_map " +
                "(material_no, customer_no, customer_material_name, customer_product_no, system_type, created_at, updated_at) " +
                "VALUES (:m, :c, :name, :cpn, 'QUOTE', NOW(), NOW())")
                .setParameter("m", matA).setParameter("c", customerCode)
                .setParameter("name", "阀体R").setParameter("cpn", "CPN-R-" + RUN_ID)
                .executeUpdate();

        utx.commit();
    }

    @Test
    @Order(1)
    @DisplayName("GET existing-products 按 quotationId 派生客户，返回 PageResult 信封 + 命中行")
    void listReturnsPageResultEnvelope() {
        given().when().get("/api/cpq/quotations/" + quotationId + "/existing-products")
            .then().statusCode(200)
                .body("data.totalElements", equalTo(1))
                .body("data.content[0].materialNo", equalTo(matA))
                .body("data.content[0].spec", equalTo("DN80"))
                .body("data.content[0].customerProductNo", equalTo("CPN-R-" + RUN_ID));
    }

    @Test
    @Order(2)
    @DisplayName("查询参数过滤生效（含中文 productName，须走 queryParam 自动编码）")
    void listFiltersViaQueryParams() {
        given().queryParam("productName", "阀体R")
            .when().get("/api/cpq/quotations/" + quotationId + "/existing-products")
            .then().statusCode(200)
                .body("data.totalElements", equalTo(1));

        given().queryParam("productName", "不存在的名字-" + RUN_ID)
            .when().get("/api/cpq/quotations/" + quotationId + "/existing-products")
            .then().statusCode(200)
                .body("data.totalElements", equalTo(0));
    }

    @Test
    @Order(3)
    @DisplayName("quotationId 不存在 → 404")
    void notFoundQuotationReturns404() {
        given().when().get("/api/cpq/quotations/" + UUID.randomUUID() + "/existing-products")
            .then().statusCode(404);
    }

    @AfterAll
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no = :c")
                .setParameter("c", customerCode).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no = :m")
                .setParameter("m", matA).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE id = :c")
                .setParameter("c", customerId).executeUpdate();
        utx.commit();
    }
}
