package com.cpq.quotation;

import com.cpq.component.entity.Component;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

    /**
     * 用 4-arg 重载（字段感知）计算 rowKey，与 CardSnapshotService 内部口径一致。
     * 需要从 quote_card_structure（组件字段定义）里取 fieldsDef。
     */
    private String computeRowKeyFieldAware(UUID lineItemId, String componentId,
                                           JsonNode driverRow, JsonNode basicDataValues) {
        try {
            @SuppressWarnings("unchecked")
            var structRows = em.createNativeQuery(
                "SELECT t1.components_snapshot FROM quotation_line_item li " +
                "JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN template t1 ON t1.id = q.customer_template_id WHERE li.id = :id")
                .setParameter("id", lineItemId).getResultList();
            if (structRows.isEmpty() || structRows.get(0) == null) return null;
            JsonNode snapshot = MAPPER.readTree(structRows.get(0).toString());
            JsonNode fieldsDef = null;
            for (JsonNode tab : snapshot) {
                if (componentId.equals(tab.path("componentId").asText(""))) {
                    fieldsDef = tab.path("fields");
                    break;
                }
            }
            JsonNode rkf = rowKeyFieldsOf(componentId);
            String rk = formulaCalculator.computeRowKey(rkf, fieldsDef, driverRow, basicDataValues);
            return (rk != null && !rk.isEmpty()) ? rk : null;
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

        // 找一个 baseRows 非空的 driver tab，取 baseRows[0] 的 rowKey（用 4-arg 字段感知重载，与 refresh 内部口径一致）
        String targetComp = null;
        String targetRowKey = null;
        for (JsonNode tab : before.path("tabs")) {
            JsonNode baseRows = tab.path("baseRows");
            if (baseRows.isArray() && baseRows.size() > 0) {
                String cid = tab.path("componentId").asText("");
                JsonNode br0 = baseRows.get(0);
                String rk = computeRowKeyFieldAware(lineId, cid,
                        br0.path("driverRow"), br0.path("basicDataValues"));
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
                ghost.putObject("values").put("__bogus__", new java.math.BigDecimal("1"));
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

    @Test
    @Order(2)
    @DisplayName("T2: refreshDraftQuoteCards — 可渲染 DRAFT 返回实际 UPDATED 数；非 DRAFT/不存在 → no-op 返 0")
    void refreshDraftQuoteCards_draftGate() {
        DraftGateFixture fixture = createDraftGateFixture(true);
        try {
            int refreshed = svc.refreshDraftQuoteCards(fixture.quotationId());
            assertEquals(1, refreshed, "fixture 只有一条可渲染行，实际 UPDATED 数必须为 1");
            assertNotNull(readQuoteValuesAt(fixture.lineItemId()),
                "UPDATED 行必须真实写入 quote_values_at，不能只返回扫描行数");

            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                    "UPDATE quotation SET status='SUBMITTED' WHERE id=:id")
                .setParameter("id", fixture.quotationId()).executeUpdate());
            String nonDraftBefore = draftGateFingerprint(fixture.quotationId());
            assertEquals(0, svc.refreshDraftQuoteCards(fixture.quotationId()),
                "非 DRAFT 报价单必须 no-op 返 0");
            assertEquals(nonDraftBefore, draftGateFingerprint(fixture.quotationId()),
                "非 DRAFT no-op 不得改动报价头、行、组件数据或结构");
        } finally {
            cleanupDraftGateFixture(fixture);
        }

        // 不存在 → 0
        assertEquals(0, svc.refreshDraftQuoteCards(UUID.randomUUID()), "不存在报价单返 0");
    }

    @Test
    @Order(4)
    @DisplayName("T4: PUBLISHED 未冻结兼容态 → 返回 0 且全持久化指纹不变")
    void refreshDraftQuoteCards_unfrozenPublishedTemplate_isCompatibleNoOp() {
        DraftGateFixture fixture = createDraftGateFixture(false);
        try {
            String before = draftGateFingerprint(fixture.quotationId());
            assertEquals(0, svc.refreshDraftQuoteCards(fixture.quotationId()),
                "历史 PUBLISHED 未冻结模板应兼容 no-op，UPDATED 数必须为 0");
            assertEquals(before, draftGateFingerprint(fixture.quotationId()),
                "未冻结兼容 no-op 必须保持报价头、全部行、组件数据和结构全指纹不变");
        } finally {
            cleanupDraftGateFixture(fixture);
        }
    }

    private DraftGateFixture createDraftGateFixture(boolean frozen) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Component component = new Component();
            component.name = "Refresh draft gate deterministic component";
            component.code = "RCSDG-" + UUID.randomUUID().toString().substring(0, 8);
            component.fields = "[]";
            component.formulas = "[]";
            component.persist();

            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = "Refresh draft gate " + (frozen ? "frozen" : "unfrozen");
            template.templateKind = "QUOTATION";
            template.status = "PUBLISHED";
            template.componentsSnapshot = frozen
                ? "[{\"id\":\"" + UUID.randomUUID()
                    + "\",\"componentId\":\"" + component.id + "\",\"componentName\":\""
                    + component.name + "\",\"componentCode\":\"" + component.code
                    + "\",\"componentType\":\"NORMAL\",\"tabName\":\"报价\","
                    + "\"sortOrder\":0,\"fields\":[],\"formulas\":[],\"formula_assignments\":{}}]"
                : "[]";
            template.sqlViewsSnapshot = "{}";
            template.templateSqlViewsSnapshot = "{}";
            template.excelViewConfig = "[]";
            template.persist();

            TemplateComponent mounted = new TemplateComponent();
            mounted.templateId = template.id;
            mounted.componentId = component.id;
            mounted.tabName = "报价";
            mounted.sortOrder = 0;
            mounted.persist();

            if (frozen) {
                TemplateComponentSnapshot snapshot = new TemplateComponentSnapshot();
                snapshot.templateId = template.id;
                snapshot.templateComponentId = mounted.id;
                snapshot.componentId = component.id;
                snapshot.sortOrder = 0;
                snapshot.tabName = "报价";
                snapshot.componentName = component.name;
                snapshot.componentCode = component.code;
                snapshot.componentType = "NORMAL";
                snapshot.fields = "[]";
                snapshot.formulas = "[]";
                snapshot.persist();
            }

            Object customerId = em.createNativeQuery("SELECT id FROM customer ORDER BY id LIMIT 1").getSingleResult();
            Object salesRepId = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1").getSingleResult();
            Quotation quotation = new Quotation();
            quotation.quotationNumber = "RCSDG-" + UUID.randomUUID();
            quotation.customerId = customerId instanceof UUID id ? id : UUID.fromString(customerId.toString());
            quotation.name = "Refresh draft gate deterministic quotation";
            quotation.salesRepId = salesRepId instanceof UUID id ? id : UUID.fromString(salesRepId.toString());
            quotation.status = "DRAFT";
            quotation.customerTemplateId = template.id;
            quotation.persist();

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.templateId = template.id;
            line.productNameSnapshot = "Refresh draft gate product";
            line.productPartNoSnapshot = "RCSDG-P1";
            line.sortOrder = 0;
            line.cardSnapshotAt = OffsetDateTime.now().minusHours(1);
            line.quoteCardValues = "{\"tabs\":[],\"marker\":\"before-refresh\"}";
            line.persist();
            return new DraftGateFixture(quotation.id, line.id, template.id, mounted.id, component.id);
        });
    }

    private void cleanupDraftGateFixture(DraftGateFixture fixture) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation WHERE id=:id")
                .setParameter("id", fixture.quotationId()).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id=:id")
                .setParameter("id", fixture.templateId()).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component WHERE id=:id")
                .setParameter("id", fixture.templateComponentId()).executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE id=:id")
                .setParameter("id", fixture.templateId()).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE id=:id")
                .setParameter("id", fixture.componentId()).executeUpdate();
        });
    }

    private OffsetDateTime readQuoteValuesAt(UUID lineItemId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object value = em.createNativeQuery(
                    "SELECT quote_values_at FROM quotation_line_item WHERE id=:id")
                .setParameter("id", lineItemId).getSingleResult();
            if (value == null) return null;
            if (value instanceof OffsetDateTime odt) return odt;
            if (value instanceof java.time.Instant instant) return instant.atOffset(java.time.ZoneOffset.UTC);
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(value.toString().replace(" ", "T"));
        });
    }

    private String draftGateFingerprint(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> String.join("|",
            tableFingerprint("quotation", "id=:id", quotationId),
            tableFingerprint("quotation_line_item", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_line_component_data",
                "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)", quotationId),
            tableFingerprint("quotation_view_structure", "quotation_id=:id", quotationId)));
    }

    private String tableFingerprint(String table, String predicate, UUID quotationId) {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT count(*),COALESCE(md5(string_agg(xmin::text || ':' || md5(to_jsonb(t)::text),"
                    + "'|' ORDER BY to_jsonb(t)::text)),'none') FROM " + table + " t WHERE " + predicate)
            .setParameter("id", quotationId).getSingleResult();
        return row[0] + ":" + row[1];
    }

    private record DraftGateFixture(UUID quotationId, UUID lineItemId, UUID templateId,
                                    UUID templateComponentId, UUID componentId) {}

    /** DRAFT 态 + 报价模板含 driver 组件 + snapshot_rows 非空 的产品行（editCardValue 需 DRAFT）。 */
    private UUID resolveDraftLineItemId() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.id FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "WHERE q.status='DRAFT' AND t1.components_snapshot IS NOT NULL " +
            "  AND EXISTS (SELECT 1 FROM quotation_line_component_data d " +
            "    WHERE d.line_item_id = li.id AND d.snapshot_rows IS NOT NULL " +
            "      AND jsonb_typeof(d.snapshot_rows)='array' AND jsonb_array_length(d.snapshot_rows) > 0) " +
            "LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @Test
    @Order(3)
    @DisplayName("T3: editCardValue — 写 editRows + 重算 formulaResults/报价Excel + 持久化 + 核价不变")
    void editCardValue_writesEdit_recomputes_costingUntouched() throws Exception {
        UUID lineId = resolveDraftLineItemId();
        Assumptions.assumeTrue(lineId != null, "需要 DRAFT 态含 driver 组件的产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);
        svc.refreshQuoteCardValues(li);

        // 选 baseRows 非空的 driver tab + 其 baseRows[0] 的 rowKey
        String qcv = readQuoteCardValues(lineId);
        Assumptions.assumeTrue(qcv != null, "quote_card_values 应非空");
        JsonNode card = MAPPER.readTree(qcv);
        String comp = null, rk = null;
        for (JsonNode tab : card.path("tabs")) {
            JsonNode baseRows = tab.path("baseRows");
            if (baseRows.isArray() && baseRows.size() > 0) {
                comp = tab.path("componentId").asText("");
                JsonNode br0 = baseRows.get(0);
                String k = computeRowKeyFieldAware(lineId, comp,
                        br0.path("driverRow"), br0.path("basicDataValues"));
                rk = (k != null && !k.isEmpty()) ? k : "0";
                break;
            }
        }
        Assumptions.assumeTrue(comp != null, "无非空 baseRows 的 tab，跳过");

        String costingBefore = readCostingCardValues(lineId);

        // === 执行编辑回写 ===
        var result = svc.editCardValue(lineId, comp, rk, "__edit_test__", 777);

        assertNotNull(result, "DRAFT 编辑回写应返回结果");
        assertTrue(result.containsKey("quoteCardValues"), "返回值应含 quoteCardValues");
        assertTrue(result.containsKey("quoteExcelValues"), "返回值应含 quoteExcelValues");

        // (a) 返回的 quoteCardValues 含写入的 editRow
        JsonNode after = MAPPER.readTree(result.get("quoteCardValues").toString());
        boolean editWritten = false;
        boolean formulaResultsPresent = false;
        for (JsonNode tab : after.path("tabs")) {
            if (!comp.equals(tab.path("componentId").asText(""))) continue;
            for (JsonNode er : tab.path("editRows")) {
                if (rk.equals(er.path("rowKey").asText(""))
                        && er.path("values").path("__edit_test__").asInt(-1) == 777) {
                    editWritten = true;
                }
            }
            formulaResultsPresent = tab.path("formulaResults").isArray();
        }
        assertTrue(editWritten, "editRows 必须写入编辑值(rowKey=" + rk + ", __edit_test__=777)");
        assertTrue(formulaResultsPresent, "编辑后 formulaResults 必须存在(重算)");

        // (b) 持久化：DB 重读应一致
        String persisted = readQuoteCardValues(lineId);
        assertTrue(persisted != null && persisted.contains("__edit_test__"),
            "编辑值必须持久化到 DB quote_card_values");

        // (c) quote_values_at 更新
        @SuppressWarnings("unchecked")
        var ts = em.createNativeQuery("SELECT quote_values_at FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId).getResultList();
        assertFalse(ts.isEmpty() || ts.get(0) == null, "quote_values_at 必须已更新");

        // (d) 核价两列不变
        assertEquals(costingBefore, readCostingCardValues(lineId), "编辑不得改动 costing_card_values");
    }
}
