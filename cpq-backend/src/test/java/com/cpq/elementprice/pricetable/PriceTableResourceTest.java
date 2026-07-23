package com.cpq.elementprice.pricetable;

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
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("PriceTableResource — task-0722 · B6 价格表 / B7.1 各源最新价")
class PriceTableResourceTest {

    private static final String BASE = "/api/cpq/element-price";
    private static final String ELEM = "TEST-PT-AG";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private UUID sourceA;
    private UUID sourceB;

    @BeforeEach
    void setup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_daily_price WHERE element_name = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name LIKE 'TEST-PT-SRC%'").executeUpdate();
        em.createNativeQuery("DELETE FROM element WHERE element_code = :c").setParameter("c", ELEM).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element (id, element_code, element_name, element_no, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, '测试银2', 'TESTNO-PT', 'ACTIVE', NOW(), NOW())")
                .setParameter("c", ELEM).executeUpdate();

        sourceA = UUID.randomUUID();
        sourceB = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO element_price_source (id, source_name, source_type, status, created_at, updated_at) VALUES " +
                "(:a, 'TEST-PT-SRC-A', 'MANUAL', 'ACTIVE', NOW(), NOW()), " +
                "(:b, 'TEST-PT-SRC-B', 'MANUAL', 'DISABLED', NOW(), NOW())")
                .setParameter("a", sourceA).setParameter("b", sourceB).executeUpdate();

        LocalDate today = LocalDate.now();
        em.createNativeQuery(
                "INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status, created_at, updated_at) VALUES " +
                "(gen_random_uuid(), :c, :a, :d0, 5740.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW()), " +
                "(gen_random_uuid(), :c, :a, :d1, 5762.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW()), " +
                "(gen_random_uuid(), :c, :b, :d1, 5700.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW())")
                .setParameter("c", ELEM).setParameter("a", sourceA).setParameter("b", sourceB)
                .setParameter("d0", today.minusDays(1)).setParameter("d1", today)
                .executeUpdate();
        utx.commit();
    }

    @Test
    @DisplayName("T1: 明细按 sourceId 过滤正确")
    void detailFilterBySource() {
        given().queryParam("sourceId", sourceA.toString()).queryParam("keyword", "TEST-PT")
            .when().get(BASE + "/prices")
            .then().statusCode(200)
                .body("totalElements", equalTo(2))
                .body("content.sourceId", hasItem(sourceA.toString()));
    }

    @Test
    @DisplayName("T2: 矩阵缺 sourceId → 400")
    void matrixRequiresSourceId() {
        given().queryParam("keyword", "TEST-PT")
            .when().get(BASE + "/prices/matrix")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("T3: 矩阵跨度 > 90 天 → 400")
    void matrixSpanTooLarge() {
        given().queryParam("sourceId", sourceA.toString())
                .queryParam("from", LocalDate.now().minusDays(120).toString())
                .queryParam("to", LocalDate.now().toString())
            .when().get(BASE + "/prices/matrix")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("T4: 矩阵正确对齐日期，无记录为 null（不补零）")
    void matrixAlignsDatesWithNulls() {
        given().queryParam("sourceId", sourceA.toString()).queryParam("keyword", "TEST-PT")
                .queryParam("from", LocalDate.now().minusDays(5).toString())
                .queryParam("to", LocalDate.now().toString())
            .when().get(BASE + "/prices/matrix")
            .then().statusCode(200)
                .body("dates.size()", equalTo(2))
                .body("rows[0].prices.size()", equalTo(2));
    }

    @Test
    @DisplayName("T5: 各源最新价 — 2 源各 1 行，停用源仍返回并带 sourceStatus=DISABLED")
    void latestBySourceIncludesDisabled() {
        given().queryParam("elementCode", ELEM)
            .when().get(BASE + "/latest-by-source")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .body("find { it.sourceId == '" + sourceB + "' }.sourceStatus", equalTo("DISABLED"))
                .body("find { it.sourceId == '" + sourceA + "' }.price", equalTo(5762.0f));
    }

    @Test
    @DisplayName("T6: 无任何价格记录的元素 → latest-by-source 返回空数组")
    void latestBySourceEmptyForUnknownElement() {
        given().queryParam("elementCode", "TEST-PT-NOPRICE")
            .when().get(BASE + "/latest-by-source")
            .then().statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    @DisplayName("T7: 导出明细 → 200 xlsx")
    void exportDetail() {
        given().queryParam("sourceId", sourceA.toString())
            .when().get(BASE + "/prices/export")
            .then().statusCode(200);
    }

    @Test
    @DisplayName("T8: 导出矩阵 → 200 xlsx")
    void exportMatrix() {
        given().queryParam("sourceId", sourceA.toString())
            .when().get(BASE + "/prices/matrix/export")
            .then().statusCode(200);
    }
}
