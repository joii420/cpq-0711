package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
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
 * BeginEditTest — 核价单状态机 Task 3。
 *
 * <p>验证 beginEdit 端点：
 * 1. COSTING_REJECTED → DRAFT；costing_order 留 REJECTED（不改）；submissionSnapshot 清空。
 * 2. 非 COSTING_REJECTED 状态调 beginEdit 抛 400。
 * 3. 非创建人/非管理员调 beginEdit 抛 403。
 */
@QuarkusTest
class BeginEditTest {

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
                "VALUES(:id, :un, 'Test Finance BE', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "test_finance_be_" + suffix)
                .setParameter("email", "test_finance_be_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    /** 插入一个随机 SALES_REP 用户（当前事务内，随测试回滚）。 */
    private UUID insertSalesRepUser() {
        UUID uid = UUID.randomUUID();
        String suffix = uid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Sales BE', :email, 'SALES_REP', 'hash', now(), now())")
                .setParameter("id", uid)
                .setParameter("un", "test_sales_be_" + suffix)
                .setParameter("email", "test_sales_be_" + suffix + "@test.invalid")
                .executeUpdate();
        return uid;
    }

    /** 建 COSTING_REJECTED 状态的报价单，salesRepId 可控。 */
    private UUID buildRejectedQuotation(UUID salesRepId) {
        UUID customerId = resolveCustomerId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-BE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "BeginEditTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();

        quotationService.submit(q.id, UUID.randomUUID());
        quotationService.costingReject(q.id, "成本偏高", finance);
        return q.id;
    }

    // ── T1: COSTING_REJECTED → DRAFT；costing 仍 REJECTED；submissionSnapshot 清空 ──

    @Test
    @Transactional
    void beginEdit_fromRejected_toDraft_costingUnchanged() {
        UUID salesRepId = resolveUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        UUID qid = buildRejectedQuotation(salesRepId);

        QuotationDTO dto = quotationService.beginEdit(qid, salesRepId);

        // 报价单状态应为 DRAFT
        assertEquals("DRAFT", dto.status, "beginEdit 后报价单状态应为 DRAFT");

        // 确认数据库层面也已更新
        Quotation q = Quotation.findById(qid);
        assertEquals("DRAFT", q.status);
        assertNull(q.submissionSnapshot, "beginEdit 后 submissionSnapshot 应已清空");

        // costing_order 应仍为 REJECTED（不被 beginEdit 修改）
        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "核价单记录应存在");
        assertEquals("REJECTED", co.status, "beginEdit 不修改 costing_order，应仍为 REJECTED");
    }

    // ── T2: 非 COSTING_REJECTED 状态调 beginEdit → 400 ─────────────────────────

    @Test
    @Transactional
    void beginEdit_fromNonRejected_400() {
        UUID salesRepId = resolveUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        UUID customerId = resolveCustomerId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-BE-NR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "BeginEditTest NonRejected";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();

        // submit → SUBMITTED 状态
        quotationService.submit(q.id, UUID.randomUUID());

        // 此时 status=SUBMITTED，beginEdit 应抛 400
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.beginEdit(q.id, salesRepId),
                "SUBMITTED 状态调 beginEdit 应抛 400");
        assertEquals(400, ex.getCode(), "异常 HTTP code 应为 400");
    }

    // ── T3: 非创建人/非管理员 → 403 ─────────────────────────────────────────────

    @Test
    @Transactional
    void beginEdit_byNonOwner_403() {
        UUID salesRepId = resolveUserId();
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        UUID qid = buildRejectedQuotation(salesRepId);

        // 用另一个随机 SALES_REP（非 owner）
        UUID otherUser = insertSalesRepUser();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.beginEdit(qid, otherUser),
                "非创建人调 beginEdit 应抛 403");
        assertEquals(403, ex.getCode(), "异常 HTTP code 应为 403");
    }
}
