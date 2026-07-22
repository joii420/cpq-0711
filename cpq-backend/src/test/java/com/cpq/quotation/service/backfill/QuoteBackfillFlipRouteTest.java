package com.cpq.quotation.service.backfill;

import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0721 报价数据版本升级 · B5 路径②（FLIP）自测。
 *
 * <p>场景：{@code unit_price} 里存在一行本单 pending（{@code is_current=false}），但报价单没有任何
 * line item / 页签渲染表征它（模拟"导入了但当前无任何报价模板渲染"，backtask B5.2 路径②，
 * 现网原型是 {@code plating_scheme}，这里用 {@code unit_price} 复现同款场景更易独立起夹具）。
 * 执行回填后应直接 flip：{@code is_current=true, pending_quotation_id=NULL}，不走升版写入器
 * （newRows 为空会被 I1 拒绝，故不能走 REBUILD），且清理干净不残留 pending。
 */
@QuarkusTest
class QuoteBackfillFlipRouteTest {

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

    @Test
    @TestTransaction
    void noSnapshotRepresentation_flipsToOfficial() {
        UUID customerId = resolveCustomerId();
        UUID userId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(userId != null, "需要共享 DB 中存在 user 行");

        String customerNo = (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-B5-FLIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "QuoteBackfillFlipRouteTest";
        q.customerId = customerId;
        q.salesRepId = userId;
        q.status = "SUBMITTED";
        q.persist();
        em.flush();

        String code = "TEST-B5-FLIP-CODE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID rowId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, cost_type, customer_no, " +
                "  currency, unit, is_current, pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', 'ELEMENT', '2000', :code, '元素价格', :cn, 'CNY', 'kg', false, :pq, now(), now())")
            .setParameter("id", rowId).setParameter("code", code).setParameter("cn", customerNo)
            .setParameter("pq", q.id)
            .executeUpdate();
        em.flush();
        em.clear();

        QuoteBackfillService.Summary summary = backfillService.execute(q.id, userId);
        assertTrue(summary.versionedGroups >= 1, "应识别到至少 1 个 FLIP 组");

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT is_current, pending_quotation_id FROM unit_price WHERE id = :id")
            .setParameter("id", rowId).getSingleResult();
        assertEquals(Boolean.TRUE, row[0], "路径②应 flip is_current=true");
        assertNull(row[1], "路径②应清空 pending_quotation_id");

        long remainingPending = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE pending_quotation_id = :qid")
            .setParameter("qid", q.id).getSingleResult()).longValue();
        assertEquals(0L, remainingPending, "回填后本单 pending 残留应清理干净");
    }
}
