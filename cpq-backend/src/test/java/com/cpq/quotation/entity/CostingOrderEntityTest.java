package com.cpq.quotation.entity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CostingOrderEntityTest — V305 核价单完整实体集成测试。
 * <p>
 * 直接用 EntityManager + 原生 SQL seed（绕过 REST 认证依赖链），验证：
 * <ul>
 *   <li>partialUniqueIndex_blocksSecondActive：同一 quotationId 连插两条 PENDING → 第二条撞 uq_co_active</li>
 *   <li>terminalOrders_coexist：REJECTED + WITHDRAWN + PENDING 三条共存；findAllByQuotation>=3；findActiveByQuotation=PENDING</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CostingOrderEntityTest {

    @Inject
    EntityManager em;

    /** 跨测试共享的报价单 ID（由 Test 1 建，Test 2 复用 sharedQid） */
    private static UUID sharedQid;

    // ── 辅助 seed：原生 SQL，无 REST 依赖 ──────────────────────────────────────

    /**
     * 在同一事务内插入最小化 user + customer + quotation，返回 quotation id。
     * 每次调用生成唯一 quotation_number 和 user.username，避免 UNIQUE 冲突。
     */
    @Transactional
    UUID seedQuotation(String suffix) {
        long ts = System.currentTimeMillis();
        String tag = suffix + "_" + ts;

        // 1) user
        UUID userId = (UUID) em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role) " +
                "VALUES (gen_random_uuid(), :uname, 'Test User', :email, 'x', 'SALES_REP') RETURNING id")
                .setParameter("uname", "co_test_" + tag)
                .setParameter("email", "co_test_" + tag + "@test.local")
                .getSingleResult();

        // 2) customer
        UUID custId = (UUID) em.createNativeQuery(
                "INSERT INTO customer(id, name, code) " +
                "VALUES (gen_random_uuid(), :cname, :code) RETURNING id")
                .setParameter("cname", "CO Test Cust " + tag)
                .setParameter("code", "CO_" + tag.substring(Math.max(0, tag.length() - 12)))
                .getSingleResult();

        // 3) quotation
        UUID qid = (UUID) em.createNativeQuery(
                "INSERT INTO quotation(id, quotation_number, customer_id, name, sales_rep_id) " +
                "VALUES (gen_random_uuid(), :qnum, :cid, 'CO Entity Test', :uid) RETURNING id")
                .setParameter("qnum", "QT-CO-" + tag)
                .setParameter("cid", custId)
                .setParameter("uid", userId)
                .getSingleResult();

        return qid;
    }

    /** 构建一条 CostingOrder，不含 id（由 Hibernate 生成） */
    private CostingOrder buildOrder(UUID quotationId, String status, String number) {
        CostingOrder co = new CostingOrder();
        co.quotationId = quotationId;
        co.status = status;
        co.costingOrderNumber = number;
        co.enteredCostingAt = OffsetDateTime.now();
        co.createdAt = OffsetDateTime.now();
        co.updatedAt = OffsetDateTime.now();
        return co;
    }

    /** 独立事务 persist + flush */
    @Transactional
    void persistInTx(CostingOrder co) {
        em.persist(co);
        em.flush();
    }

    // ── TEST 1 ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("partialUniqueIndex_blocksSecondActive: 同 quotationId 两条 PENDING 第二条撞 uq_co_active")
    void partialUniqueIndex_blocksSecondActive() {
        UUID activeQid = seedQuotation("active");

        // 第一条 PENDING → 应成功
        persistInTx(buildOrder(activeQid, "PENDING", "HJ-TEST-ACTIVE-001-" + System.currentTimeMillis()));

        // 第二条 PENDING → 应因 uq_co_active 部分唯一索引抛异常
        long ts2 = System.currentTimeMillis() + 1;
        assertThrows(Exception.class, () ->
                persistInTx(buildOrder(activeQid, "PENDING", "HJ-TEST-ACTIVE-002-" + ts2)),
                "应抛异常（uq_co_active 部分唯一索引阻止同 quotationId 第二条 PENDING）"
        );
    }

    // ── TEST 2 ────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("terminalOrders_coexist: REJECTED+WITHDRAWN+PENDING 三条共存，findActiveByQuotation=PENDING")
    void terminalOrders_coexist() {
        sharedQid = seedQuotation("shared");
        long ts = System.currentTimeMillis();

        CostingOrder rejected  = buildOrder(sharedQid, "REJECTED",  "HJ-TST-R-" + ts);
        CostingOrder withdrawn = buildOrder(sharedQid, "WITHDRAWN", "HJ-TST-W-" + ts);
        CostingOrder pending   = buildOrder(sharedQid, "PENDING",   "HJ-TST-P-" + ts);

        // 终态行不占 uq_co_active 谓词，三条都应插入成功
        persistInTx(rejected);
        persistInTx(withdrawn);
        persistInTx(pending);

        // findAllByQuotation 返 >= 3（测试专用报价单，应恰好 3 条）
        List<CostingOrder> all = CostingOrder.findAllByQuotation(sharedQid);
        assertTrue(all.size() >= 3, "应能查到至少 3 条核价单，实际=" + all.size());

        // findActiveByQuotation 返那条 PENDING
        CostingOrder active = CostingOrder.findActiveByQuotation(sharedQid);
        assertNotNull(active, "应能找到 active 核价单");
        assertEquals("PENDING", active.status, "active 核价单状态应为 PENDING");
    }
}
