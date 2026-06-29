package com.cpq.quotation.service;

import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CostingStatusSyncTest — 核价单状态机 Task 3。
 *
 * <p>验证 costingApprove / costingReject 调用时，costing_order 记录的
 * status / reviewedBy / reviewedAt / rejectReason 正确同步。
 */
@QuarkusTest
class CostingStatusSyncTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID financeUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1").getResultList();
        if (!rows.isEmpty()) return UUID.fromString(rows.get(0).toString());
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Finance SS', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "test_finance_ss_" + suffix)
                .setParameter("email", "test_finance_ss_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    private UUID buildSubmittedQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-SS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingStatusSyncTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();

        quotationService.submit(q.id, UUID.randomUUID());
        return q.id;
    }

    // ── T1: costingApprove 后 costing_order.status=APPROVED, reviewedBy/At 已填 ──

    @Test
    @Transactional
    void costingApprove_syncsActiveCostingOrder() {
        UUID qid = buildSubmittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        quotationService.costingApprove(qid, "ok", finance);

        // findActiveByQuotation：APPROVED 也是 active
        CostingOrder co = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(co, "costingApprove 后应仍有 active 核价单（status=APPROVED）");
        assertEquals("APPROVED", co.status, "核价单 status 应同步为 APPROVED");
        assertEquals(finance, co.reviewedBy, "reviewedBy 应等于操作用户");
        assertNotNull(co.reviewedAt, "reviewedAt 不得为 null");
        assertNotNull(co.updatedAt, "@PreUpdate 应已更新 updatedAt");
    }

    // ── T2: costingReject 后 costing_order.status=REJECTED, rejectReason/reviewedBy 已填 ──

    @Test
    @Transactional
    void costingReject_syncsActiveCostingOrder() {
        UUID qid = buildSubmittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        String reason = "测试驳回原因";
        quotationService.costingReject(qid, reason, finance);

        // 驳回后 active = 0（REJECTED 不在 active 集合），用 findLatest 取
        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "应存在核价单记录");
        assertEquals("REJECTED", co.status, "核价单 status 应同步为 REJECTED");
        assertEquals(reason, co.rejectReason, "rejectReason 应同步");
        assertEquals(finance, co.reviewedBy, "reviewedBy 应等于操作用户");
        assertNotNull(co.reviewedAt, "reviewedAt 不得为 null");
        assertNotNull(co.updatedAt, "@PreUpdate 应已更新 updatedAt");
    }
}
