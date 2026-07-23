package com.cpq.elementprice;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * task-0722 · B7.2 —— 元素列表 lastModifiedAt = MAX(element.updated_at, 价格记录 updated_at)。
 * 不反写 element.updated_at（§11.14B）。
 */
@QuarkusTest
@DisplayName("ElementResource lastModifiedAt — task-0722 · B7.2")
class ElementLastModifiedAtTest {

    private static final String ELEM_CODE = "TEST-LMA-AG";
    private static final String ELEM_NO = "TESTNO-LMA";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_daily_price WHERE element_name = :c").setParameter("c", ELEM_CODE).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name = 'TEST-LMA-SRC'").executeUpdate();
        em.createNativeQuery("DELETE FROM element WHERE element_code = :c").setParameter("c", ELEM_CODE).executeUpdate();
        utx.commit();
    }

    @Test
    @DisplayName("导入一条价格后 lastModifiedAt 更新并排最前，但 element.updated_at 不变")
    void importUpdatesLastModifiedAtWithoutTouchingElementUpdatedAt() throws Exception {
        OffsetDateTime originalUpdatedAt = OffsetDateTime.now().minusDays(10);
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element (id, element_code, element_name, element_no, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, '测试银4', :no, 'ACTIVE', :ts, :ts)")
                .setParameter("c", ELEM_CODE).setParameter("no", ELEM_NO).setParameter("ts", originalUpdatedAt)
                .executeUpdate();
        utx.commit();

        String sourceId = given().contentType("application/json")
                .body("{\"sourceName\":\"TEST-LMA-SRC\",\"sourceUrl\":\"https://test.example/lma\"}")
            .when().post("/api/cpq/element-price/sources")
            .then().statusCode(200).extract().path("id");

        // 直接向价格写入器插入一条价格记录（updated_at=NOW()，晚于元素原 updated_at）
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :c, :s, :d, 5820.0000, 'CNY', '元/kg', 'IMPORT', NOW(), NOW())")
                .setParameter("c", ELEM_CODE).setParameter("s", UUID.fromString(sourceId)).setParameter("d", LocalDate.now())
                .executeUpdate();
        utx.commit();

        // 列表 lastModifiedAt 应约等于"刚才"（价格记录 updated_at），排最前
        given().queryParam("keyword", ELEM_CODE)
            .when().get("/api/cpq/elements")
            .then().statusCode(200)
                .body("[0].elementCode", equalTo(ELEM_CODE));

        // 直查 DB 确认 element.updated_at 未被反写（仍是原始值，未变为 NOW()）
        Object raw = em.createNativeQuery("SELECT updated_at FROM element WHERE element_code = :c")
                .setParameter("c", ELEM_CODE).getSingleResult();
        OffsetDateTime dbUpdatedAt = toOffsetDateTime(raw);
        assertNotNull(dbUpdatedAt);
        // 允许 JDBC 往返的微秒截断误差，但绝不应跳到"现在"（若被反写，两者相差会 < 1 分钟）
        long diffSeconds = Math.abs(java.time.Duration.between(originalUpdatedAt, dbUpdatedAt).getSeconds());
        org.junit.jupiter.api.Assertions.assertTrue(diffSeconds < 5,
                "element.updated_at 不应被价格导入反写；期望仍约等于原始值 " + originalUpdatedAt + "，实际 " + dbUpdatedAt);
    }

    private OffsetDateTime toOffsetDateTime(Object o) {
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("未知时间类型: " + (o == null ? "null" : o.getClass()));
    }
}
