package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TDD Phase 3 — saveDraft 携带前端 quoteExcelSnapshot 并原样落库。
 *
 * <p><b>设计要点：</b>
 * <ul>
 *   <li>前端算好的 {@code quoteExcelValues}（JSONB JSON 字符串）随 LineItemDraft 发来后，
 *       后端 saveDraft 必须原样写入 {@code quotation_line_item.quote_excel_values}，
 *       不被 {@code snapshotLineValues → buildExcelValues} 后端重算覆盖。</li>
 *   <li>守卫：{@code snapshotLineValues} 仅当 {@code li.quoteExcelValues == null} 时
 *       调 {@code buildExcelValues} 兜底；前端已送值则跳过（前端权威，后端退让）。</li>
 * </ul>
 *
 * <p><b>测试策略：</b>
 * <ol>
 *   <li>用 native SQL 找一条真实 DRAFT 报价单（无则 assumeTrue 跳过）。</li>
 *   <li>调用 {@code quotationService.saveDraft}，LineItemDraft 携带
 *       {@code quoteExcelValues='{"rows":[{"C":0.93}]}'} 和该单现有 line item 的
 *       关键字段（确保 UPSERT 命中已有行）。</li>
 *   <li>断言落库后 {@code QuotationLineItem.quoteExcelValues} 含 {@code "0.93"}
 *       （原样落库，未被后端重算覆盖）。</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SaveDraftExcelSnapshotTest — Phase3 前端 Excel 快照原样落库")
public class SaveDraftExcelSnapshotTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    /** 前端送来的 Excel 快照 JSON 字符串（含特征值 0.93，便于断言）。 */
    private static final String FRONTEND_SNAPSHOT = "{\"rows\":[{\"C\":0.93}]}";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** 找一条真实 DRAFT 报价单及其第一条 lineItem；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private Object[] findDraftQuotationWithLineItem() {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT q.id, li.id, li.product_id, li.template_id, " +
                "li.product_part_no_snapshot, li.product_name_snapshot " +
                "FROM quotation q " +
                "JOIN quotation_line_item li ON li.quotation_id = q.id " +
                "WHERE q.status = 'DRAFT' " +
                "ORDER BY q.created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) return null;
        return rows.get(0);
    }

    /** 用 native SQL 直接读 quote_excel_values，绕过 Hibernate L1 缓存。 */
    @SuppressWarnings("unchecked")
    private String readQuoteExcelValues(UUID lineItemId) {
        List<Object> r = em.createNativeQuery(
                "SELECT quote_excel_values::text FROM quotation_line_item WHERE id = :lid")
                .setParameter("lid", lineItemId)
                .getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    /** 还原 quote_excel_values（AfterEach 清理，不污染 DB）。 */
    @Transactional
    public void restoreQuoteExcelValues(UUID lineItemId, String original) {
        if (lineItemId == null) return;
        if (original == null) {
            em.createNativeQuery(
                    "UPDATE quotation_line_item SET quote_excel_values = NULL WHERE id = :lid")
                    .setParameter("lid", lineItemId)
                    .executeUpdate();
        } else {
            em.createNativeQuery(
                    "UPDATE quotation_line_item SET quote_excel_values = CAST(:val AS jsonb) WHERE id = :lid")
                    .setParameter("val", original)
                    .setParameter("lid", lineItemId)
                    .executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // T1 — 前端送 quoteExcelValues → 原样落库，不被后端重算覆盖
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("T1: saveDraft 携带 quoteExcelValues → 原样落库含 '0.93'")
    void saveDraft_carriesQuoteExcelValues_persistedAsIs() {
        Object[] row = findDraftQuotationWithLineItem();
        assumeTrue(row != null, "DB 无 DRAFT 报价单，跳过 T1");

        UUID quotationId = toUUID(row[0]);
        UUID lineItemId  = toUUID(row[1]);
        UUID productId   = toUUID(row[2]);
        UUID templateId  = toUUID(row[3]);
        String partNo    = row[4] != null ? row[4].toString() : null;
        String name      = row[5] != null ? row[5].toString() : null;

        // 记录原始值（AfterEach 还原用）
        String originalVal = readQuoteExcelValues(lineItemId);

        try {
            // 构造 SaveDraftRequest，LineItemDraft 携带前端 Excel 快照
            SaveDraftRequest req = new SaveDraftRequest();
            SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
            liDraft.id              = lineItemId;  // 命中已有行 → UPSERT
            liDraft.productId       = productId;
            liDraft.templateId      = templateId;
            liDraft.productPartNo   = partNo;
            liDraft.productName     = name;
            liDraft.sortOrder       = 0;
            // ★ Phase 3 核心：携带前端算好的 Excel 快照
            liDraft.quoteExcelValues = FRONTEND_SNAPSHOT;
            req.lineItems = List.of(liDraft);

            quotationService.saveDraft(quotationId, req);

            // 绕过 Hibernate L1 缓存直接读库
            em.clear();
            String stored = readQuoteExcelValues(lineItemId);

            assertNotNull(stored, "quoteExcelValues 不应为 null（前端已送值）");
            assertTrue(stored.contains("0.93"),
                    "quoteExcelValues 应原样含 '0.93'，实际：" + stored);
        } finally {
            restoreQuoteExcelValues(lineItemId, originalVal);
        }
    }

    // -----------------------------------------------------------------------
    // T2 — snapshotLineValues 守卫验证：前端已送值后，守卫不覆盖（不调 buildExcelValues）。
    //   策略：先用 T1 同样的方式写入 FRONTEND_SNAPSHOT，再调用 snapshotLineValues，
    //   验证守卫生效——quoteExcelValues 仍然是 FRONTEND_SNAPSHOT 而非后端算出的其他值。
    //   注：snapshotLineValues 在 QuotationResource 里仅对新行（quoteCardValues=null）触发；
    //   本测试直接调 CardSnapshotService.snapshotLineValues 以白盒验证守卫逻辑本身。
    // -----------------------------------------------------------------------

    @Inject
    CardSnapshotService cardSnapshotService;

    @Test
    @Order(2)
    @DisplayName("T2: snapshotLineValues 守卫 — 前端值已存在时不覆盖")
    void snapshotLineValues_guardDoesNotOverwriteFrontendValue() {
        Object[] row = findDraftQuotationWithLineItem();
        assumeTrue(row != null, "DB 无 DRAFT 报价单，跳过 T2");

        UUID lineItemId = toUUID(row[1]);

        // 记录原始值（AfterEach 还原用）
        String originalVal = readQuoteExcelValues(lineItemId);

        try {
            // 先直接写入 FRONTEND_SNAPSHOT（模拟 saveDraft Phase3 落库后的状态）
            writeFrontendSnapshot(lineItemId, FRONTEND_SNAPSHOT);
            em.clear();

            // 验证写入成功
            String afterWrite = readQuoteExcelValues(lineItemId);
            assertNotNull(afterWrite, "直接写入后应非 null");
            assertTrue(afterWrite.contains("0.93"), "直接写入后应含 '0.93'，实际：" + afterWrite);

            // 调 snapshotLineValues（守卫路径：quoteExcelValues != null → 不调 buildExcelValues）
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            assumeTrue(li != null, "lineItem 找不到，跳过");
            cardSnapshotService.snapshotLineValues(li);

            // 绕过 Hibernate L1 缓存验证
            em.clear();
            String afterSnapshot = readQuoteExcelValues(lineItemId);

            assertNotNull(afterSnapshot, "snapshotLineValues 后不应为 null");
            assertTrue(afterSnapshot.contains("0.93"),
                    "守卫应生效：snapshotLineValues 不应覆盖前端值，实际：" + afterSnapshot);
        } finally {
            restoreQuoteExcelValues(lineItemId, originalVal);
        }
    }

    @Transactional
    public void writeFrontendSnapshot(UUID lineItemId, String snapshot) {
        em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_excel_values = CAST(:val AS jsonb) WHERE id = :lid")
                .setParameter("val", snapshot)
                .setParameter("lid", lineItemId)
                .executeUpdate();
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
