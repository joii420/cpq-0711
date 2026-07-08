package com.cpq.industry;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("IndustryResource — 行业管理 CRUD")
class IndustryResourceTest {

    private static final String BASE = "/api/cpq/industries";
    private static final String TEST_CODE = "TEST-IND-AUTO";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM customer WHERE code LIKE 'TEST-IND-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM industry WHERE code LIKE 'TEST-IND-%'").executeUpdate();
        utx.commit();
    }

    @Test
    @Order(1)
    @DisplayName("T1: create → 200 + 回显 code/name")
    void create() {
        given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"自动测试行业\"}")
        .when().post(BASE)
        .then().statusCode(200)
            .body("data.code", equalTo(TEST_CODE))
            .body("data.name", equalTo("自动测试行业"))
            .body("data.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(2)
    @DisplayName("T2: create 重复 code → 业务错误")
    void createDuplicate() {
        given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"甲\"}")
        .when().post(BASE).then().statusCode(200);

        given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"乙\"}")
        .when().post(BASE)
        .then().statusCode(400);
    }

    @Test
    @Order(3)
    @DisplayName("T3: listActive 含新建的 ACTIVE 行业")
    void listActive() {
        given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"活跃行业\"}")
        .when().post(BASE).then().statusCode(200);

        given().when().get(BASE + "/active")
        .then().statusCode(200)
            .body("data.code", hasItem(TEST_CODE));
    }

    @Test
    @Order(4)
    @DisplayName("T4: @RoleAllowed 注解存在于 IndustryResource")
    void roleAnnotationPresent() {
        Assertions.assertTrue(
            com.cpq.industry.resource.IndustryResource.class
                .isAnnotationPresent(com.cpq.common.security.RoleAllowed.class),
            "IndustryResource 必须有 @RoleAllowed");
    }

    @Test
    @Order(5)
    @DisplayName("T5: delete 未被引用的行业 → 200，且不再出现在 listActive 里")
    void deleteUnreferenced() {
        String id = given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"待删除行业\"}")
        .when().post(BASE)
        .then().statusCode(200)
            .extract().path("data.id");

        given().when().delete(BASE + "/" + id)
        .then().statusCode(200);

        given().when().get(BASE + "/active")
        .then().statusCode(200)
            .body("data.code", not(hasItem(TEST_CODE)));
    }

    @Test
    @Order(6)
    @DisplayName("T6: delete 被客户引用的行业 → 业务错误")
    void deleteReferencedByCustomer() throws Exception {
        String id = given().contentType("application/json")
            .body("{\"code\":\"" + TEST_CODE + "\",\"name\":\"被引用行业\"}")
        .when().post(BASE)
        .then().statusCode(200)
            .extract().path("data.id");

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
            "INSERT INTO customer(id, name, code, level, accumulated_amount, status, industry_code) " +
            "VALUES (gen_random_uuid(), 'TEST-IND-客户', 'TEST-IND-CUST-01', 'STANDARD', 0, 'ACTIVE', :ic)")
            .setParameter("ic", TEST_CODE)
            .executeUpdate();
        utx.commit();

        given().when().delete(BASE + "/" + id)
        .then().statusCode(400);
    }
}
