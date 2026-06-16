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

    // -----------------------------------------------------------------------
    // T3: 并发竞态防御 — 重复插入同 (quotation, view_kind) 必须幂等不抛、不污染事务
    //   复现根因：并发两线程都通过 findByQuotationAndKind 检查后各自插入 → 第二个撞
    //   uq_quotation_view_structure(23505) → PG 标记事务 aborted(25P02) → 同事务后续
    //   核价值写入(buildCostingCardValues 的 UPDATE)全失败 → costing_* 永久空。
    //   修复：插入走 ON CONFLICT (quotation_id, view_kind) DO NOTHING，撞键不抛错。
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @Transactional
    @DisplayName("T3: persistStructureIdempotent 重复插同 key 不抛错且只留 1 行（事务不被污染）")
    void persistStructureIdempotent_duplicate_noThrow_singleRow() {
        UUID qid = resolveTestQuotationId();
        assumeNotNull(qid, "需要一个绑定了双模板的 DRAFT 报价单");

        svc.persistStructureIdempotent(qid, "QUOTE_CARD", "{}");
        // 模拟并发：第二个线程同样通过了存在性检查、来插同一 key
        assertDoesNotThrow(
            () -> svc.persistStructureIdempotent(qid, "QUOTE_CARD", "{}"),
            "重复插入同一 (quotation,view_kind) 不应抛异常（应 ON CONFLICT DO NOTHING）");

        long count = QuotationViewStructure.count(
            "quotationId = ?1 and viewKind = ?2", qid, "QUOTE_CARD");
        assertEquals(1, count, "重复插入后应只有 1 行（不重复、不丢）");
    }

    // -----------------------------------------------------------------------
    // T4: 单位换算绑定 unit_source_field 必须搬进 QUOTE_CARD 结构（unitSourceField）。
    //   回归（2026-06-17）：前端「结构脱钩」(659cb09) 改读 quotation_view_structure 建 componentData，
    //   而 buildCardStructure 的字段序列化漏搬 unit_source_field → 结构无绑定 →
    //   buildComponentDataFromStructure 拿到的 comp.fields 无 unit_source_field →
    //   applyUnitConversion 空操作 → 前端实时重算用原值（g/pcs 未 ×0.001 归一 kg/pcs）→ 产品小计虚高 ~1000x。
    //   后端保存计算读 components_snapshot（有绑定）仍正确，故落库值对、仅前端实时视图错。
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("T4: 组件字段 unit_source_field 必须搬进 QUOTE_CARD 结构(unitSourceField)")
    void quoteCardStructure_propagatesUnitSourceField() throws Exception {
        // 找一张 DRAFT 报价单，其报价模板 components_snapshot 含配了 unit_source_field 的字段
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT q.id FROM quotation q " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "WHERE q.status='DRAFT' AND t1.components_snapshot::text LIKE '%unit_source_field%' " +
            "LIMIT 1").getResultList();
        org.junit.jupiter.api.Assumptions.assumeTrue(!rows.isEmpty(),
            "需要一张 DRAFT 报价单且其报价模板含配了 unit_source_field 的字段");
        UUID qid = UUID.fromString(rows.get(0).toString());

        // 草稿：rebuildStructureForDraft 删旧 4 份后重插，避免读到漏搬的冻结旧结构。
        svc.rebuildStructureForDraft(qid);

        var s = QuotationViewStructure.findByQuotationAndKind(qid, "QUOTE_CARD");
        assertNotNull(s, "QUOTE_CARD structure must exist");
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s.structure);

        boolean found = false;
        for (var tab : root.path("tabs")) {
            for (var f : tab.path("fields")) {
                if (!f.path("unitSourceField").asText("").isBlank()) { found = true; break; }
            }
            if (found) break;
        }
        assertTrue(found,
            "QUOTE_CARD 结构必须把组件 unit_source_field 搬为 unitSourceField"
            + "（前端结构脱钩路径据此实时换算；漏搬则净用量等列实时重算用原值）");
    }

    /** 辅助：假设前提不满足时跳过测试 */
    private void assumeNotNull(Object val, String msg) {
        org.junit.jupiter.api.Assumptions.assumeTrue(val != null, msg);
    }
}
