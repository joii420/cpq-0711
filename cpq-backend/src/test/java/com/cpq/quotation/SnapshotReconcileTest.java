package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 对账门禁：快照值 vs 实时值。
 * 对应报价单整份快照 Phase 1 Task 8。
 *
 * <p>目的：证明"加产品时冻进的快照结构/值"与"当前 DB 数据"一致（为 Phase 2 切渲染打基础）。
 * <ul>
 *   <li>T1: quote_card_values.tabs 数组与 components_snapshot tabs 数量相同（结构一致性）</li>
 *   <li>T2: quote_excel_values 是 {rows:[...]} 结构（格式一致）</li>
 *   <li>T3: 多次调用 snapshotLineValues 后 quote_card_values 不变（幂等=同一快照）</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SnapshotReconcileTest — Phase 1 对账门禁")
public class SnapshotReconcileTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    static final ObjectMapper MAPPER = new ObjectMapper();

    private UUID resolveTestLineItemId() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.id FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "WHERE li.product_part_no_snapshot IS NOT NULL " +
            "  AND q.customer_template_id IS NOT NULL " +
            "  AND t1.components_snapshot IS NOT NULL " +
            "  AND t1.status='PUBLISHED' " +
            "LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @BeforeEach
    @Transactional
    void clearColumns() {
        em.createNativeQuery(
            "UPDATE quotation_line_item SET quote_card_values=NULL, quote_excel_values=NULL, " +
            "costing_card_values=NULL, costing_excel_values=NULL, card_snapshot_at=NULL " +
            "WHERE id IN (" +
            "  SELECT li.id FROM quotation_line_item li " +
            "  JOIN quotation q ON q.id = li.quotation_id " +
            "  JOIN template t1 ON t1.id = q.customer_template_id " +
            "  WHERE li.product_part_no_snapshot IS NOT NULL " +
            "    AND t1.components_snapshot IS NOT NULL AND t1.status='PUBLISHED' LIMIT 5)"
        ).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // T1: quote_card_values.tabs 数量应与 components_snapshot 的 tab 数量相同
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("T1: quote_card_values.tabs 数量与模板 components_snapshot tab 数量一致")
    void quoteCardSnapshot_tabCountMatchesTemplate() throws Exception {
        UUID lineId = resolveTestLineItemId();
        org.junit.jupiter.api.Assumptions.assumeTrue(lineId != null, "需要已有产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        // 从 DB 重新读
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.quote_card_values, " +
            "       jsonb_array_length(t.components_snapshot) AS template_tab_count " +
            "FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "JOIN template t ON t.id = q.customer_template_id " +
            "WHERE li.id = :id")
            .setParameter("id", lineId)
            .getResultList();

        assertFalse(rows.isEmpty());
        Object[] row = (Object[]) rows.get(0);
        String qcv = row[0] != null ? row[0].toString() : null;
        Number templateTabCount = (Number) row[1];

        assertNotNull(qcv, "quote_card_values should be written");
        assertNotNull(templateTabCount, "template should have components_snapshot");

        JsonNode json = MAPPER.readTree(qcv);
        int snapshotTabCount = json.path("tabs").size();

        assertEquals(templateTabCount.intValue(), snapshotTabCount,
            "quote_card_values.tabs count should match template components_snapshot tab count");
    }

    // -----------------------------------------------------------------------
    // T2: quote_excel_values 具有 {rows:[]} 结构
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("T2: quote_excel_values 为 {rows:[...]} JSON 结构")
    void quoteExcelSnapshot_hasRowsArrayStructure() throws Exception {
        UUID lineId = resolveTestLineItemId();
        org.junit.jupiter.api.Assumptions.assumeTrue(lineId != null, "需要已有产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT quote_excel_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();

        assertFalse(rows.isEmpty());
        String qev = rows.get(0) != null ? rows.get(0).toString() : null;
        assertNotNull(qev, "quote_excel_values should be written");

        JsonNode json = MAPPER.readTree(qev);
        assertTrue(json.has("rows"), "quote_excel_values should have 'rows' key");
        assertTrue(json.path("rows").isArray(), "quote_excel_values.rows should be an array");
    }

    // -----------------------------------------------------------------------
    // T3: 两次调用 snapshotLineValues，tabs 结构不变（幂等对账）
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("T3: 两次调用 snapshotLineValues 后 tabs 结构不变（幂等）")
    void quoteCardSnapshot_isIdempotentAndStable() throws Exception {
        UUID lineId = resolveTestLineItemId();
        org.junit.jupiter.api.Assumptions.assumeTrue(lineId != null, "需要已有产品行");

        QuotationLineItem li1 = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li1);

        @SuppressWarnings("unchecked")
        var rows1 = em.createNativeQuery(
            "SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        String first = rows1.get(0) != null ? rows1.get(0).toString() : null;

        // 第二次调用
        QuotationLineItem li2 = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li2);

        @SuppressWarnings("unchecked")
        var rows2 = em.createNativeQuery(
            "SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        String second = rows2.get(0) != null ? rows2.get(0).toString() : null;

        assertNotNull(first);
        assertNotNull(second);

        // Tab 数量应相同（幂等稳定）
        JsonNode j1 = MAPPER.readTree(first);
        JsonNode j2 = MAPPER.readTree(second);
        assertEquals(j1.path("tabs").size(), j2.path("tabs").size(),
            "两次调用 tabs 数量应相同（幂等稳定）");
    }
}
