package com.cpq.quotation.service.backfill;

import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.service.QuotationService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0721 报价数据版本升级 · AC-13 状态机验收（驳回保留 pending / 撤回不回滚 / 删单级联清理）。
 *
 * <p>设计上刻意只用 <b>FLIP 路径</b>（无 snapshot 表征的纯 pending 组，与
 * {@code QuoteBackfillFlipRouteTest} 同构）来触发 {@code costingApprove}——绕开
 * {@code QuoteBackfillCollector.asUuid} 的已知缺陷（见
 * {@code QuoteBackfillFlatAcceptanceTest} javadoc），因为本文件关注的是<b>状态机语义</b>
 * （驳回/撤回/删除对 pending 数据的处理），不是回填内容本身的正确性。
 *
 * <p>「重交覆盖旧 pending」(AC-13 第三项) 是 B2 导入层"先清后写"的既有行为
 * （{@code QuoteImportService}/各 {@code Q*Handler}），不在 backfill 服务范围内，
 * 已有 {@code Q02CustomerMapReplaceTest} 等类同构验证，本文件不重复。
 */
@QuarkusTest
class QuoteStateMachineAcceptanceTest {

    private static final String TAG = "T0721SM";

    @Inject QuotationService quotationService;
    @Inject QuoteBackfillService backfillService;
    @Inject EntityManager em;

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
                "VALUES(:id, :un, 'Test Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
            .setParameter("id", fid).setParameter("un", "test_finance_" + suffix)
            .setParameter("email", "test_finance_" + suffix + "@test.invalid")
            .executeUpdate();
        return fid;
    }

    private String customerCodeOf(UUID customerId) {
        return (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();
    }

    private UUID newQuotation(UUID customerId, UUID salesRepId, String status) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                " tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, :qn, :cid, :name, :srid, :status, 0, 0, now(), now())")
            .setParameter("id", id).setParameter("qn", TAG + "-" + id.toString().substring(0, 8))
            .setParameter("cid", customerId).setParameter("name", TAG + "-quotation")
            .setParameter("srid", salesRepId).setParameter("status", status)
            .executeUpdate();
        return id;
    }

    private UUID insertPendingUnitPrice(String customerNo, String materialNo, UUID pendingQid) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, " +
                "  seq_no, pricing_price, unit, customer_no, is_current, pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', 'PROCESS', '2001', :code, :fmn, 1, 8.88, '元', :cn, false, :pq, now(), now())")
            .setParameter("id", id).setParameter("code", materialNo).setParameter("fmn", materialNo)
            .setParameter("cn", customerNo).setParameter("pq", pendingQid)
            .executeUpdate();
        return id;
    }

    private long pendingCount(UUID quotationId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId).getSingleResult()).longValue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-13 场景一：核价驳回 → pending 保留（不清理）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void costingReject_keepsPendingIntact() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String materialNo = "SM1" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        insertPendingUnitPrice(customerNo, materialNo, quotationId);
        assertEquals(1L, pendingCount(quotationId), "驳回前应有 1 条 pending");

        QuotationDTO dto = quotationService.costingReject(quotationId, "成本偏高，请复核", finance);
        assertEquals("COSTING_REJECTED", dto.status);

        assertEquals(1L, pendingCount(quotationId),
            "驳回后 pending 应原样保留（AC-13：销售改完重交，pending 跟着走）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-13 场景二：撤回已通过 → 不回滚已回填的 V6 数据（只状态流转回 DRAFT）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void withdraw_afterApproved_doesNotRollbackBackfilledData() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String materialNo = "SM2" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        // FLIP 路径：无任何页签渲染表征该组（与 QuoteBackfillFlipRouteTest 同构），绕开 asUuid 缺陷
        insertPendingUnitPrice(customerNo, materialNo, quotationId);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertTrue(summary.versionedGroups >= 1);

        // 直接把状态推进到 APPROVED（模拟 costingApprove 完成后的状态，backfill 已单独调用过，
        // 不重复走两段式 previewToken 校验——本测试只关心撤回语义）
        em.createNativeQuery("UPDATE quotation SET status = 'APPROVED' WHERE id = :id")
            .setParameter("id", quotationId).executeUpdate();

        long isCurrentBeforeWithdraw = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE customer_no = :cn AND finished_material_no = :mn AND is_current = true")
            .setParameter("cn", customerNo).setParameter("mn", materialNo).getSingleResult()).longValue();
        assertEquals(1L, isCurrentBeforeWithdraw, "撤回前：回填已生效，应有 1 条 is_current 行");

        QuotationDTO dto = quotationService.withdraw(quotationId, finance);
        assertEquals("DRAFT", dto.status, "撤回后报价单状态应回到 DRAFT");

        long isCurrentAfterWithdraw = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE customer_no = :cn AND finished_material_no = :mn AND is_current = true")
            .setParameter("cn", customerNo).setParameter("mn", materialNo).getSingleResult()).longValue();
        assertEquals(1L, isCurrentAfterWithdraw,
            "撤回不应回滚已回填的 V6 数据（AC-13：避免抽走下游已引用的新版本数据）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-13 场景三：报价单删除（仅 DRAFT）→ 级联清理本单 pending 数据
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void delete_draftQuotation_cascadesCleanupPendingData() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String materialNo = "SM3" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "DRAFT");
        insertPendingUnitPrice(customerNo, materialNo, quotationId);
        assertEquals(1L, pendingCount(quotationId));

        quotationService.delete(quotationId);

        assertEquals(0L, pendingCount(quotationId),
            "DRAFT 报价单删除应级联清理本单在 unit_price 的 pending 残留（AC-13）");
        long quotationExists = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation WHERE id = :id")
            .setParameter("id", quotationId).getSingleResult()).longValue();
        assertEquals(0L, quotationExists, "报价单本身应已删除");
    }

    @Test
    @TestTransaction
    void delete_nonDraftQuotation_rejected() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");

        com.cpq.common.exception.BusinessException ex = assertThrows(
            com.cpq.common.exception.BusinessException.class,
            () -> quotationService.delete(quotationId),
            "非 DRAFT 报价单不应允许删除");
        assertEquals(400, ex.getCode());
    }
}
