package com.cpq.quotation.service.backfill;

import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
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
 * task-0721 报价数据版本升级 · B6 —— previewToken 确定性自测（需求说明 §12 Q4）。
 *
 * <p>T1：同一（未变）报价单状态下连续两次 {@code preview} 必须得到相同 token（幂等，不误报 409）。
 * <p>T2：一个全新报价单（无任何 pending V6 数据、无任何行）应得到 {@code summary} 全 0 + {@code groups: []}
 *       （api.md §1.1"空影响"约定）。
 * <p>T3：{@code verifyToken} 对刚拿到的 token 返回 true；对随意伪造的 token 返回 false（防伪造放行）。
 */
@QuarkusTest
class QuoteBackfillPreviewTokenTest {

    @Inject QuoteBackfillPreviewService previewService;
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

    private UUID freshQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");
        Quotation q = new Quotation();
        q.quotationNumber = "TEST-BF-TOKEN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "QuoteBackfillPreviewTokenTest";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();
        return q.id;
    }

    @Test
    @TestTransaction
    void samStateProducesSameToken() {
        UUID qid = freshQuotation();
        BackfillPreviewDTO p1 = previewService.preview(qid);
        BackfillPreviewDTO p2 = previewService.preview(qid);
        assertNotNull(p1.previewToken);
        assertEquals(p1.previewToken, p2.previewToken, "同状态两次 preview 必须得到同一 token（Q4 幂等）");
    }

    @Test
    @TestTransaction
    void emptyQuotationHasZeroSummary() {
        UUID qid = freshQuotation();
        BackfillPreviewDTO p = previewService.preview(qid);
        assertEquals(0, p.summary.versionedGroups);
        assertEquals(0, p.summary.addedRows);
        assertEquals(0, p.summary.deletedRows);
        assertEquals(0, p.summary.changedRows);
        assertTrue(p.groups.isEmpty());
    }

    @Test
    @TestTransaction
    void verifyTokenAcceptsFreshRejectsForged() {
        UUID qid = freshQuotation();
        BackfillPreviewDTO p = previewService.preview(qid);
        assertTrue(previewService.verifyToken(qid, p.previewToken), "刚拿到的 token 应校验通过");
        assertFalse(previewService.verifyToken(qid, "forged-token-not-a-real-hash"), "伪造 token 应被拒绝");
        assertFalse(previewService.verifyToken(qid, null), "空 token 应被拒绝");
    }
}
