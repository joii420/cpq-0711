package com.cpq.elementprice.strategy;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * task-0722 · B8 策略 CRUD + 历史写入 / B9 历史查询与差异 / B10 试算。
 */
@QuarkusTest
@DisplayName("StrategyResource — task-0722 · B8/B9/B10")
class StrategyResourceTest {

    private static final String BASE = "/api/cpq/element-price/strategies";
    private static final String CUSTOMER = "TEST-STRAT-CUST-1269";
    private static final String ELEM = "TEST-ST-AG";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private UUID sourceId;

    @BeforeEach
    void setup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_price_strategy_log WHERE customer_no LIKE 'TEST-STRAT-%' OR customer_no = '_GLOBAL_TEST_'").executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_strategy WHERE customer_no LIKE 'TEST-STRAT-%' OR customer_no = '_GLOBAL_TEST_'").executeUpdate();
        em.createNativeQuery("DELETE FROM element_daily_price WHERE element_name = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name = 'TEST-STRAT-SRC'").executeUpdate();
        em.createNativeQuery("DELETE FROM element WHERE element_code = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE code = :c").setParameter("c", CUSTOMER).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element (id, element_code, element_name, element_no, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, '测试银3', 'TESTNO-ST', 'ACTIVE', NOW(), NOW())")
                .setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO customer (id, code, name, level, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :code, 'B8策略测试客户', 'STANDARD', 'ACTIVE', NOW(), NOW())")
                .setParameter("code", CUSTOMER).executeUpdate();
        utx.commit();

        sourceId = UUID.fromString(given().contentType("application/json")
                .body("{\"sourceName\":\"TEST-STRAT-SRC\",\"sourceUrl\":\"https://test.example/strat\"}")
            .when().post("/api/cpq/element-price/sources")
            .then().statusCode(200).extract().path("id").toString());
    }

    // ── B8: 默认策略 CRUD ──

    @Test
    @DisplayName("T1: 保存默认策略（新建）→ 200 + 同事务写 CREATE 历史")
    void saveDefaultCreatesAndLogs() {
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.05,"premium":2.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default")
            .then().statusCode(200)
                .body("method", equalTo("AVG"))
                .body("windowNum", equalTo(30))
                .body("factor", equalTo(1.05f))
                .body("premium", equalTo(2.00f));

        given().queryParam("customerNo", CUSTOMER).queryParam("elementCode", "__DEFAULT__")
            .when().get(BASE + "/history")
            .then().statusCode(200)
                .body("content.size()", equalTo(1))
                .body("content[0].action", equalTo("CREATE"))
                .body("content[0].changes.size()", equalTo(0))
                .body("content[0].snapshot.method", equalTo("AVG"));
    }

    @Test
    @DisplayName("T2: 再次保存默认策略 → UPDATE + 历史只列变化项")
    void saveDefaultUpdatesAndDiffs() {
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.00,"premium":0.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default").then().statusCode(200);

        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.05,"premium":0.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default").then().statusCode(200)
                .body("factor", equalTo(1.05f));

        given().queryParam("customerNo", CUSTOMER).queryParam("elementCode", "__DEFAULT__")
            .when().get(BASE + "/history")
            .then().statusCode(200)
                .body("content.size()", equalTo(2))
                .body("content[0].action", equalTo("UPDATE"))
                .body("content[0].changes.size()", equalTo(1))
                .body("content[0].changes[0].field", equalTo("factor"))
                .body("content[0].changes[0].oldValue", equalTo("1.00"))
                .body("content[0].changes[0].newValue", equalTo("1.05"));
    }

    @Test
    @DisplayName("T3: method=LATEST 带窗口参数 → 400")
    void latestWithWindowRejected() {
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"LATEST","windowNum":30,"windowUnit":"DAY"}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("T4: sourceId 非 ACTIVE → 400")
    void disabledSourceRejectedForStrategy() {
        given().contentType("application/json").body("{\"status\":\"DISABLED\"}")
            .when().post("/api/cpq/element-price/sources/" + sourceId + "/status")
            .then().statusCode(200);

        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"LATEST"}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default")
            .then().statusCode(400);
    }

    // ── B8: 例外 CRUD ──

    @Test
    @DisplayName("T5: 例外新建/回读一致，优先于默认；重复 → 409；删除 → 204 + DELETE 历史存删除前快照")
    void exceptionCrudAndConflict() {
        // 先建默认
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.05,"premium":2.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default").then().statusCode(200);

        String excId = given().contentType("application/json").body("""
                {"customerNo":"%s","elementCode":"%s","sourceId":"%s","method":"LATEST","factor":1.00,"premium":0.00}
                """.formatted(CUSTOMER, ELEM, sourceId))
            .when().post(BASE + "/exceptions")
            .then().statusCode(200)
                .body("elementCode", equalTo(ELEM))
                .body("method", equalTo("LATEST"))
            .extract().path("id");

        given().queryParam("customerNo", CUSTOMER)
            .when().get(BASE)
            .then().statusCode(200)
                .body("default.factor", equalTo(1.05f))
                .body("exceptions.elementCode", hasItem(ELEM));

        // 重复例外 → 409
        given().contentType("application/json").body("""
                {"customerNo":"%s","elementCode":"%s","sourceId":"%s","method":"LATEST"}
                """.formatted(CUSTOMER, ELEM, sourceId))
            .when().post(BASE + "/exceptions")
            .then().statusCode(409);

        // 删除
        given().when().delete(BASE + "/exceptions/" + excId).then().statusCode(204);

        given().queryParam("customerNo", CUSTOMER)
            .when().get(BASE)
            .then().statusCode(200)
                .body("exceptions.size()", equalTo(0));

        given().queryParam("customerNo", CUSTOMER).queryParam("elementCode", ELEM)
            .when().get(BASE + "/history")
            .then().statusCode(200)
                .body("content.size()", equalTo(2))  // CREATE + DELETE
                .body("content[0].action", equalTo("DELETE"))
                .body("content[0].snapshot.method", equalTo("LATEST"));
    }

    @Test
    @DisplayName("T6: _GLOBAL_ 可配可读，同样入历史（用完即清，仅清本测试自建 source 引用的行，不碰其它 _GLOBAL_ 数据）")
    void globalCustomerConfigurable() throws Exception {
        // 🔒 customerNo 用字面量 "_GLOBAL_"（真实核价成本口径特殊值），不是随意测试字符串——
        // 这正是要验证的"全链路 String、_GLOBAL_ 原样穿透"行为本身。清理仅按本测试专属 sourceId 精确定位，
        // 不会误删其它并发用户/会话已配置的真实 _GLOBAL_ 策略（那些行引用的是别的 source_id）。
        try {
            given().contentType("application/json").body("""
                    {"customerNo":"_GLOBAL_","sourceId":"%s","method":"LATEST"}
                    """.formatted(sourceId))
                .when().put(BASE + "/default")
                .then().statusCode(200)
                    .body("method", equalTo("LATEST"));

            given().queryParam("customerNo", "_GLOBAL_")
                .when().get(BASE)
                .then().statusCode(200)
                    .body("customerNo", equalTo("_GLOBAL_"))
                    .body("default", notNullValue())
                    .body("default.sourceId", equalTo(sourceId.toString()));

            given().queryParam("customerNo", "_GLOBAL_")
                .when().get(BASE + "/history")
                .then().statusCode(200)
                    .body("content.findAll { it.snapshot.sourceId == '%s' }.size()".formatted(sourceId), equalTo(1));
        } finally {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM element_price_strategy_log WHERE customer_no = '_GLOBAL_' AND snapshot->>'sourceId' = :sid")
                    .setParameter("sid", sourceId.toString()).executeUpdate();
            em.createNativeQuery("DELETE FROM element_price_strategy WHERE customer_no = '_GLOBAL_' AND source_id = :sid")
                    .setParameter("sid", sourceId).executeUpdate();
            utx.commit();
        }
    }

    @Test
    @DisplayName("T7: 未配置策略的（真实存在）客户 → default=null, exceptions=[]")
    void unconfiguredCustomerReturnsEmpty() throws Exception {
        String otherCustomer = "TEST-STRAT-CUST-UNCONFIGURED";
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM customer WHERE code = :c").setParameter("c", otherCustomer).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO customer (id, code, name, level, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :code, 'B8未配置策略测试客户', 'STANDARD', 'ACTIVE', NOW(), NOW())")
                .setParameter("code", otherCustomer).executeUpdate();
        utx.commit();

        given().queryParam("customerNo", otherCustomer)
            .when().get(BASE)
            .then().statusCode(200)
                .body("default", nullValue())
                .body("exceptions.size()", equalTo(0));
    }

    // ── B10: 试算 ──

    @Test
    @DisplayName("T8: 试算（无 draft）留空兜底 — 窗口内无价 hasPrice=false, finalPrice=null")
    void simulateNoPriceLeavesBlank() {
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.05,"premium":2.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default").then().statusCode(200);

        given().contentType("application/json").body("""
                {"customerNo":"%s","baseDate":"%s"}
                """.formatted(CUSTOMER, LocalDate.now()))
            .when().post(BASE + "/simulate")
            .then().statusCode(200)
                .body("find { it.elementCode == '%s' }.hasPrice".formatted(ELEM), equalTo(false))
                .body("find { it.elementCode == '%s' }.finalPrice".formatted(ELEM), nullValue());
    }

    @Test
    @DisplayName("T9: 试算 LATEST 命中真实价格 → finalPrice = raw*factor+premium")
    void simulateLatestComputesFinalPrice() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, :s, :d, 5820.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW())")
                .setParameter("c", ELEM).setParameter("s", sourceId).setParameter("d", LocalDate.now())
                .executeUpdate();
        utx.commit();

        given().contentType("application/json").body("""
                {"customerNo":"%s","elementCode":"%s","sourceId":"%s","method":"LATEST","factor":1.10,"premium":5.00}
                """.formatted(CUSTOMER, ELEM, sourceId))
            .when().post(BASE + "/exceptions").then().statusCode(200);

        given().contentType("application/json").body("""
                {"customerNo":"%s","baseDate":"%s"}
                """.formatted(CUSTOMER, LocalDate.now()))
            .when().post(BASE + "/simulate")
            .then().statusCode(200)
                .body("find { it.elementCode == '%s' }.hitRule".formatted(ELEM), equalTo("EXCEPTION"))
                .body("find { it.elementCode == '%s' }.hasPrice".formatted(ELEM), equalTo(true))
                .body("find { it.elementCode == '%s' }.rawValue".formatted(ELEM), equalTo(5820.0f))
                // finalPrice = 5820 * 1.10 + 5.00 = 6407.0000
                .body("find { it.elementCode == '%s' }.finalPrice".formatted(ELEM), equalTo(6407.0f));
    }

    @Test
    @DisplayName("T10: 试算 draft（未保存草稿）不落库，读回策略仍是旧值")
    void simulateDraftDoesNotPersist() {
        given().contentType("application/json").body("""
                {"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.00,"premium":0.00}
                """.formatted(CUSTOMER, sourceId))
            .when().put(BASE + "/default").then().statusCode(200);

        given().contentType("application/json").body("""
                {"customerNo":"%s","baseDate":"%s","draft":{"default":{"customerNo":"%s","sourceId":"%s","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":9.99,"premium":0}}}
                """.formatted(CUSTOMER, LocalDate.now(), CUSTOMER, sourceId))
            .when().post(BASE + "/simulate")
            .then().statusCode(200);

        // 读回：真实保存的策略应仍是 factor=1.00（未被草稿污染）
        given().queryParam("customerNo", CUSTOMER)
            .when().get(BASE)
            .then().statusCode(200)
                .body("default.factor", equalTo(1.00f));
    }
}
