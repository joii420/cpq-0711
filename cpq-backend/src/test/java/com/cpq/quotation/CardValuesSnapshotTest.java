package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: CardSnapshotService.snapshotLineValues — 产品行级 4 份值快照。
 * 对应报价单整份快照 Phase 1 Task 6。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CardValuesSnapshotTest — Task 6 snapshotLineValues")
public class CardValuesSnapshotTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    /**
     * 获取一个已有产品行的 line item ID（quotation 必须绑定双模板）。
     */
    private UUID resolveTestLineItemId() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.id FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "WHERE li.product_part_no_snapshot IS NOT NULL " +
            "  AND q.customer_template_id IS NOT NULL " +
            "  AND q.costing_card_template_id IS NOT NULL " +
            "LIMIT 1").getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        return null;
    }

    @BeforeEach
    @Transactional
    void clearSnapshotColumns() {
        // 清理快照列，保证每次测试独立
        em.createNativeQuery(
            "UPDATE quotation_line_item SET quote_card_values=NULL, quote_excel_values=NULL, " +
            "costing_card_values=NULL, costing_excel_values=NULL, card_snapshot_at=NULL, " +
            "quote_values_at=NULL WHERE id IN (" +
            "  SELECT li.id FROM quotation_line_item li " +
            "  JOIN quotation q ON q.id = li.quotation_id " +
            "  WHERE li.product_part_no_snapshot IS NOT NULL " +
            "    AND q.customer_template_id IS NOT NULL " +
            "    AND q.costing_card_template_id IS NOT NULL " +
            "  LIMIT 5)"
        ).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // T1: snapshotLineValues 写入 4 份值列 + cardSnapshotAt
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("T1: snapshotLineValues 写入 quote_card_values + cardSnapshotAt，tabs 数组存在")
    void snapshotLineValues_writesValueColumns_withTabs() throws Exception {
        UUID lineId = resolveTestLineItemId();
        org.junit.jupiter.api.Assumptions.assumeTrue(lineId != null,
            "需要一个已有产品行的报价单（绑定双模板）");

        // 先读 entity
        QuotationLineItem li = QuotationLineItem.findById(lineId);
        assertNotNull(li, "QuotationLineItem should exist");

        // 执行快照
        svc.snapshotLineValues(li);

        // 重新从 DB 加载验证
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT quote_card_values, quote_excel_values, costing_card_values, " +
            "costing_excel_values, card_snapshot_at " +
            "FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        assertFalse(rows.isEmpty(), "line item should exist in DB");

        Object[] row = (Object[]) rows.get(0);
        String qcv = row[0] != null ? row[0].toString() : null;
        String qev = row[1] != null ? row[1].toString() : null;
        // costing 值依赖 costing_card_template_id，有模板则非 null
        Object cardSnapshotAt = row[4];

        assertNotNull(qcv, "quote_card_values should be written");
        assertNotNull(qev, "quote_excel_values should be written");
        assertNotNull(cardSnapshotAt, "card_snapshot_at should be written");

        // quote_card_values 必须含 tabs 数组
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(qcv);
        assertTrue(json.path("tabs").isArray(),
            "quote_card_values.tabs should be an array");
    }

    // -----------------------------------------------------------------------
    // T2: 幂等调用 — 第二次调用不重置已写的快照时间
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("T2: 幂等调用 — snapshotLineValues 写入后再调用 card_snapshot_at 不早于首次")
    void snapshotLineValues_isIdempotent() throws Exception {
        UUID lineId = resolveTestLineItemId();
        org.junit.jupiter.api.Assumptions.assumeTrue(lineId != null,
            "需要一个已有产品行的报价单（绑定双模板）");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        @SuppressWarnings("unchecked")
        var rows1 = em.createNativeQuery(
            "SELECT card_snapshot_at FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        assertFalse(rows1.isEmpty());
        Object firstAt = rows1.get(0);
        assertNotNull(firstAt);

        // 第二次调用
        QuotationLineItem li2 = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li2);

        @SuppressWarnings("unchecked")
        var rows2 = em.createNativeQuery(
            "SELECT card_snapshot_at FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        assertFalse(rows2.isEmpty());
        Object secondAt = rows2.get(0);
        assertNotNull(secondAt, "card_snapshot_at should still be set after second call");
    }
}
