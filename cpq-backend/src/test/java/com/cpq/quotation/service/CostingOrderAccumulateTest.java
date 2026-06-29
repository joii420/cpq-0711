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
 * CostingOrderAccumulateTest — T2 核价单累积模式 + 并发 409 保护验证。
 *
 * <p>resubmit_accumulates:
 * 同一报价单经历 submit→REJECTED→DRAFT→submit 后，findAllByQuotation 返回 2 条，
 * 两者 costingOrderNumber 不同，旧条 REJECTED 新条 PENDING。
 *
 * <p>secondActiveSubmit_maps409:
 * 同一报价单已有 PENDING 核价单时再次调 createForSubmission 应抛 409 BusinessException。
 */
@QuarkusTest
class CostingOrderAccumulateTest {

    @Inject
    QuotationService quotationService;

    @Inject
    CostingFreezeService costingFreezeService;

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

    // ── resubmit_accumulates ────────────────────────────────────────────────────

    @Test
    @Transactional
    void resubmit_accumulates() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        // 1. 建最小 DRAFT 报价单
        Quotation q = new Quotation();
        q.quotationNumber = "TEST-ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingOrderAccumulateTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID qid = q.id;

        // 2. 第一次 submit → 建 CostingOrder#1 PENDING，报价单进入 SUBMITTED
        quotationService.submit(qid, UUID.randomUUID());

        CostingOrder co1 = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(co1, "第一次 submit 后应有 active 核价单");
        assertEquals("PENDING", co1.status);
        String number1 = co1.costingOrderNumber;

        // 3. 模拟驳回 + 退回编辑：CostingOrder#1 → REJECTED，报价单 → DRAFT
        co1.status = "REJECTED";
        Quotation managedQ = Quotation.findById(qid);
        managedQ.status = "DRAFT";
        em.flush(); // 将变更写入事务内 DB，使后续 JPQL 查询见到最新值

        // 4. 第二次 submit → 建 CostingOrder#2 PENDING（uq_co_active 不阻断，因#1 已 REJECTED）
        quotationService.submit(qid, UUID.randomUUID());

        // 5. 全量历史应有 2 条
        List<CostingOrder> all = CostingOrder.findAllByQuotation(qid);
        assertEquals(2, all.size(), "两次 submit 后应有 2 条 costing_order 记录");

        // 6. 两条 number 不同
        String number2 = all.stream()
                .map(c -> c.costingOrderNumber)
                .filter(n -> !n.equals(number1))
                .findFirst()
                .orElse(null);
        assertNotNull(number2, "第二条核价单应有不同的 costingOrderNumber");
        assertNotEquals(number1, number2, "两次提交的 costingOrderNumber 应互不相同");

        // 7. 旧条仍 REJECTED，新条 PENDING
        CostingOrder active = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(active, "第二次 submit 后应有新的 active 核价单");
        assertEquals("PENDING", active.status, "新核价单 status 应为 PENDING");
        assertNotEquals(number1, active.costingOrderNumber, "新 active 核价单 number 应与旧条不同");

        // 旧条应为 REJECTED
        CostingOrder rejected = all.stream()
                .filter(c -> c.costingOrderNumber.equals(number1))
                .findFirst()
                .orElse(null);
        assertNotNull(rejected, "应能找到旧条（number=" + number1 + "）");
        assertEquals("REJECTED", rejected.status, "旧核价单 status 应仍为 REJECTED");
    }

    // ── secondActiveSubmit_maps409 ──────────────────────────────────────────────

    @Test
    @Transactional
    void secondActiveSubmit_maps409() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        // 1. 建最小 DRAFT 报价单并 submit → CostingOrder#1 进入 PENDING
        Quotation q = new Quotation();
        q.quotationNumber = "TEST-409-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingOrderAccumulateTest 409 Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        UUID qid = q.id;
        quotationService.submit(qid, UUID.randomUUID());

        // 确认已有 PENDING 核价单
        CostingOrder existing = CostingOrder.findActiveByQuotation(qid);
        assertNotNull(existing, "需要 submit 后有 PENDING 核价单");
        assertEquals("PENDING", existing.status);

        // 2. 同报价单再次 createForSubmission（跳过 submit 的 DRAFT 检查，直接触发 uq_co_active 冲突）
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> costingFreezeService.createForSubmission(qid, UUID.randomUUID()),
                "同报价单已有 active 核价单时 createForSubmission 应抛 409 BusinessException"
        );
        assertEquals(409, ex.getCode(), "异常 HTTP code 应为 409");
        assertNotNull(ex.getMessage(), "异常消息不应为 null");
        assertTrue(ex.getMessage().contains("进行中"), "错误消息应提示已存在进行中的核价单");
    }
}
