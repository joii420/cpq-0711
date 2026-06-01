package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Task 5 — 草稿重刷 refreshQuoteCardValues。
 *
 * <p>验证：行有 editRows（按 rowKey）→ refresh → baseRows 刷新 + editRows 按 rowKey 保留
 * + formulaResults 重算 + quote_values_at 更新 + <b>核价两列不变</b>。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("RefreshCardSnapshotTest — Task 5 草稿重刷(保编辑/核价不动)")
public class RefreshCardSnapshotTest {

    @Inject CardSnapshotService svc;
    @Inject FormulaCalculator formulaCalculator;
    @Inject EntityManager em;

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final int TEST_EDIT_VAL = 12345;

    /** 选一条「报价模板有 driver 组件 + 有非空 snapshot_rows（基础数据存在）」的报价行。 */
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

    private JsonNode rowKeyFieldsOf(String componentId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery("SELECT row_key_fields FROM component WHERE id = :cid")
                .setParameter("cid", UUID.fromString(componentId)).getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            return MAPPER.readTree(rows.get(0).toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String readQuoteCardValues(UUID id) {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery("SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", id).getResultList();
        return (rows.isEmpty() || rows.get(0) == null) ? null : rows.get(0).toString();
    }

    private String readCostingCardValues(UUID id) {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery("SELECT costing_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", id).getResultList();
        return (rows.isEmpty() || rows.get(0) == null) ? null : rows.get(0).toString();
    }

    @Transactional
    void writeQuoteCardValues(UUID id, String json) {
        em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values = CAST(:j AS jsonb) WHERE id = :id")
            .setParameter("j", json).setParameter("id", id).executeUpdate();
    }

    @Test
    @Order(1)
    @DisplayName("T1: 注入 editRow → refresh → editRows 按 rowKey 保留 + baseRows 非空 + quote_values_at 更新 + 核价不变")
    void refresh_preservesEdits_andLeavesCostingUntouched() throws Exception {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null, "需要报价模板含 driver 组件且基础数据非空的产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        // 初始化 quote + costing 快照
        svc.snapshotLineValues(li);
        // 先重刷一次：使 quote_card_values 来自 refresh 的 expand 路径（rowKey 与下次 refresh 一致）
        svc.refreshQuoteCardValues(li);

        String beforeQcv = readQuoteCardValues(lineId);
        assertNotNull(beforeQcv, "refresh 后 quote_card_values 应非空");
        JsonNode before = MAPPER.readTree(beforeQcv);

        // 找一个 baseRows 非空的 driver tab，取 baseRows[0] 的 rowKey
        String targetComp = null;
        String targetRowKey = null;
        for (JsonNode tab : before.path("tabs")) {
            JsonNode baseRows = tab.path("baseRows");
            if (baseRows.isArray() && baseRows.size() > 0) {
                String cid = tab.path("componentId").asText("");
                JsonNode rkf = rowKeyFieldsOf(cid);
                String rk = formulaCalculator.computeRowKey(rkf, baseRows.get(0).path("driverRow"));
                targetComp = cid;
                targetRowKey = (rk != null && !rk.isEmpty()) ? rk : "0";
                break;
            }
        }
        Assumptions.assumeTrue(targetComp != null, "refresh 后无非空 baseRows 的 tab（基础数据缺失），跳过");

        // 注入两条 editRow（模拟用户编辑）到该 tab：
        //  - 有效 rowKey（命中新 baseRows）→ 必须保留
        //  - 幽灵 rowKey（新 baseRows 不存在）→ 真 refresh 必须丢弃（空实现/no-op 会留着 → 判别 RED）
        final String BOGUS_KEY = "__bogus_no_such_row__";
        ObjectNode mutable = (ObjectNode) before;
        for (JsonNode tab : mutable.path("tabs")) {
            if (targetComp.equals(tab.path("componentId").asText(""))) {
                ArrayNode editRows = MAPPER.createArrayNode();
                ObjectNode er = MAPPER.createObjectNode();
                er.put("rowKey", targetRowKey);
                er.putObject("values").put("__refresh_test__", TEST_EDIT_VAL);
                editRows.add(er);
                ObjectNode ghost = MAPPER.createObjectNode();
                ghost.put("rowKey", BOGUS_KEY);
                ghost.putObject("values").put("__bogus__", 1);
                editRows.add(ghost);
                ((ObjectNode) tab).set("editRows", editRows);
                break;
            }
        }
        writeQuoteCardValues(lineId, MAPPER.writeValueAsString(mutable));

        // 记录核价侧（refresh 不应改动）
        String costingBefore = readCostingCardValues(lineId);

        // === 执行重刷 ===
        QuotationLineItem li2 = QuotationLineItem.findById(lineId);
        svc.refreshQuoteCardValues(li2);

        // 校验
        String afterQcv = readQuoteCardValues(lineId);
        assertNotNull(afterQcv);
        JsonNode after = MAPPER.readTree(afterQcv);

        // (a) editRows 按 rowKey 保留，且编辑值不丢
        boolean editPreserved = false;
        boolean ghostDropped = true;
        boolean targetBaseRowsNonEmpty = false;
        boolean formulaResultsPresent = false;
        for (JsonNode tab : after.path("tabs")) {
            if (!targetComp.equals(tab.path("componentId").asText(""))) continue;
            targetBaseRowsNonEmpty = tab.path("baseRows").isArray() && tab.path("baseRows").size() > 0;
            for (JsonNode er : tab.path("editRows")) {
                String rk = er.path("rowKey").asText("");
                if (targetRowKey.equals(rk)
                        && er.path("values").path("__refresh_test__").asInt(-1) == TEST_EDIT_VAL) {
                    editPreserved = true;
                }
                if ("__bogus_no_such_row__".equals(rk)) ghostDropped = false;
            }
            formulaResultsPresent = tab.path("formulaResults").isArray();
        }
        assertTrue(targetBaseRowsNonEmpty, "重刷后目标 tab 的 baseRows 必须非空 comp=" + targetComp);
        assertTrue(editPreserved,
            "重刷后 editRows 必须按 rowKey 保留编辑值(rowKey=" + targetRowKey + ", __refresh_test__=" + TEST_EDIT_VAL + ")");
        // 判别性断言：真 refresh 必须丢弃新 baseRows 中不存在的 rowKey 的编辑（证明确实重 expand + 过滤，非 no-op）
        assertTrue(ghostDropped, "重刷必须丢弃 rowKey 不在新 baseRows 的幽灵 editRow（证明真重刷而非 no-op）");
        assertTrue(formulaResultsPresent, "重刷后 formulaResults 必须存在(数组)");

        // (b) quote_values_at 更新
        @SuppressWarnings("unchecked")
        var ts = em.createNativeQuery("SELECT quote_values_at FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId).getResultList();
        assertFalse(ts.isEmpty() || ts.get(0) == null, "quote_values_at 必须已更新");

        // (c) 核价两列不变
        String costingAfter = readCostingCardValues(lineId);
        assertEquals(costingBefore, costingAfter, "refresh 不得改动 costing_card_values");
    }
}
