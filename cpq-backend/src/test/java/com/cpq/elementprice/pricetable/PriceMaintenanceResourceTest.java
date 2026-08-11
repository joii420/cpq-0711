package com.cpq.elementprice.pricetable;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PriceMaintenanceService / PriceTableResource 手工维护端点测试（update-0724 · B8 自检）。
 *
 * <p>覆盖：撞键 409（原值不变）、price&lt;=0 拒绝、键字段被 Jackson 丢弃、IMPORT→MANUAL 翻转、
 * 三种动作各写一条日志、DELETE 的 snapshot 是删除前值、写入失败时价格与日志同事务回滚。
 */
@QuarkusTest
@TestProfile(PriceMaintenanceResourceTest.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PriceMaintenanceService — update-0724 · B4/B5/B6 手工维护 + 变更历史")
class PriceMaintenanceResourceTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    private static final String BASE = "/api/cpq/element-price";
    private static final String ELEM = "TEST-PM-AG";

    @Inject EntityManager em;
    @Inject UserTransaction utx;
    @Inject PriceMaintenanceService maintenanceService;

    private UUID sourceActive;
    private UUID sourceDisabled;

    @BeforeEach
    void setup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_daily_price_log WHERE element_name = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery("DELETE FROM element_daily_price WHERE element_name = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name LIKE 'TEST-PM-SRC%'").executeUpdate();
        em.createNativeQuery("DELETE FROM element WHERE element_code = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element (id, element_code, element_name, element_no, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, '测试银3', 'TESTNO-PM', 'ACTIVE', NOW(), NOW())")
                .setParameter("c", ELEM).executeUpdate();

        sourceActive = UUID.randomUUID();
        sourceDisabled = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO element_price_source (id, source_name, source_type, status, created_at, updated_at) VALUES " +
                "(:a, 'TEST-PM-SRC-A', 'MANUAL', 'ACTIVE', NOW(), NOW()), " +
                "(:b, 'TEST-PM-SRC-B', 'MANUAL', 'DISABLED', NOW(), NOW())")
                .setParameter("a", sourceActive).setParameter("b", sourceDisabled).executeUpdate();
        utx.commit();
    }

    // ══════════════════════ B4.1 新建 ══════════════════════

    @Test
    @Order(1)
    @DisplayName("T1: 新建价格 → 201，fetchStatus=MANUAL")
    void create_success() {
        given().contentType(JSON).body(createBody(ELEM, sourceActive, LocalDate.now().toString(), "5860.0000"))
            .when().post(BASE + "/prices")
            .then().statusCode(201)
                .body("id", notNullValue())
                .body("fetchStatus", equalTo("MANUAL"))
                .body("elementCode", equalTo(ELEM))
                .body("price", equalTo("5860"));
    }

    @Test
    @Order(2)
    @DisplayName("T2: 撞键 → 409，原值不变")
    void create_duplicateKey_returns409_originalUnchanged() {
        String today = LocalDate.now().toString();
        given().contentType(JSON).body(createBody(ELEM, sourceActive, today, "5860.0000"))
            .when().post(BASE + "/prices")
            .then().statusCode(201);

        given().contentType(JSON).body(createBody(ELEM, sourceActive, today, "9999.0000"))
            .when().post(BASE + "/prices")
            .then().statusCode(409)
                .body("message", containsString("已存在"));

        given().queryParam("sourceId", sourceActive.toString()).queryParam("keyword", ELEM)
            .when().get(BASE + "/prices")
            .then().statusCode(200)
                .body("content[0].price", equalTo("5860"));
    }

    @Test
    @Order(3)
    @DisplayName("T3: price=0 → 400")
    void create_zeroPrice_returns400() {
        given().contentType(JSON).body(createBody(ELEM, sourceActive, LocalDate.now().toString(), "0"))
            .when().post(BASE + "/prices")
            .then().statusCode(400);
    }

    @Test
    @Order(4)
    @DisplayName("T4: price 负数 → 400")
    void create_negativePrice_returns400() {
        given().contentType(JSON).body(createBody(ELEM, sourceActive, LocalDate.now().toString(), "-5"))
            .when().post(BASE + "/prices")
            .then().statusCode(400);
    }

    @Test
    @Order(5)
    @DisplayName("T5: elementCode 不存在 → 400")
    void create_unknownElement_returns400() {
        given().contentType(JSON).body(createBody("TEST-PM-NOPE", sourceActive, LocalDate.now().toString(), "100"))
            .when().post(BASE + "/prices")
            .then().statusCode(400);
    }

    @Test
    @Order(6)
    @DisplayName("T6: sourceId 已停用 → 400")
    void create_disabledSource_returns400() {
        given().contentType(JSON).body(createBody(ELEM, sourceDisabled, LocalDate.now().toString(), "100"))
            .when().post(BASE + "/prices")
            .then().statusCode(400);
    }

    @Test
    @Order(7)
    @DisplayName("T7: sourceId 缺失 → 400")
    void create_missingSource_returns400() {
        String body = """
                {"elementCode":"%s","priceDate":"%s","price":100,"currency":"CNY","priceUnit":"kg"}
                """.formatted(ELEM, LocalDate.now());
        given().contentType(JSON).body(body)
            .when().post(BASE + "/prices")
            .then().statusCode(400);
    }

    @Test
    @Order(8)
    @DisplayName("task-0810: price JSON number is rejected without a partial insert")
    void create_numericPrice_returns400_withoutPartialWrite() throws Exception {
        long before = countRows();
        String body = """
                {"elementCode":"%s","sourceId":"%s","priceDate":"%s",
                 "price":98765431.123456789012,"currency":"CNY","priceUnit":"kg"}
                """.formatted(ELEM, sourceActive, LocalDate.now());

        given().contentType(JSON).body(body)
            .when().post(BASE + "/prices")
            .then().statusCode(400)
                .body("attributeName", equalTo("price"))
                .body("value", equalTo("98765431.123456789012"));

        assertEquals(before, countRows(), "rejected numeric token must not insert a price row");
    }

    // ══════════════════════ B4.2 修改 ══════════════════════

    @Test
    @Order(10)
    @DisplayName("T8: 修改导入行 → fetch_status 翻为 MANUAL，强传键字段被忽略")
    void update_importRow_flipsToManual_keyFieldsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        LocalDate origDate = LocalDate.now().minusDays(1);
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status, created_at, updated_at) " +
                "VALUES (:id, :c, :s, :d, 5700.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW())")
                .setParameter("id", id).setParameter("c", ELEM).setParameter("s", sourceActive).setParameter("d", origDate)
                .executeUpdate();
        utx.commit();

        // 强行传键字段（elementCode/sourceId/priceDate）——UpdatePriceRequest 根本不声明这三个字段，
        // Jackson 直接丢弃，验证键不被污染（U4 / api.md §2）。
        String body = """
                {"price":"5920.0000","currency":"CNY","priceUnit":"kg","elementCode":"Cu","sourceId":"%s","priceDate":"2020-01-01"}
                """.formatted(UUID.randomUUID());
        given().contentType(JSON).body(body)
            .when().put(BASE + "/prices/" + id)
            .then().statusCode(200)
                .body("fetchStatus", equalTo("MANUAL"))
                .body("price", equalTo("5920"))
                .body("elementCode", equalTo(ELEM));

        utx.begin();
        em.joinTransaction();
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT fetch_status, element_name, source_id, price_date FROM element_daily_price WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        utx.commit();
        assertEquals("MANUAL", row[0]);
        assertEquals(ELEM, row[1]);
        assertEquals(sourceActive, row[2]);
        assertEquals(origDate, toLocalDate(row[3]));
    }

    @Test
    @Order(11)
    @DisplayName("T9: 修改 price<=0 → 400")
    void update_zeroPrice_returns400() throws Exception {
        UUID id = seedRow(ELEM, sourceActive, LocalDate.now(), new BigDecimal("100"), "MANUAL");
        given().contentType(JSON).body("""
                {"price":"0","currency":"CNY","priceUnit":"kg"}
                """)
            .when().put(BASE + "/prices/" + id)
            .then().statusCode(400);
    }

    @Test
    @Order(12)
    @DisplayName("T10: 修改不存在的 id → 404")
    void update_notFound_returns404() {
        given().contentType(JSON).body("""
                {"price":"100","currency":"CNY","priceUnit":"kg"}
                """)
            .when().put(BASE + "/prices/" + UUID.randomUUID())
            .then().statusCode(404);
    }

    // ══════════════════════ B4.3 删除 ══════════════════════

    @Test
    @Order(13)
    @DisplayName("T11: 删除 → 204，日志 snapshot 为删除前值")
    void delete_success_logsPreDeleteSnapshot() throws Exception {
        UUID id = seedRow(ELEM, sourceActive, LocalDate.now(), new BigDecimal("5800.0000"), "MANUAL");

        given().when().delete(BASE + "/prices/" + id)
            .then().statusCode(204);

        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery("SELECT COUNT(*) FROM element_daily_price WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        Object[] logRow = (Object[]) em.createNativeQuery(
                "SELECT action, snapshot::text FROM element_daily_price_log WHERE price_id = :id ORDER BY changed_at DESC LIMIT 1")
                .setParameter("id", id).getSingleResult();
        utx.commit();
        assertEquals(0, cnt.intValue(), "价格行应已被删除");
        assertEquals("DELETE", logRow[0]);
        assertTrue(((String) logRow[1]).contains("5800"), "DELETE 日志的 snapshot 应为删除前的值: " + logRow[1]);
    }

    @Test
    @Order(14)
    @DisplayName("T12: 删除不存在的 id → 404")
    void delete_notFound_returns404() {
        given().when().delete(BASE + "/prices/" + UUID.randomUUID())
            .then().statusCode(404);
    }

    // ══════════════════════ B4.4 三种动作各写一条日志 + B5 历史查询 ══════════════════════

    @Test
    @Order(20)
    @DisplayName("T13: CREATE/UPDATE/DELETE 各写一条日志，历史查询 UPDATE 行含 price 的 changes")
    void threeActions_eachWritesOneLog() throws Exception {
        String today = LocalDate.now().toString();
        String id = given().contentType(JSON).body(createBody(ELEM, sourceActive, today, "5000"))
                .when().post(BASE + "/prices")
                .then().statusCode(201).extract().path("id");

        given().contentType(JSON).body("""
                {"price":"5100","currency":"CNY","priceUnit":"kg"}
                """)
            .when().put(BASE + "/prices/" + id)
            .then().statusCode(200);

        given().when().delete(BASE + "/prices/" + id).then().statusCode(204);

        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price_log WHERE price_id = :id")
                .setParameter("id", UUID.fromString(id)).getSingleResult();
        @SuppressWarnings("unchecked")
        List<String> actions = em.createNativeQuery(
                "SELECT action FROM element_daily_price_log WHERE price_id = :id ORDER BY changed_at ASC")
                .setParameter("id", UUID.fromString(id)).getResultList();
        utx.commit();
        assertEquals(3, cnt.intValue(), "CREATE/UPDATE/DELETE 各应写一条日志");
        assertEquals(List.of("CREATE", "UPDATE", "DELETE"), actions);

        given().queryParam("sourceId", sourceActive.toString()).queryParam("keyword", ELEM)
            .when().get(BASE + "/prices/history")
            .then().statusCode(200)
                .body("totalElements", equalTo(3))
                .body("content.find { it.action == 'UPDATE' }.changes.field", hasItem("price"));
    }

    // ══════════════════════ 事务原子性（验收 11） ══════════════════════

    @Test
    @Order(30)
    @DisplayName("T14: 日志写入失败 → 价格写入整体回滚（changed_by_name 超长触发 DB 长度约束）")
    void create_logWriteFails_rollsBackPriceWrite() throws Exception {
        // element_daily_price_log.changed_by_name 是 VARCHAR(100)，"user".full_name 是 VARCHAR(200)：
        // 造一个 101~200 字符的 full_name，在 user 表合法，但写日志时必然触发约束失败。
        // ⚠️ username/email 必须按 UUID 唯一化：固定字符串在共享远程库上重跑会撞 user_email_key，
        // 若撞键异常从这段裸 utx.begin()/commit() 中抛出且未 catch，会让本线程残留"已关联事务"，
        // poison 同一 JVM fork 内后续所有测试类的 @BeforeEach utx.begin()（ARJUNA016051）。
        UUID longNameUser = UUID.randomUUID();
        String longName = "长".repeat(150);
        utx.begin();
        try {
            em.joinTransaction();
            em.createNativeQuery(
                    "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                    "VALUES (:id, :un, :fn, :em, 'hash', 'SYSTEM_ADMIN', 'ACTIVE', false, NOW(), NOW())")
                    .setParameter("id", longNameUser).setParameter("un", "test-pm-longname-" + longNameUser)
                    .setParameter("fn", longName).setParameter("em", "test-pm-longname-" + longNameUser + "@test.com")
                    .executeUpdate();
            utx.commit();
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }

        CreatePriceRequest req = new CreatePriceRequest();
        req.elementCode = ELEM;
        req.sourceId = sourceActive;
        req.priceDate = LocalDate.now();
        req.price = new BigDecimal("6100");
        req.currency = "CNY";
        req.priceUnit = "kg";

        assertThrows(Exception.class, () -> maintenanceService.create(req, longNameUser));

        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = :c AND source_id = :s AND price_date = :d")
                .setParameter("c", ELEM).setParameter("s", sourceActive).setParameter("d", LocalDate.now())
                .getSingleResult();
        utx.commit();
        assertEquals(0, cnt.intValue(), "日志写入失败应导致价格写入整体回滚，不允许只落价格不落日志");
    }

    // ══════════════════════ helpers ══════════════════════

    private String createBody(String elementCode, UUID sourceId, String priceDate, String price) {
        return """
                {"elementCode":"%s","sourceId":"%s","priceDate":"%s","price":"%s","currency":"CNY","priceUnit":"kg"}
                """.formatted(elementCode, sourceId, priceDate, price);
    }

    private UUID seedRow(String elementCode, UUID sourceId, LocalDate priceDate, BigDecimal price, String fetchStatus) throws Exception {
        UUID id = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status, created_at, updated_at) " +
                "VALUES (:id, :c, :s, :d, :p, 'CNY', 'kg', :fs, NOW(), NOW())")
                .setParameter("id", id).setParameter("c", elementCode).setParameter("s", sourceId)
                .setParameter("d", priceDate).setParameter("p", price).setParameter("fs", fetchStatus)
                .executeUpdate();
        utx.commit();
        return id;
    }

    private long countRows() throws Exception {
        utx.begin();
        em.joinTransaction();
        long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = :c AND source_id = :s")
                .setParameter("c", ELEM).setParameter("s", sourceActive).getSingleResult()).longValue();
        utx.commit();
        return count;
    }

    private LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        return LocalDate.parse(o.toString());
    }
}
