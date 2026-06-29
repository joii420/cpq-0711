package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
 * <p><b>测试策略（I3 取舍说明）：</b>
 * <p>原版依赖 DB 现存 DRAFT 报价单，干净 CI 会因 {@code assumeTrue} 静默跳过（零覆盖）。
 * <p>理想方案是完整自建 quotation 图谱（customer→user→product→template→quotation→line_item），
 * 但 quotation 有 customer_id/sales_rep_id 真实外键约束，cost 过高。
 * <p><b>实际方案</b>：
 * <ol>
 *   <li>先查一条 DRAFT quotation（只读 quotation 表，FK 约束已满足）。</li>
 *   <li>@TestTransaction 内自建最小 QuotationLineItem（quotationId 指向该 DRAFT），
 *       测试完毕事务自动回滚，不污染 DB。</li>
 *   <li>若 DB 确实无任何 DRAFT quotation，改为显式 fail 而非静默 skip，
 *       测试失败可见（CI 红）而非假绿（zero-coverage green）。</li>
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

    /**
     * T2 guard sentinel: 物理上不可能由 buildExcelValues 产出的哨兵 JSON。
     * 用于 T2 证伪强度：若守卫失效（buildExcelValues 被调），返回值必然不含该哨兵。
     */
    private static final String GUARD_SENTINEL_SNAPSHOT =
            "{\"rows\":[{\"__guard_sentinel__\":\"GUARD_KEEP_ME\"}]}";

    /** T1 用的特征值快照。 */
    private static final String FRONTEND_SNAPSHOT = "{\"rows\":[{\"C\":0.93}]}";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** 找一条 DRAFT quotation ID；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private UUID findDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) return null;
        Object o = rows.get(0);
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    /**
     * 在当前事务内自建最小 QuotationLineItem，挂到 quotationId 下。
     * 直接从 product / template 表各取一条合法 ID 满足 FK 约束。
     * 调用方须在 @TestTransaction 测试方法中，事务结束自动回滚清理（不污染 DB）。
     */
    @SuppressWarnings("unchecked")
    private UUID createMinimalLineItem(UUID quotationId) {
        // 直接从 product 表取任意一条，满足 FK quotation_line_item.product_id → product.id
        List<Object> products = em.createNativeQuery(
                "SELECT id FROM product LIMIT 1")
                .getResultList();
        if (products.isEmpty()) return null;
        UUID productId = toUUID(products.get(0));

        // 直接从 template 表取任意一条，满足 FK quotation_line_item.template_id → template.id
        List<Object> templates = em.createNativeQuery(
                "SELECT id FROM template LIMIT 1")
                .getResultList();
        if (templates.isEmpty()) return null;
        UUID templateId = toUUID(templates.get(0));

        UUID newId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_id, template_id, sort_order, created_at) " +
                "VALUES (:id, :qid, :pid, :tid, 99, :now)")
                .setParameter("id",   newId)
                .setParameter("qid",  quotationId)
                .setParameter("pid",  productId)
                .setParameter("tid",  templateId)
                .setParameter("now",  OffsetDateTime.now())
                .executeUpdate();
        return newId;
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

    // -----------------------------------------------------------------------
    // T1 — 前端送 quoteExcelValues → 原样落库，不被后端重算覆盖
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("T1: saveDraft 携带 quoteExcelValues → 原样落库含 '0.93'")
    @TestTransaction
    void saveDraft_carriesQuoteExcelValues_persistedAsIs() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId,
                "无法自建 QuotationLineItem（无可用 product_id/template_id 引用），请检查基础数据");

        // 构造 SaveDraftRequest，LineItemDraft 携带前端 Excel 快照（命中自建行 → UPSERT）
        SaveDraftRequest req = new SaveDraftRequest();
        SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
        liDraft.id              = lineItemId;
        liDraft.sortOrder       = 99;
        // ★ Phase 3 核心：携带前端算好的 Excel 快照
        liDraft.quoteExcelValues = FRONTEND_SNAPSHOT;
        req.lineItems = List.of(liDraft);

        quotationService.saveDraft(quotationId, req);

        // 绕过 Hibernate L1 缓存直接读库
        em.flush();
        em.clear();
        String stored = readQuoteExcelValues(lineItemId);

        assertNotNull(stored, "quoteExcelValues 不应为 null（前端已送值）");
        assertTrue(stored.contains("0.93"),
                "quoteExcelValues 应原样含 '0.93'，实际：" + stored);
        // 事务回滚（@TestTransaction on test）自动清理自建数据
    }

    // -----------------------------------------------------------------------
    // T2 — snapshotLineValues 守卫验证：前端已送值后，守卫不覆盖（不调 buildExcelValues）。
    //   I2 fix（2026-06-21）：改用物理不可产出的哨兵值 GUARD_SENTINEL_SNAPSHOT，
    //   证伪强度远高于依赖"buildExcelValues 不产出 0.93"的隐含前提。
    // -----------------------------------------------------------------------

    @Inject
    CardSnapshotService cardSnapshotService;

    @Test
    @Order(2)
    @DisplayName("T2: snapshotLineValues 守卫 — 前端值已存在时不覆盖（哨兵证伪）")
    @TestTransaction
    void snapshotLineValues_guardDoesNotOverwriteFrontendValue() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId,
                "无法自建 QuotationLineItem（无可用 product_id/template_id 引用），请检查基础数据");

        // 先直写哨兵快照（模拟 saveDraft Phase3 落库后的状态）
        // I2: 使用物理不可能由 buildExcelValues 产出的哨兵值，证伪强度更高
        writeFrontendSnapshot(lineItemId, GUARD_SENTINEL_SNAPSHOT);
        em.flush();
        em.clear();

        // 验证写入成功
        String afterWrite = readQuoteExcelValues(lineItemId);
        assertNotNull(afterWrite, "直接写入后应非 null");
        assertTrue(afterWrite.contains("GUARD_KEEP_ME"),
                "直接写入后应含哨兵 'GUARD_KEEP_ME'，实际：" + afterWrite);

        // 调 snapshotLineValues（守卫路径：quoteExcelValues != null → 不调 buildExcelValues）
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        assertNotNull(li, "自建 line item 应可查到");
        cardSnapshotService.snapshotLineValues(li);

        // 绕过 Hibernate L1 缓存验证
        em.flush();
        em.clear();
        String afterSnapshot = readQuoteExcelValues(lineItemId);

        assertNotNull(afterSnapshot, "snapshotLineValues 后不应为 null");
        assertTrue(afterSnapshot.contains("GUARD_KEEP_ME"),
                "守卫应生效：snapshotLineValues 不应覆盖前端哨兵值，实际：" + afterSnapshot);
        // 事务回滚自动清理自建数据
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
