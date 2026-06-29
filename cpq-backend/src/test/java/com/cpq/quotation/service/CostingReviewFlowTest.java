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
 * CostingReviewFlowTest — 核价管理·财务核价工作台 第一期 Task 2。
 *
 * <p>用例：submit_creates_costing_order
 * — DRAFT 报价单提交后自动幂等建 CostingOrder。
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

    // ── 主测试：submit 自动建核价单 ──────────────────────────────────────────────

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
        // 前置：需要共享 DB 中存在 customer 和 user 行（外键约束）
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        // 1. 构造最小 DRAFT 报价单（status/taxRate/taxAmount 有 Java 默认值）
        Quotation q = new Quotation();
        q.quotationNumber = "TEST-CR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingReviewFlowTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID qid = q.id;
        UUID submitUserId = UUID.randomUUID(); // 模拟提交操作用户（不必是真实 user）

        // 2. 直调 service.submit（含 userId）
        quotationService.submit(qid, submitUserId);

        // 3. 断言核价单已建
        CostingOrder co = CostingOrder.findByQuotation(qid);
        assertNotNull(co, "submit 后 costing_order 应已创建");
        assertEquals(submitUserId, co.submittedBy, "submittedBy 应等于传入的 userId");
        assertNotNull(co.enteredCostingAt, "enteredCostingAt 不得为 null（@PrePersist 填充）");
        assertNotNull(co.createdAt, "createdAt 不得为 null（@PrePersist 填充）");

        // 4. 幂等：同一报价单仅建一条
        long count = CostingOrder.count("quotationId", qid);
        assertEquals(1L, count, "幂等：同一报价单仅建一条 costing_order");
    }
}
