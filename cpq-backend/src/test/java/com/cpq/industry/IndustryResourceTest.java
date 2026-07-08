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
}
