package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * BL-0092 孤儿 pending 清理的行为测试。
 *
 * <p>最关键的一条是 {@link #orphanCleaned_liveKept()}：本服务是 DELETE 语义、横跨 9 张基础资料表，
 * <b>误删活数据的代价远高于漏删孤儿</b>，因此每个用例都同时断言"孤儿被删"与"非孤儿仍在"。
 *
 * <p>测试数据用 {@code BL0092-} 前缀隔离，{@code @AfterEach} 无条件清理，
 * 不依赖测试是否通过（失败时也不留脏数据到共享测试库）。
 */
@QuarkusTest
@DisplayName("BL-0092 孤儿 pending 清理")
class PendingHygieneServiceTest {

    private static final String PFX = "BL0092-";

    @Inject
    PendingHygieneService service;

    @Inject
    EntityManager em;

    /** 真实存在的报价单 id —— 挂在它下面的 pending 行不是孤儿，必须被保留。 */
    private UUID liveQuotationId;
    /** 从未存在过的报价单 id —— 挂在它下面的 pending 行是孤儿，必须被清理。 */
    private UUID deadQuotationId;

    @BeforeEach
    @Transactional
    void setUp() {
        cleanupTestData();
        liveQuotationId = UUID.randomUUID();
        deadQuotationId = UUID.randomUUID();

        // customer_id / sales_rep_id 带外键约束，必须引用库中真实行（不能用随机 UUID）
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, "
                + " created_at, updated_at, tax_rate, tax_amount, bound_global_variables_snapshot) "
                + "SELECT ?1, ?2, (SELECT id FROM customer ORDER BY created_at LIMIT 1), ?3, "
                + "       (SELECT id FROM \"user\" ORDER BY created_at LIMIT 1), 'DRAFT', "
                + "       NOW(), NOW(), 0, 0, '{}'")
            .setParameter(1, liveQuotationId)
            .setParameter(2, PFX + "LIVE-" + System.currentTimeMillis())
            .setParameter(3, PFX + "live-quotation")
            .executeUpdate();

        // material_customer_map：一条挂活单（应保留）、一条挂死单（应清理）
        insertMcm(PFX + "MAT-LIVE", liveQuotationId);
        insertMcm(PFX + "MAT-DEAD", deadQuotationId);
        // 再来一条 pending_quotation_id 为 NULL 的正式行（应保留，且不该被本服务碰）
        insertMcm(PFX + "MAT-FORMAL", null);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        cleanupTestData();
    }

    @Test
    @DisplayName("孤儿被清理，挂活单的与正式行都保留")
    void orphanCleaned_liveKept() {
        long orphansBefore = countMcm(PFX + "MAT-DEAD");
        assertEquals(1, orphansBefore, "前置：死单 pending 行应存在");

        PendingHygieneService.CleanupResult r = service.cleanup(false);

        assertEquals(0, countMcm(PFX + "MAT-DEAD"), "挂死单的孤儿必须被清理");
        assertEquals(1, countMcm(PFX + "MAT-LIVE"), "挂活单的 pending 行必须保留 —— 误删活数据是本服务最严重的失败模式");
        assertEquals(1, countMcm(PFX + "MAT-FORMAL"), "pending_quotation_id 为 NULL 的正式行不在清理范围内");
        assertTrue(r.totalDeleted() >= 1, "清理结果应至少包含本用例造的 1 条孤儿");
    }

    @Test
    @DisplayName("dryRun 只统计不删除")
    void dryRun_countsButDeletesNothing() {
        PendingHygieneService.CleanupResult r = service.cleanup(true);

        assertTrue(r.dryRun(), "返回结果应标记为 dryRun");
        assertTrue(r.totalDeleted() >= 1, "dryRun 应报告将要删除的条数");
        assertEquals(1, countMcm(PFX + "MAT-DEAD"), "dryRun 不得真的删除任何行");
        assertEquals(1, countMcm(PFX + "MAT-LIVE"), "dryRun 不得影响活数据");
    }

    @Test
    @DisplayName("体检只读，且能报出各表孤儿数")
    void inspect_isReadOnly() {
        PendingHygieneService.InspectResult r = service.inspect();

        assertTrue(r.totalOrphans() >= 1, "体检应至少发现本用例造的 1 条孤儿");
        assertTrue(r.orphanCountByTable().containsKey("material_customer_map"), "体检结果应逐表列出");
        assertEquals(1, countMcm(PFX + "MAT-DEAD"), "体检不得修改任何数据");
        assertEquals(1, countMcm(PFX + "MAT-LIVE"), "体检不得修改任何数据");
    }

    @Test
    @DisplayName("管理清单覆盖全部带 pending_quotation_id 的表（新增表会在此暴露）")
    void noUnmanagedPendingTables() {
        PendingHygieneService.InspectResult r = service.inspect();

        assertTrue(r.unmanagedTables().isEmpty(),
                "存在未纳入清理清单的 pending 表：" + r.unmanagedTables()
                + " —— 新增带 pending_quotation_id 的表时，必须同步 "
                + "PendingHygieneService.PENDING_TABLES 与 QuotationService.B8_PENDING_TABLES");
    }

    // ------------------------------------------------------------------

    private void insertMcm(String materialNo, UUID pendingQuotationId) {
        em.createNativeQuery(
                "INSERT INTO material_customer_map (id, material_no, customer_no, system_type, "
                + " pending_quotation_id, created_at, updated_at) "
                + "VALUES (?1, ?2, 'BL0092-CUST', 'QUOTE', ?3, NOW(), NOW())")
            .setParameter(1, UUID.randomUUID())
            .setParameter(2, materialNo)
            .setParameter(3, pendingQuotationId)
            .executeUpdate();
    }

    private long countMcm(String materialNo) {
        Object n = em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE material_no = ?1")
            .setParameter(1, materialNo)
            .getSingleResult();
        return ((Number) n).longValue();
    }

    private void cleanupTestData() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no LIKE ?1")
            .setParameter(1, PFX + "%").executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE name LIKE ?1")
            .setParameter(1, PFX + "%").executeUpdate();
    }
}
