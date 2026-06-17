package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TDD: CardSnapshotService.refreshQuoteCardValues(li, force) 草稿冻结短路。
 *
 * <p>验证：已首次 bake（cardSnapshotAt != null）的行：
 * <ul>
 *   <li>force=false → no-op，quoteValuesAt 不变。</li>
 *   <li>force=true → 重算，quoteValuesAt 被更新（晚于 cardSnapshotAt）。</li>
 * </ul>
 *
 * <p>seed 策略：依赖真实 DB 中「报价模板含 driver 组件且 snapshot_rows 非空」的产品行
 * （与 RefreshCardSnapshotTest 同口径），用 native SQL 直接写 cardSnapshotAt 和 quoteValuesAt
 * 初始值，测后通过 @Transactional + rollback 隔离（@TestTransaction 或手动清理）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CardSnapshotFreezeTest — refreshQuoteCardValues force 短路")
public class CardSnapshotFreezeTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** 选一条真实的、有 driver+基础数据 的产品行（与 RefreshCardSnapshotTest 同口径）。 */
    private UUID resolveTestLineItemId() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.id FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "WHERE t1.components_snapshot IS NOT NULL " +
            "  AND EXISTS (" +
            "    SELECT 1 FROM quotation_line_component_data d " +
            "    WHERE d.line_item_id = li.id AND d.snapshot_rows IS NOT NULL " +
            "      AND jsonb_typeof(d.snapshot_rows)='array' AND jsonb_array_length(d.snapshot_rows) > 0) " +
            "LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    /** 读 quoteValuesAt 时间戳。PostgreSQL + Hibernate 6 返回 Instant 或 OffsetDateTime，兼容处理。 */
    private OffsetDateTime readQuoteValuesAt(UUID lineId) {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT quote_values_at FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        Object val = rows.get(0);
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof java.time.Instant inst) return inst.atOffset(java.time.ZoneOffset.UTC);
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        // fallback: toString -> parse
        return OffsetDateTime.parse(val.toString().replace(" ", "T"));
    }

    /** 读 quoteCardValues（用来判断字段值是否被改写）。 */
    private String readQuoteCardValues(UUID lineId) {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        return (rows.isEmpty() || rows.get(0) == null) ? null : rows.get(0).toString();
    }

    /** 将 cardSnapshotAt 写为过去某个固定时刻，并清零 quoteValuesAt，返回写入的 cardSnapshotAt 值。 */
    @Transactional
    OffsetDateTime seedBakedState(UUID lineId) {
        OffsetDateTime past = OffsetDateTime.now().minusHours(2);
        em.createNativeQuery(
            "UPDATE quotation_line_item " +
            "   SET card_snapshot_at = CAST(:ts AS timestamptz), " +
            "       quote_values_at  = NULL " +
            " WHERE id = :id")
            .setParameter("ts", past.toString())
            .setParameter("id", lineId)
            .executeUpdate();
        return past;
    }

    // -----------------------------------------------------------------------
    // T1: force=false + cardSnapshotAt!=null → no-op，quoteValuesAt 不变
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("T1: force=false + 已 bake 行 → no-op，quoteValuesAt 保持 NULL 不变")
    void refreshQuoteCardValues_nonForce_skipsAlreadyBakedRow() {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null,
            "需要报价模板含 driver 组件且基础数据非空的产品行");

        // seed：设置 cardSnapshotAt（已 bake），清零 quoteValuesAt
        seedBakedState(lineId);

        // 确认 seed 写入成功
        em.clear(); // 清 L1 缓存，后续读会走 DB
        OffsetDateTime beforeValuesAt = readQuoteValuesAt(lineId);
        assertNull(beforeValuesAt, "seed 后 quoteValuesAt 应为 NULL（seed 条件）");

        // 执行非 force 调用（已 bake 行 → 应 no-op）
        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.refreshQuoteCardValues(li, false);

        // 验证：quoteValuesAt 仍为 NULL（没被更新，证明真正 no-op）
        em.clear();
        OffsetDateTime afterValuesAt = readQuoteValuesAt(lineId);
        assertNull(afterValuesAt,
            "force=false 且 cardSnapshotAt!=null 时，refreshQuoteCardValues 必须 no-op，quoteValuesAt 不得被更新");
    }

    // -----------------------------------------------------------------------
    // Helpers for T3
    // -----------------------------------------------------------------------

    /**
     * 找一个满足以下条件的报价单 ID：
     * <ul>
     *   <li>status = 'DRAFT'</li>
     *   <li>有 quotation_view_structure 结构行（至少 QUOTE_CARD）</li>
     *   <li>至少有 1 条 lineItem（有 driver 数据，供 force 重算）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private UUID resolveQuotationIdWithStructure() {
        var rows = em.createNativeQuery(
            "SELECT q.id FROM quotation q " +
            "JOIN quotation_view_structure vs ON vs.quotation_id = q.id AND vs.view_kind = 'QUOTE_CARD' " +
            "WHERE q.status = 'DRAFT' " +
            "  AND EXISTS (" +
            "    SELECT 1 FROM quotation_line_item li " +
            "    WHERE li.quotation_id = q.id" +
            "  ) " +
            "LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    /**
     * 读指定报价单的所有 quotation_view_structure 行的 created_at（字符串列表，顺序固定）。
     * 用于在调用前后对比，验证结构行没有被 delete+重建。
     */
    @SuppressWarnings("unchecked")
    private List<String> readStructureCreatedAts(UUID quotationId) {
        var rows = em.createNativeQuery(
            "SELECT created_at::text FROM quotation_view_structure " +
            "WHERE quotation_id = :qid ORDER BY view_kind")
            .setParameter("qid", quotationId)
            .getResultList();
        return rows.stream().map(Object::toString).toList();
    }

    /** 读指定报价单第一条 lineItem 的 quoteValuesAt（用于验证 force=true 有走）。 */
    @SuppressWarnings("unchecked")
    private OffsetDateTime readFirstLineQuoteValuesAt(UUID quotationId) {
        var rows = em.createNativeQuery(
            "SELECT li.quote_values_at FROM quotation_line_item li " +
            "WHERE li.quotation_id = :qid ORDER BY li.created_at LIMIT 1")
            .setParameter("qid", quotationId)
            .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        Object val = rows.get(0);
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof java.time.Instant inst) return inst.atOffset(java.time.ZoneOffset.UTC);
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(val.toString().replace(" ", "T"));
    }

    /**
     * seed：把指定报价单所有 lineItem 的 cardSnapshotAt 写为过去（已 bake），清零 quoteValuesAt。
     * 确保 force=false 时 no-op，但我们在 refreshDraftQuoteCards 里要测的是 force=true。
     */
    @Transactional
    void seedAllLinesBaked(UUID quotationId) {
        OffsetDateTime past = OffsetDateTime.now().minusHours(2);
        em.createNativeQuery(
            "UPDATE quotation_line_item " +
            "   SET card_snapshot_at = CAST(:ts AS timestamptz), " +
            "       quote_values_at  = NULL " +
            " WHERE quotation_id = :qid")
            .setParameter("ts", past.toString())
            .setParameter("qid", quotationId)
            .executeUpdate();
    }

    // -----------------------------------------------------------------------
    // T3: refreshDraftQuoteCards 不重建结构 + 逐行 force=true 重算
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("T3: refreshDraftQuoteCards → 不调 rebuildStructureForDraft(结构 createdAt 不变) + 逐行 force=true 重算(quoteValuesAt 被更新)")
    void refreshDraftQuoteCards_doesNotRebuildStructure() {
        UUID quotationId = resolveQuotationIdWithStructure();
        assumeTrue(quotationId != null,
            "需要 DRAFT 报价单且有 quotation_view_structure 行 + lineItem");

        // seed：所有行设为已 bake 状态（cardSnapshotAt=过去, quoteValuesAt=NULL）
        seedAllLinesBaked(quotationId);

        // 记录调用前的结构快照 createdAt（rebuildStructureForDraft = delete+重建 → createdAt 会变）
        em.clear();
        List<String> beforeCreatedAts = readStructureCreatedAts(quotationId);
        assertFalse(beforeCreatedAts.isEmpty(),
            "前置条件：quotation_view_structure 应有行");

        // 执行：显式刷新整单（被改造后：不重建结构 + 逐行 force=true 重算值）
        svc.refreshDraftQuoteCards(quotationId);

        // 验证1：quotation_view_structure 的 created_at 完全不变
        // （rebuildStructureForDraft 会先 DELETE 再 insert 新行，createdAt 必然变化）
        em.clear();
        List<String> afterCreatedAts = readStructureCreatedAts(quotationId);
        assertEquals(beforeCreatedAts.size(), afterCreatedAts.size(),
            "R1：refreshDraftQuoteCards 不得重建结构，行数不得变化");
        assertEquals(beforeCreatedAts, afterCreatedAts,
            "R1：rebuildStructureForDraft 未被调用 → quotation_view_structure.created_at 必须与调用前完全相同");

        // 验证2：第一条 lineItem 的 quoteValuesAt 被更新（证明 force=true 路径有走）
        OffsetDateTime afterValuesAt = readFirstLineQuoteValuesAt(quotationId);
        assertNotNull(afterValuesAt,
            "I-1+force=true：refreshDraftQuoteCards 应逐行调 self.refreshQuoteCardValues(li, true)，quoteValuesAt 必须被更新");
    }

    // -----------------------------------------------------------------------
    // T2: force=true + cardSnapshotAt!=null → 重算，quoteValuesAt 被更新
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("T2: force=true + 已 bake 行 → 重算，quoteValuesAt 被更新（晚于 cardSnapshotAt）")
    void refreshQuoteCardValues_force_recomputesBakedRow() {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null,
            "需要报价模板含 driver 组件且基础数据非空的产品行");

        // 先做一次 snapshotLineValues 以确保 quoteCardValues 有可以重算的内容
        QuotationLineItem li0 = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li0);

        // seed：设 cardSnapshotAt=过去，清零 quoteValuesAt
        OffsetDateTime cardSnapshotAt = seedBakedState(lineId);

        em.clear();
        OffsetDateTime beforeValuesAt = readQuoteValuesAt(lineId);
        assertNull(beforeValuesAt, "seed 后 quoteValuesAt 应为 NULL");

        // 记录调用前的 quoteCardValues（用来判断 force=true 后是否真的重算写过）
        String beforeQcv = readQuoteCardValues(lineId);

        // 等一毫秒，确保 quoteValuesAt 时间差可检测
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        // 执行 force=true 调用（即使已 bake 也应重算）
        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.refreshQuoteCardValues(li, true);

        // 验证：quoteValuesAt 被更新（非 NULL，且晚于 cardSnapshotAt）
        em.clear();
        OffsetDateTime afterValuesAt = readQuoteValuesAt(lineId);
        assertNotNull(afterValuesAt,
            "force=true 时，refreshQuoteCardValues 必须更新 quoteValuesAt（不得 no-op）");
        assertTrue(afterValuesAt.isAfter(cardSnapshotAt),
            "force=true 后 quoteValuesAt 应晚于 cardSnapshotAt（证明真正重算而非遗留值）");
    }
}
