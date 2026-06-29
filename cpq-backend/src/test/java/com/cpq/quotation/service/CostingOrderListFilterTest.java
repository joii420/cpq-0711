package com.cpq.quotation.service;

import com.cpq.quotation.dto.CostingOrderDetailDTO;
import com.cpq.quotation.dto.CostingOrderListItemDTO;
import com.cpq.quotation.entity.CostingOrder;
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
 * CostingOrderListFilterTest — Task 4 过滤 + 详情验证。
 *
 * <p>T1: list_all_returns_pending_rejected_withdrawn
 *        — null 过滤时列表包含 PENDING、REJECTED、WITHDRAWN 各状态历史单（守卫已移除）。
 * <p>T2: list_filter_by_rejected_returns_only_rejected
 *        — statuses=["REJECTED"] 时只返回 REJECTED 行。
 * <p>T3: list_keyword_filter_by_quotation_number
 *        — keyword 按报价单号命中（大小写不敏感）。
 * <p>T4: dto_fields_populated_no_frozen_dto
 *        — 列表 DTO 含 costingOrderId / costingOrderNumber / updatedAt，无 frozenDto 字段。
 * <p>T5: get_by_id_returns_frozen_dto_and_quotation_id
 *        — getCostingOrderById 返回含 frozenDto 非空、quotationId 正确。
 *
 * <p>夹具策略：@Transactional 测试事务内建数据，结束后自动回滚，不污染共享 DB。
 */
