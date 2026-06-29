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
 * CostingOrderLifecycleTest — T2 核价单冻结 DTO 验证。
 *
 * <p>submit_createsPendingOrder_withFrozenDto:
 * <ul>
 *   <li>DRAFT 报价单提交后 findActiveByQuotation 返回一条 PENDING 核价单</li>
 *   <li>costingOrderNumber 以 "HJ-" 开头</li>
 *   <li>frozenDto 非空且包含 "costingCardStructure" 与 "gvDefs" 键</li>
 *   <li>totalAmount 已填（>= 0）</li>
 * </ul>
 */
@QuarkusTest
class CostingOrderLifecycleTest {

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

    @Test
    @Transactional
    void submit_createsPendingOrder_withFrozenDto() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        // 1. 建最小 DRAFT 报价单
        Quotation q = new Quotation();
        q.quotationNumber = "TEST-LF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingOrderLifecycleTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID qid = q.id;
        UUID submitUserId = UUID.randomUUID();

        // 2. submit
        quotationService.submit(qid, submitUserId);

        // 3. 验证 active 核价单
        CostingOrder co = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(co, "submit 后 findActiveByQuotation 应返回一条 PENDING 核价单");

        // 3a. status = PENDING
        assertEquals("PENDING", co.status, "新建核价单 status 应为 PENDING");

        // 3b. costingOrderNumber 以 HJ- 开头
        assertNotNull(co.costingOrderNumber, "costingOrderNumber 不得为 null");
        assertTrue(co.costingOrderNumber.startsWith("HJ-"),
                "costingOrderNumber 应以 HJ- 开头，实际：" + co.costingOrderNumber);

        // 3c. frozenDto 非空且包含关键字段
        assertNotNull(co.frozenDto, "frozenDto 不得为 null");
        assertFalse(co.frozenDto.isBlank(), "frozenDto 不得为空串");
        assertTrue(co.frozenDto.contains("costingCardStructure"),
                "frozenDto 应包含 costingCardStructure 键");
        assertTrue(co.frozenDto.contains("gvDefs"),
                "frozenDto 应包含 gvDefs 键");

        // 3d. totalAmount 已填（DRAFT 报价单无明细时为 0，不应为 null）
        assertNotNull(co.totalAmount, "totalAmount 不得为 null");

        // 3e. submittedBy
        assertEquals(submitUserId, co.submittedBy, "submittedBy 应等于传入的 userId");

        // 3f. 时间戳由 @PrePersist 填充
        assertNotNull(co.enteredCostingAt, "enteredCostingAt 不得为 null");
        assertNotNull(co.createdAt, "createdAt 不得为 null");
    }
}
