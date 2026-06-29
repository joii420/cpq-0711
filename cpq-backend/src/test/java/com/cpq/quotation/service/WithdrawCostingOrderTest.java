package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
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
 * WithdrawCostingOrderTest — 核价单状态机 Task 3。
 *
 * <p>验证 withdraw 路径下核价单 status 同步为 WITHDRAWN；
 * 以及撤回后再提交不抛 409（T2 评审 bug 修复验证）。
 */
@QuarkusTest
class WithdrawCostingOrderTest {

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
                "VALUES(:id, :un, 'Test Finance W', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "test_finance_w_" + suffix)
                .setParameter("email", "test_finance_w_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    /** 建一个最小 Quotation，salesRepId 设为指定用户（作为 withdraw 权限主体）。 */
    private UUID buildAndSubmit(UUID salesRepId) {
        UUID customerId = resolveCustomerId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-WD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "WithdrawCostingOrderTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();

        quotationService.submit(q.id, UUID.randomUUID());
        return q.id;
    }

    // ── T1: withdraw from SUBMITTED → quotation=DRAFT, costing=WITHDRAWN ────────

    @Test
    @Transactional
    void withdraw_fromSubmitted_costingWithdrawn() {
        UUID salesRepId = resolveUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        UUID qid = buildAndSubmit(salesRepId);

        // 撤回
        quotationService.withdraw(qid, salesRepId);

        Quotation q = Quotation.findById(qid);
        assertEquals("DRAFT", q.status, "撤回后报价单应为 DRAFT");

        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "应存在核价单记录");
        assertEquals("WITHDRAWN", co.status, "撤回后最新核价单 status 应为 WITHDRAWN");
    }

    // ── T2: withdraw from COSTING_REJECTED → 不抛 NPE，costing=WITHDRAWN ────────

    @Test
    @Transactional
    void withdraw_fromRejected_noNpe_costingWithdrawn() {
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        UUID qid = buildAndSubmit(salesRepId);

        // 财务驳回
        quotationService.costingReject(qid, "成本偏高", finance);

        // 撤回（COSTING_REJECTED → DRAFT）
        assertDoesNotThrow(() -> quotationService.withdraw(qid, salesRepId),
                "withdraw from COSTING_REJECTED 不应抛 NPE");

        Quotation q = Quotation.findById(qid);
        assertEquals("DRAFT", q.status, "撤回后报价单应为 DRAFT");

        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "应存在核价单记录");
        assertEquals("WITHDRAWN", co.status, "驳回后撤回，最新核价单 status 应为 WITHDRAWN");
    }

    // ── T3: withdraw from APPROVED → quotation=DRAFT, costing=WITHDRAWN ─────────

    @Test
    @Transactional
    void withdraw_fromApproved_costingWithdrawn() {
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        UUID qid = buildAndSubmit(salesRepId);

        // 财务通过
        quotationService.costingApprove(qid, "ok", finance);

        // 撤回
        quotationService.withdraw(qid, salesRepId);

        Quotation q = Quotation.findById(qid);
        assertEquals("DRAFT", q.status, "撤回后报价单应为 DRAFT");

        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "应存在核价单记录");
        assertEquals("WITHDRAWN", co.status, "通过后撤回，最新核价单 status 应为 WITHDRAWN");
    }

    // ── T4: withdraw then resubmit → 不抛 409（修复 T2 评审 bug）───────────────

    @Test
    @Transactional
    void withdrawThenResubmit_no409() {
        UUID salesRepId = resolveUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        UUID qid = buildAndSubmit(salesRepId);

        // 撤回
        quotationService.withdraw(qid, salesRepId);

        // 再次提交，不应抛 409
        assertDoesNotThrow(() -> quotationService.submit(qid, UUID.randomUUID()),
                "撤回后再次提交不应抛 409（uq_co_active 唯一约束不应被 WITHDRAWN 触发）");

        // 验证累积：共 2 条核价单，最新为 PENDING，旧条为 WITHDRAWN
        List<CostingOrder> all = CostingOrder.findAllByQuotation(qid);
        assertEquals(2, all.size(), "撤回+重提后应共有 2 条核价单");

        CostingOrder active = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(active, "再次提交后应有 active 核价单");
        assertEquals("PENDING", active.status, "再提交的核价单 status 应为 PENDING");

        // 旧条为 WITHDRAWN
        long withdrawnCount = all.stream().filter(c -> "WITHDRAWN".equals(c.status)).count();
        assertEquals(1L, withdrawnCount, "应有 1 条 WITHDRAWN 核价单");
    }
}
