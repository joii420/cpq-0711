package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationApproval;
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
 * WithdrawUnfreezeTest — Task 5: 撤回放宽 + 解冻 + 废弃两步撤回。
 *
 * <p>测试撤回从 APPROVED 状态成功退回 DRAFT 并解冻快照；
 * 验证 SENT/ACCEPTED 状态不可撤回。
 */
@QuarkusTest
class WithdrawUnfreezeTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    // ── 辅助：从共享 DB 捞一个真实 customer ID ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM customer LIMIT 1")
                .getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    // ── 辅助：从共享 DB 捞一个真实 user ID ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" LIMIT 1")
                .getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    // ── 辅助：从 DB 查 SALES_REP 角色用户 ──────────────────────────────────────

    @SuppressWarnings("unchecked")
    private UUID salesUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'SALES_REP' LIMIT 1")
                .getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        // 若无，在当前事务内插入一个（随测试事务回滚）
        UUID sid = UUID.randomUUID();
        String suffix = sid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Sales', :email, 'SALES_REP', 'hash', now(), now())")
                .setParameter("id", sid)
                .setParameter("un", "test_sales_" + suffix)
                .setParameter("email", "test_sales_" + suffix + "@test.invalid")
                .executeUpdate();
        return sid;
    }

    // ── 辅助：获取或插入 PRICING_MANAGER 用户 ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private UUID financeUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1")
                .getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "test_finance_" + suffix)
                .setParameter("email", "test_finance_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    // ── 辅助：建一个 SUBMITTED 报价单并返回其 id（salesRepId 与 salesUserId() 对齐）─

    private UUID submittedQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = salesUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-WD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "WithdrawUnfreezeTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID submitUserId = UUID.randomUUID();
        quotationService.submit(q.id, submitUserId);
        return q.id;
    }

    // ── T1: 从 APPROVED 状态撤回成功，解冻快照 ─────────────────────────────────

    @Test
    @Transactional
    void withdraw_allowed_from_approved_and_unfreezes() {
        UUID qid = submittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        // 核价通过 → APPROVED
        quotationService.costingApprove(qid, null, finance);

        // 销售代表撤回
        UUID sales = salesUserId();
        quotationService.withdraw(qid, sales);

        Quotation q = Quotation.findById(qid);
        assertEquals("DRAFT", q.status, "撤回后状态应为 DRAFT");
        assertNull(q.submissionSnapshot, "撤回后 submissionSnapshot 应已解冻为 null");

        // SQL 视图闭包应已清空
        long snapCount = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM quotation_component_sql_snapshot WHERE quotation_id = :qid")
                .setParameter("qid", qid)
                .getSingleResult();
        assertEquals(0L, snapCount, "撤回后 quotation_component_sql_snapshot 应已清空");

        // 应写入 WITHDRAWN 审批记录
        long wdCount = QuotationApproval.count(
                "quotationId = ?1 AND action = ?2", qid, "WITHDRAWN");
        assertTrue(wdCount >= 1L, "撤回后应有 WITHDRAWN 审批记录");
    }

    // ── T2: SENT/ACCEPTED 状态不可撤回 ────────────────────────────────────────

    @Test
    @Transactional
    void withdraw_blocked_from_sent_accepted() {
        UUID qid = submittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        // 核价通过 → APPROVED
        quotationService.costingApprove(qid, null, finance);

        // 手动将状态改为 SENT（模拟已发送给客户）
        Quotation q = Quotation.findById(qid);
        q.status = "SENT";
        q.persist();
        em.flush();

        UUID sales = salesUserId();
        assertThrows(BusinessException.class,
                () -> quotationService.withdraw(qid, sales),
                "SENT 状态不可撤回，应抛 BusinessException");
    }
}
