package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationApproval;
import com.cpq.quotation.dto.QuotationDTO;
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
 * CostingReviewFlowTest — 核价管理·财务核价工作台 第一期。
 *
 * <p>T1/T2: submit_creates_costing_order — DRAFT 报价单提交后自动幂等建 CostingOrder。
 * <p>T3: costingApprove_by_any_finance_not_just_assignee
 * <p>T4: costingReject_requires_reason_and_sets_status
 * <p>T5: costingApprove_forbidden_for_sales
 *
 * <p>夹具策略：用 native SQL 查真实 customer + user ID（外键约束合规），
 * 在 @Transactional 内建最小 Quotation + 调 submit；
 * 测试结束回滚，不污染共享 DB。
 */
@QuarkusTest
class CostingReviewFlowTest {

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
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    // ── 辅助：获取或插入 PRICING_MANAGER 用户（事务内插入，随回滚清除）──────────

    @SuppressWarnings("unchecked")
    private UUID financeUserId() {
        // 先从 DB 查现有 PRICING_MANAGER
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1")
                .getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        // 若无，在当前事务内插入一个（随测试事务回滚，不污染 DB）
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

    // ── 辅助：建一个 SUBMITTED 报价单并返回其 id ─────────────────────────────────

    private UUID submittedQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-CR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingReviewFlowTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID submitUserId = UUID.randomUUID();
        quotationService.submit(q.id, submitUserId);
        return q.id;
    }

    // ── T1/T2: submit 自动建核价单 ───────────────────────────────────────────────

    /**
     * 验证：
     * 1. submit 后 CostingOrder 已建
     * 2. submittedBy == 传入的 userId
     * 3. enteredCostingAt / createdAt 由 @PrePersist 填充，不为 null
     * 4. 幂等：同一 quotationId 仅 1 条
     */
    @Test
    @Transactional
    void submit_creates_costing_order() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-CR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingReviewFlowTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID qid = q.id;
        UUID submitUserId = UUID.randomUUID();

        quotationService.submit(qid, submitUserId);

        CostingOrder co = CostingOrder.findByQuotation(qid);
        assertNotNull(co, "submit 后 costing_order 应已创建");
        assertEquals(submitUserId, co.submittedBy, "submittedBy 应等于传入的 userId");
        assertNotNull(co.enteredCostingAt, "enteredCostingAt 不得为 null（@PrePersist 填充）");
        assertNotNull(co.createdAt, "createdAt 不得为 null（@PrePersist 填充）");

        long count = CostingOrder.count("quotationId", qid);
        assertEquals(1L, count, "幂等：同一报价单仅建一条 costing_order");
    }

    // ── T3: 任一财务均可核价通过（不校验 assignedApproverId）─────────────────────

    @Test
    @Transactional
    void costingApprove_by_any_finance_not_just_assignee() {
        UUID qid = submittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        QuotationDTO dto = quotationService.costingApprove(qid, "ok", finance);

        assertEquals("APPROVED", dto.status);
        assertEquals(1L,
                QuotationApproval.count("quotationId = ?1 AND action = ?2", qid, "COSTING_APPROVED"),
                "应写入 COSTING_APPROVED 审批记录");
    }

    // ── T4: 驳回必填原因，且状态变为 COSTING_REJECTED ──────────────────────────

    @Test
    @Transactional
    void costingReject_requires_reason_and_sets_status() {
        UUID qid = submittedQuotation();
        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        // 空原因应抛 BusinessException
        assertThrows(BusinessException.class,
                () -> quotationService.costingReject(qid, "  ", finance),
                "驳回原因为空时应抛异常");

        // 恢复状态（上一步异常后事务可能已修改 status，需从 DB 重读）
        // 因为 assertThrows 在同一事务内，抛异常后 Panache 托管实体可能已 mark rollback-only
        // 但 @Transactional 测试整体事务未提交，状态仍为 SUBMITTED（BusinessException 是非 checked 不会触发 setRollbackOnly）
        QuotationDTO dto = quotationService.costingReject(qid, "成本偏高", finance);
        assertEquals("COSTING_REJECTED", dto.status);
        assertEquals(1L,
                QuotationApproval.count("quotationId = ?1 AND action = ?2", qid, "COSTING_REJECTED"),
                "应写入 COSTING_REJECTED 审批记录");
    }

    // ── T5: SALES_REP 无权核价通过 ───────────────────────────────────────────────

    @Test
    @Transactional
    void costingApprove_forbidden_for_sales() {
        UUID qid = submittedQuotation();
        UUID sales = salesUserId();
        assumeTrue(sales != null, "需要共享 DB 中存在 SALES_REP 用户");

        assertThrows(BusinessException.class,
                () -> quotationService.costingApprove(qid, null, sales),
                "SALES_REP 调用 costingApprove 应抛 403 异常");
    }
}
