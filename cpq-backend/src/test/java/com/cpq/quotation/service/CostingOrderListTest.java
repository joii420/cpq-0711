package com.cpq.quotation.service;

import com.cpq.quotation.dto.CostingOrderListItemDTO;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CostingOrderListTest — 核价管理列表端点 Task 4。
 *
 * <p>T1: list_shows_submitted_and_approved_with_derived_status
 *        — SUBMITTED → 待核价；approve 后 → 核价通过。
 * <p>T2: list_filters_by_status
 *        — status=待核价 时只返回 SUBMITTED 对应的行。
 *
 * <p>夹具策略：复用 CostingReviewFlowTest 相同 helper 逻辑（resolveCustomerId /
 * resolveUserId / financeUserId / submittedQuotation）。
 * 测试在同一 @Transactional 事务内完成，结束后自动回滚，不污染共享 DB。
 */
@QuarkusTest
class CostingOrderListTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    // ── helpers ───────────────────────────────────────────────────────────────

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
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1")
                .getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        // 当前事务内插入，随回滚清除
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "colt_finance_" + suffix)
                .setParameter("email", "colt_finance_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    /**
     * 建一个 SUBMITTED 报价单并返回其 id。
     * 复用与 CostingReviewFlowTest#submittedQuotation 相同逻辑。
     */
    private UUID submittedQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-CL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "CostingOrderListTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        quotationService.submit(q.id, UUID.randomUUID());
        return q.id;
    }

    // ── T1: 派生状态 ──────────────────────────────────────────────────────────

    @Test
    @Transactional
    void list_shows_submitted_and_approved_with_derived_status() {
        UUID a = submittedQuotation();                                // → 待核价
        UUID b = submittedQuotation();
        quotationService.costingApprove(b, null, financeUserId());   // → 核价通过

        List<CostingOrderListItemDTO> list = quotationService.listCostingOrders(null, null);

        // 过滤出本测试创建的两条（其它测试产生的老数据不干扰断言，按 quotationId 精确查）
        Map<UUID, String> byId = list.stream()
                .collect(Collectors.toMap(x -> x.quotationId, x -> x.status));

        assertTrue(byId.containsKey(a), "列表中应包含 quotation a");
        assertTrue(byId.containsKey(b), "列表中应包含 quotation b");
        assertEquals("待核价",  byId.get(a), "a 状态应为 待核价");
        assertEquals("核价通过", byId.get(b), "b 状态应为 核价通过（核价已批准仍可回看）");

        // 货币码固定为 CNY
        list.stream()
            .filter(x -> x.quotationId.equals(a) || x.quotationId.equals(b))
            .forEach(x -> assertEquals("CNY", x.currency, "货币码应为 CNY"));
    }

    // ── T2: 状态过滤 ──────────────────────────────────────────────────────────

    @Test
    @Transactional
    void list_filters_by_status() {
        submittedQuotation(); // 建一条 SUBMITTED，确保至少有数据

        List<CostingOrderListItemDTO> list = quotationService.listCostingOrders("待核价", null);

        // 所有返回项必须是 待核价
        assertTrue(list.stream().allMatch(x -> "待核价".equals(x.status)),
                "status=待核价 过滤后，所有结果的 status 必须为 待核价");
    }
}
