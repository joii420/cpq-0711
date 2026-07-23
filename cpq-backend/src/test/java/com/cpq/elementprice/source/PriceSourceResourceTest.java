package com.cpq.elementprice.source;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PriceSourceResource — task-0722 · B4 价格源 CRUD")
class PriceSourceResourceTest {

    private static final String BASE = "/api/cpq/element-price/sources";
    private static final String NAME = "TEST-SRC-上海有色网";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private static String createdId;

    @BeforeEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name LIKE 'TEST-SRC-%'").executeUpdate();
        utx.commit();
    }

    @Test
    @Order(1)
    @DisplayName("T1: 新建 → 200，sourceType 后端固定 MANUAL（无视前端传值）")
    void create() {
        createdId = given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "\",\"sourceUrl\":\"https://test.example/1\",\"sourceType\":\"API\"}")
            .when().post(BASE)
            .then().statusCode(200)
                .body("sourceName", equalTo(NAME))
                .body("sourceType", equalTo("MANUAL"))
                .body("status", equalTo("ACTIVE"))
            .extract().path("id");
    }

    @Test
    @Order(2)
    @DisplayName("T2: 名称+网址重复 → 409")
    void createDuplicateConflict() {
        given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "\",\"sourceUrl\":\"https://test.example/1\"}")
            .when().post(BASE).then().statusCode(200);

        given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "\",\"sourceUrl\":\"https://test.example/1\"}")
            .when().post(BASE)
            .then().statusCode(409);
    }

    @Test
    @Order(3)
    @DisplayName("T3: 列表 status=ACTIVE 过滤 + 停用后不再出现")
    void listAndDisableSemantics() {
        String id = given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "-3\",\"sourceUrl\":\"https://test.example/3\"}")
            .when().post(BASE).then().statusCode(200).extract().path("id");

        given().queryParam("status", "ACTIVE")
            .when().get(BASE)
            .then().statusCode(200)
                .body("sourceName", hasItem(NAME + "-3"));

        given().contentType("application/json").body("{\"status\":\"DISABLED\"}")
            .when().post(BASE + "/" + id + "/status")
            .then().statusCode(200).body("status", equalTo("DISABLED"));

        given().queryParam("status", "ACTIVE")
            .when().get(BASE)
            .then().statusCode(200)
                .body("sourceName", org.hamcrest.Matchers.not(hasItem(NAME + "-3")));

        // 全部/DISABLED 列表仍能看到（历史照常显示，§11.13.1）
        given().queryParam("status", "DISABLED")
            .when().get(BASE)
            .then().statusCode(200)
                .body("sourceName", hasItem(NAME + "-3"));
    }

    @Test
    @Order(4)
    @DisplayName("T4: 编辑保留 id，返回更新后的字段")
    void update() {
        String id = given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "-4\",\"sourceUrl\":\"https://test.example/4\"}")
            .when().post(BASE).then().statusCode(200).extract().path("id");

        given().contentType("application/json")
                .body("{\"sourceName\":\"" + NAME + "-4改\",\"sourceUrl\":\"https://test.example/4\",\"description\":\"备注\"}")
            .when().put(BASE + "/" + id)
            .then().statusCode(200)
                .body("id", equalTo(id))
                .body("sourceName", equalTo(NAME + "-4改"))
                .body("description", equalTo("备注"));
    }

    @Test
    @Order(5)
    @DisplayName("T5: 编辑不存在的源 → 404")
    void updateNotFound() {
        given().contentType("application/json")
                .body("{\"sourceName\":\"x\",\"sourceUrl\":\"y\"}")
            .when().put(BASE + "/" + java.util.UUID.randomUUID())
            .then().statusCode(404);
    }
}
