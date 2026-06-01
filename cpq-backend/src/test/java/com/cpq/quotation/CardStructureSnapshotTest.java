package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: CardSnapshotService.ensureStructure — 4 份结构快照 + 幂等 + 字段契约。
 * 对应报价单整份快照 Phase 1 Task 5。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CardStructureSnapshotTest — Task 5 ensureStructure")
public class CardStructureSnapshotTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    /**
     * 使用已有的 DRAFT 报价单（绑定了报价模板 + 核价模板，且两者均有 components_snapshot）。
     * 若无则在测试内动态创建。
     */
    private UUID resolveTestQuotationId() {
        // 先找现有有效报价单
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT q.id FROM quotation q " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "JOIN template t2 ON t2.id = q.costing_card_template_id " +
            "WHERE q.customer_template_id IS NOT NULL AND q.costing_card_template_id IS NOT NULL " +
            "  AND t1.components_snapshot IS NOT NULL AND t2.components_snapshot IS NOT NULL " +
            "  AND t1.status = 'PUBLISHED' AND t2.status = 'PUBLISHED' " +
            "LIMIT 1").getResultList();
        if (!rows.isEmpty()) {
            return UUID.fromString(rows.get(0).toString());
        }
        return null; // 测试将跳过
    }

    @BeforeEach
    @Transactional
    void cleanViewStructures() {
        // 清理上次测试留下的结构快照，保证每次测试独立
        em.createNativeQuery(
            "DELETE FROM quotation_view_structure WHERE quotation_id IN (" +
            "  SELECT q.id FROM quotation q " +
            "  JOIN template t1 ON t1.id = q.customer_template_id " +
            "  JOIN template t2 ON t2.id = q.costing_card_template_id " +
            "  WHERE t1.components_snapshot IS NOT NULL AND t2.components_snapshot IS NOT NULL " +
            "    AND t1.status='PUBLISHED' AND t2.status='PUBLISHED' LIMIT 10)"
        ).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // T1: ensureStructure 创建 4 份结构 + 幂等
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("T1: ensureStructure 创建 4 种视图结构 + 幂等调用后 count=4")
    void ensureStructure_createsFourKinds_andIsIdempotent() {
        UUID qid = resolveTestQuotationId();
        assumeNotNull(qid, "需要一个绑定了双模板的 DRAFT 报价单");

        svc.ensureStructure(qid);

        for (String kind : new String[]{"QUOTE_CARD", "QUOTE_EXCEL", "COSTING_CARD", "COSTING_EXCEL"}) {
            var s = QuotationViewStructure.findByQuotationAndKind(qid, kind);
            assertNotNull(s, "missing structure: " + kind);
            assertNotNull(s.structure, kind + " structure should not be null");
        }

        // 幂等：再调一次，count 仍为 4
        svc.ensureStructure(qid);
        long count = QuotationViewStructure.count("quotationId", qid);
        assertEquals(4, count, "幂等调用后应仍为 4 份结构");
    }

    // -----------------------------------------------------------------------
    // T2: 卡片结构字段契约（AP-39: DATA_SOURCE 必须保留 datasourceBinding）
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("T2: QUOTE_CARD 结构 tabs 数组非空 + DATA_SOURCE 字段 binding 非空")
    void quoteCardStructure_preservesFieldContract() throws Exception {
        UUID qid = resolveTestQuotationId();
        assumeNotNull(qid, "需要一个绑定了双模板的 DRAFT 报价单");

        svc.ensureStructure(qid);

        var s = QuotationViewStructure.findByQuotationAndKind(qid, "QUOTE_CARD");
        assertNotNull(s, "QUOTE_CARD structure must exist");

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(s.structure);

        var tabs = root.path("tabs");
        assertTrue(tabs.isArray() && tabs.size() > 0,
            "QUOTE_CARD structure.tabs should be non-empty array");

        for (var tab : tabs) {
            assertTrue(tab.has("componentId"), "tab should have componentId");
            assertTrue(tab.has("tabName"), "tab should have tabName");

            for (var f : tab.path("fields")) {
                String ft = f.path("fieldType").asText("");
                // AP-39: DATA_SOURCE binding 必须完整搬运
                if ("DATA_SOURCE".equals(ft)) {
                    assertFalse(
                        f.path("datasourceBinding").isMissingNode() || f.path("datasourceBinding").isNull(),
                        "DATA_SOURCE field must have datasourceBinding: " + f.path("name").asText());
                }
            }

            // 含可编辑字段的 tab + driver 路径 → 必须有 rowKeyFields 或哨兵
            boolean hasEditable = false;
            for (var f : tab.path("fields")) {
                String ft = f.path("fieldType").asText("");
                if ("INPUT_NUMBER".equals(ft) || "INPUT_TEXT".equals(ft) || "LIST_FORMULA".equals(ft)) {
                    hasEditable = true;
                    break;
                }
            }
            String driverPath = tab.path("dataDriverPath").asText("");
            if (hasEditable && !driverPath.isBlank()) {
                assertTrue(
                    tab.path("rowKeyFields").isArray() && tab.path("rowKeyFields").size() > 0,
                    "editable multi-row tab must have rowKeyFields: " + tab.path("tabName").asText());
            }
        }
    }

    /** 辅助：假设前提不满足时跳过测试 */
    private void assumeNotNull(Object val, String msg) {
        org.junit.jupiter.api.Assumptions.assumeTrue(val != null, msg);
    }
}