@QuarkusTest
class CostingOrderListFilterTest {

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
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Filter Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
                .setParameter("id", fid)
                .setParameter("un", "filter_finance_" + suffix)
                .setParameter("email", "filter_finance_" + suffix + "@test.invalid")
                .executeUpdate();
        return fid;
    }

    /**
     * 建一个 SUBMITTED 报价单（submit 后核价单 status=PENDING），返回 quotationId。
     */
    private UUID submittedQuotation(String suffix) {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        Quotation q = new Quotation();
        q.quotationNumber = "FLT-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        q.name = "CostingOrderListFilterTest Draft";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();

        quotationService.submit(q.id, UUID.randomUUID());
        return q.id;
    }

    // ── T1: null 过滤返回全状态（含 REJECTED / WITHDRAWN） ──────────────────────

    @Test
    @Transactional
    void list_all_returns_pending_rejected_withdrawn() {
        UUID qPending    = submittedQuotation("P");
        UUID qRejected   = submittedQuotation("R");
        UUID qWithdrawn  = submittedQuotation("W");

        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");

        // 驳回 qRejected → costing_order.status=REJECTED
        quotationService.costingReject(qRejected, "成本偏高", finance);

        // 撤回 qWithdrawn → costing_order.status=WITHDRAWN
        // withdraw 方法校验 salesRepId 或 isFinanceOrAdmin；传 finance 用户（PRICING_MANAGER）以通过权限检查
        quotationService.withdraw(qWithdrawn, finance);

        // 全量查询
        List<CostingOrderListItemDTO> all = quotationService.listCostingOrders(null, null, null);

        // 同一 quotationId 在共享 DB 可能多条历史；enteredCostingAt DESC 首条最新，取 first 即最新状态
        Map<UUID, String> byQid = all.stream()
                .collect(Collectors.toMap(d -> d.quotationId, d -> d.status, (s1, s2) -> s1));

        assertTrue(byQid.containsKey(qPending),   "列表应包含 PENDING 核价单");
        assertTrue(byQid.containsKey(qRejected),  "列表应包含 REJECTED 核价单（守卫已移除，不再丢失）");
        assertTrue(byQid.containsKey(qWithdrawn), "列表应包含 WITHDRAWN 核价单（守卫已移除，不再丢失）");

        assertEquals("PENDING",   byQid.get(qPending));
        assertEquals("REJECTED",  byQid.get(qRejected));
        assertEquals("WITHDRAWN", byQid.get(qWithdrawn));
    }

    // ── T2: statuses=["REJECTED"] 只返回 REJECTED ───────────────────────────

    @Test
    @Transactional
    void list_filter_by_rejected_returns_only_rejected() {
        UUID qPending  = submittedQuotation("P2");
        UUID qRejected = submittedQuotation("R2");

        UUID finance = financeUserId();
        assertNotNull(finance, "需要有 PRICING_MANAGER 用户");
        quotationService.costingReject(qRejected, "价格异常", finance);

        List<CostingOrderListItemDTO> result = quotationService.listCostingOrders(
                List.of("REJECTED"), null, null);

        // 不含本次 PENDING 条目
        Map<UUID, String> byQid = result.stream()
                .collect(Collectors.toMap(d -> d.quotationId, d -> d.status, (a, b) -> a));
        assertFalse(byQid.containsKey(qPending), "PENDING 核价单不应出现在 REJECTED 过滤结果中");
        assertTrue(byQid.containsKey(qRejected), "REJECTED 核价单应在结果中");

        // 所有结果必须是 REJECTED
        assertTrue(result.stream().allMatch(d -> "REJECTED".equals(d.status)),
                "statuses=[REJECTED] 过滤后，所有结果的 status 必须为 REJECTED");
    }

    // ── T3: keyword 按报价单号命中 ───────────────────────────────────────────

    @Test
    @Transactional
    void list_keyword_filter_by_quotation_number() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        // 建两张报价单，报价单号含可区分的标记
        String uniqueMark = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Quotation qA = new Quotation();
        qA.quotationNumber = "KW-FIND-" + uniqueMark;
        qA.name = "keyword target";
        qA.customerId = customerId;
        qA.salesRepId = salesRepId;
        qA.persist();
        quotationService.submit(qA.id, UUID.randomUUID());

        Quotation qB = new Quotation();
        qB.quotationNumber = "KW-MISS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        qB.name = "keyword miss";
        qB.customerId = customerId;
        qB.salesRepId = salesRepId;
        qB.persist();
        quotationService.submit(qB.id, UUID.randomUUID());

        // keyword 匹配 qA 特征串（小写）
        List<CostingOrderListItemDTO> result = quotationService.listCostingOrders(
                null, uniqueMark.toLowerCase(), null);

        Map<UUID, String> byQid = result.stream()
                .collect(Collectors.toMap(d -> d.quotationId, d -> d.status, (a, b) -> a));

        assertTrue(byQid.containsKey(qA.id), "keyword 应命中 qA");
        assertFalse(byQid.containsKey(qB.id), "keyword 不应命中 qB");
    }

    // ── T4: DTO 含 costingOrderId / costingOrderNumber / updatedAt，无 frozenDto ─

    @Test
    @Transactional
    void dto_fields_populated_no_frozen_dto() {
        UUID qid = submittedQuotation("DTO");

        List<CostingOrderListItemDTO> all = quotationService.listCostingOrders(null, null, null);

        CostingOrderListItemDTO d = all.stream()
                .filter(x -> qid.equals(x.quotationId))
                .findFirst()
                .orElse(null);

        assertNotNull(d, "提交后的报价单应在列表中");
        assertNotNull(d.costingOrderId,     "costingOrderId 不得为 null");
        assertNotNull(d.costingOrderNumber, "costingOrderNumber 不得为 null");
        assertNotNull(d.updatedAt,          "updatedAt 不得为 null");
        assertEquals("PENDING", d.status,   "新提交状态应为 PENDING");

        // DTO 类本身无 frozenDto 字段，验证方式：通过 Class 反射确认无该字段
        boolean hasFrozenDto = false;
        try {
            CostingOrderListItemDTO.class.getField("frozenDto");
            hasFrozenDto = true;
        } catch (NoSuchFieldException ignored) {
            // 预期：无 frozenDto 字段
        }
        assertFalse(hasFrozenDto, "CostingOrderListItemDTO 不应含 frozenDto 字段");
    }

    // ── T5: getCostingOrderById 返回含 frozenDto 非空、quotationId 正确 ─────────

    @Test
    @Transactional
    void get_by_id_returns_frozen_dto_and_quotation_id() {
        UUID qid = submittedQuotation("DETAIL");

        // 找到新建的核价单 ID
        CostingOrder co = CostingOrder.findLatestByQuotation(qid);
        assertNotNull(co, "submit 后应有核价单");

        CostingOrderDetailDTO detail = quotationService.getCostingOrderById(co.id);

        assertNotNull(detail, "详情不得为 null");
        assertEquals(co.id,  detail.costingOrderId, "costingOrderId 应与核价单 ID 一致");
        assertEquals(qid,    detail.quotationId,    "quotationId 应与报价单 ID 一致");
        assertNotNull(detail.frozenDto,             "frozenDto 不得为 null");
        assertFalse(detail.frozenDto.isBlank(),     "frozenDto 不得为空串");
        assertTrue(detail.frozenDto.contains("costingCardStructure"),
                "frozenDto 应包含 costingCardStructure 键");
        assertEquals("PENDING", detail.status,      "新建核价单详情 status 应为 PENDING");
    }
}
