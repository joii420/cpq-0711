package com.cpq.elementprice;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * task-0722 · B3 风险 2 专项 —— "缓存串价" 回归测试。
 *
 * <p>直接命中 {@code backtask.md} 要求的验证方法：两张<b>不同创建日期</b>的报价单，
 * 同一客户、同一料号（本测试甚至更严格：料号/partVersion 均为 null，只靠 quotationId 区分），
 * 让两个基准日在 {@code :priceBaseDate} 求值结果上不同 → 两次 {@code expand} 调用结果必须不同。
 *
 * <p>刻意复现最容易漏维度的路径：调用 4-arg {@code expand(componentId, customerId, null, null)}
 * （不带 lineItemId —— {@code ConfigureSnapshotService.precomputeQuoteDriverBuckets} 等路径的形态），
 * 若 {@link ComponentDriverService#cacheKey} 缺 quotationId 维度，第二次调用会在 30s TTL 内命中第一次的
 * 缓存条目，两次结果会"错误地相同"（等于第一张报价单的基准日）。
 */
@QuarkusTest
@DisplayName("task-0722 B3：expand 缓存 key 必须含 quotationId（防跨报价单串价）")
class PriceBaseDateCacheIsolationTest {

    private static final String TEST_VIEW_COMPONENT_CODE = "TEST-PRICEBASE-CACHE";
    private static final String TEST_VIEW_NAME = "test_pricebase_view";

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Inject
    ComponentDriverService componentDriverService;

    private static UUID componentId;
    private static UUID customerId;
    private static UUID quotationOld;   // created_at = 30 天前
    private static UUID quotationNew;   // created_at = 今天
    private static boolean seeded = false;

    private void seed() throws Exception {
        if (seeded) return;
        utx.begin();
        em.joinTransaction();

        // 清理可能的历史残留（同 code 复跑）
        em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_VIEW_COMPONENT_CODE).executeUpdate();

        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, status, component_type, " +
                "  data_driver_path, excel_columns, column_count, bom_recursive_expand, created_at, updated_at) " +
                "VALUES (:id, :name, :code, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', " +
                "  :dp, '[]'::jsonb, 0, false, NOW(), NOW())")
                .setParameter("id", componentId)
                .setParameter("name", "B3 缓存串价专项测试组件")
                .setParameter("code", TEST_VIEW_COMPONENT_CODE)
                .setParameter("dp", "$" + TEST_VIEW_NAME)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, " +
                "  declared_columns, required_variables, scope, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :vn, :tpl, '[]'::jsonb, " +
                "  ARRAY['priceBaseDate'], 'COMPONENT', 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", componentId)
                .setParameter("vn", TEST_VIEW_NAME)
                .setParameter("tpl", "SELECT :priceBaseDate::text AS pricebasedate")
                .executeUpdate();

        utx.commit();

        // 客户 + 两张报价单走真实 REST 创建（与既有测试同款模式），再原生 SQL 改 created_at 制造"不同创建日期"
        customerId = UUID.fromString(RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"B3缓存串价测试客户","level":"STANDARD",
                         "contacts":[{"name":"测试联系人","phone":"13900000099","isPrimary":true}]}
                        """)
                .post("/api/cpq/customers")
                .then().statusCode(200)
                .extract().path("data.id").toString());

        quotationOld = createQuotation("B3缓存串价-旧单");
        quotationNew = createQuotation("B3缓存串价-新单");

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("UPDATE quotation SET created_at = :d WHERE id = :id")
                .setParameter("d", OffsetDateTime.now().minusDays(30))
                .setParameter("id", quotationOld)
                .executeUpdate();
        // quotationNew 保持 REST 创建时的 created_at（今天），不改
        utx.commit();

        seeded = true;
    }

    private UUID createQuotation(String name) {
        String id = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"customerId\":\"%s\",\"name\":\"%s\"}".formatted(customerId, name))
                .post("/api/cpq/quotations")
                .then().statusCode(200)
                .extract().path("data.id");
        return UUID.fromString(id);
    }

    @Test
    @DisplayName("T1: 4-arg expand（无 lineItemId）在两张不同创建日期的报价单间不串价")
    void expandDoesNotBleedAcrossQuotations() throws Exception {
        seed();

        // 第一次调用：旧单上下文（30 天前创建）
        QuotationIdContext.set(quotationOld);
        String pricebaseOld;
        try {
            ExpandDriverResponse r1 = componentDriverService.expand(componentId, customerId, null, null);
            assertEquals(1, r1.rowCount, "测试视图恒 1 行");
            pricebaseOld = (String) r1.rows.get(0).driverRow.get("pricebasedate");
        } finally {
            QuotationIdContext.clear();
        }

        // 第二次调用：新单上下文（今天创建）——同 componentId/customerId/partNo(null)/partVersion(null)，
        // 30s TTL 内若 cache key 缺 quotationId 维度，会直接命中第一次缓存条目返回旧单的日期。
        QuotationIdContext.set(quotationNew);
        String pricebaseNew;
        try {
            ExpandDriverResponse r2 = componentDriverService.expand(componentId, customerId, null, null);
            assertEquals(1, r2.rowCount);
            pricebaseNew = (String) r2.rows.get(0).driverRow.get("pricebasedate");
        } finally {
            QuotationIdContext.clear();
        }

        assertNotNull(pricebaseOld);
        assertNotNull(pricebaseNew);
        assertNotEquals(pricebaseOld, pricebaseNew,
                "串价！旧单与新单的 :priceBaseDate 求值结果相同，说明 expandCache key 缺 quotationId 维度");

        // 精确到"日"的比较容易受 JVM 默认时区 vs DB timestamptz 边界舍入影响（±1 天），
        // 核心待证性质是"旧单基准日显著早于新单基准日"（相差近 30 天），而非逐字节相等。
        java.time.LocalDate dOld = java.time.LocalDate.parse(pricebaseOld);
        java.time.LocalDate dNew = java.time.LocalDate.parse(pricebaseNew);
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(dOld, dNew);
        org.junit.jupiter.api.Assertions.assertTrue(daysBetween >= 28 && daysBetween <= 31,
                "旧单基准日应比新单早约 30 天，实际相差 " + daysBetween + " 天（old=" + pricebaseOld + " new=" + pricebaseNew + "）");
    }
}
