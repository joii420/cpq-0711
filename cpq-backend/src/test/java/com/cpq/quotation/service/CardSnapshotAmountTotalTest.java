package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B8.1（BL-0017 遗漏路径补齐）：验证 {@code CardSnapshotService.backfillSubtotalsFromResolved}
 * 现已登记 {@code ${key}#__amount_total__} 哨兵键（此前该类全类只有 buildTabNode:~1806 的"防泄漏排除"，
 * 无任何 put，导致 [页签(总计)] 公式在 quote_card_values 路径恒读不到值）。
 *
 * <p>照抄 {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} 的口径：
 * Σ 仅取 {@code is_amount && is_subtotal} 列，非全部 {@code is_subtotal} 列——本测试专门构造
 * 「一个金额列 + 一个非金额小计列」的页签，断言 SUBTOTAL 公式只累加金额列。
 */
@QuarkusTest
class CardSnapshotAmountTotalTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * 投料页签（c1/投料）：两个 is_subtotal 列——「金额」(is_amount=true，应计入 Σ)、
     * 「数量」(is_amount=false，不应计入 Σ)。
     * 汇总页签（c2/SUBTOTAL）：唯一公式 = component_subtotal(tab_name="投料", value="__amount_total__")，
     * 即 [投料(总计)]，期望只等于「金额」列之和（不含「数量」）。
     */
    private static final String SNAPSHOT = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"投料",
            "componentType":"NORMAL", "sortOrder":1,
            "fields":[
              {"name":"金额","field_type":"INPUT_NUMBER","is_amount":true,"is_subtotal":true,"sort_order":1},
              {"name":"数量","field_type":"INPUT_NUMBER","is_amount":false,"is_subtotal":true,"sort_order":2}
            ],
            "formulas":[], "formula_assignments":[] },
          { "componentId":"c2", "componentCode":"C2", "tabName":"汇总",
            "componentType":"SUBTOTAL", "sortOrder":2,
            "fields":[], "formula_assignments":[],
            "formulas":[ { "expression":[
              { "type":"component_subtotal", "tab_name":"投料", "value":"__amount_total__" }
            ] } ] } ]
        """;

    @Test
    void amountTotalSentinelOnlySumsAmountColumns() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();

        // 两行：金额 10/20（Σ=30），数量 3/4（Σ=7，不应计入 __amount_total__）。
        // INPUT_NUMBER 字段解析优先级：editValues → driverRow[name] → default_source(basicDataValues) → content
        // （见 FormulaCalculator#resolveRowByFieldName:1236-1268）；本测试字段未配 default_source，
        // 故值须放进 driverRow（而非 basicDataValues，那需要 default_source.path 才会被查到）。
        var r1 = M.createObjectNode();
        var dr1 = M.createObjectNode(); dr1.put("金额", new java.math.BigDecimal("10")); dr1.put("数量", new java.math.BigDecimal("3"));
        r1.set("driverRow", dr1);
        r1.set("basicDataValues", M.createObjectNode());
        baseRows.add(r1);

        var r2 = M.createObjectNode();
        var dr2 = M.createObjectNode(); dr2.put("金额", new java.math.BigDecimal("20")); dr2.put("数量", new java.math.BigDecimal("4"));
        r2.set("driverRow", dr2);
        r2.set("basicDataValues", M.createObjectNode());
        baseRows.add(r2);

        baseRowsByComp.put("c1", baseRows);
        baseRowsByComp.put("c2", M.createArrayNode()); // SUBTOTAL 组件恒 0 driver 行

        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));

        // 断言 1：汇总页签(c2) 的 subtotal = Σ「金额」列 = 30（不是 Σ 全部 is_subtotal 列 = 37）
        JsonNode tabs = root.path("tabs");
        JsonNode subtotalTab = null;
        for (JsonNode t : tabs) {
            if ("c2".equals(t.path("componentId").asText())) { subtotalTab = t; break; }
        }
        assertNotNull(subtotalTab, "汇总(SUBTOTAL)页签应出现在装配结果里");
        assertTrue(subtotalTab.has("subtotal"), "SUBTOTAL 页签应有 subtotal 字段");
        assertEquals(30.0, subtotalTab.path("subtotal").asDouble(), 0.0001,
            "[投料(总计)] 应只累加 is_amount=true 的「金额」列(10+20=30)，不含「数量」列(3+4=7)");

        // 断言 2（BL-0017 防泄漏回归）：投料页签(c1) 的 subtotalByColumn 不得出现 __amount_total__ 键，
        // 只应出现真实业务列「金额」「数量」——buildTabNode:~1806 的排除逻辑必须仍然生效。
        JsonNode normalTab = null;
        for (JsonNode t : tabs) {
            if ("c1".equals(t.path("componentId").asText())) { normalTab = t; break; }
        }
        assertNotNull(normalTab, "投料(NORMAL)页签应出现在装配结果里");
        JsonNode byCol = normalTab.path("subtotalByColumn");
        assertFalse(byCol.has("__amount_total__"),
            "__amount_total__ 是内部聚合键，不得泄漏进 subtotalByColumn（BL-0017 排除逻辑，防快照污染/golden 漂移）");
        assertTrue(byCol.has("金额") && byCol.has("数量"), "真实业务列仍应正常出现在 subtotalByColumn 里");
        assertEquals(30.0, byCol.path("金额").asDouble(), 0.0001);
        assertEquals(7.0, byCol.path("数量").asDouble(), 0.0001);
    }

    @Test
    void amountTotalSentinelPreservesTwelveDigitsThroughPass1AndBackfill() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        ((com.fasterxml.jackson.databind.node.ArrayNode) snapshot.get(0).path("fields")).add(
            M.createObjectNode()
                .put("name", "附加金额")
                .put("field_type", "INPUT_NUMBER")
                .put("is_amount", true)
                .put("is_subtotal", true)
                .put("sort_order", 3));
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();

        var row = M.createObjectNode();
        var driverRow = M.createObjectNode();
        driverRow.put("金额", new java.math.BigDecimal("0.040000000001"));
        driverRow.put("附加金额", new java.math.BigDecimal("0.043825536788"));
        driverRow.put("数量", new java.math.BigDecimal("7"));
        row.set("driverRow", driverRow);
        row.set("basicDataValues", M.createObjectNode());
        baseRows.add(row);
        baseRowsByComp.put("c1", baseRows);
        baseRowsByComp.put("c2", M.createArrayNode());

        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode subtotal = root.path("tabs").get(1).path("subtotal");

        assertEquals("0.083825536789", subtotal.asText());
        assertNotEquals("0.0838", subtotal.asText(),
            "CardSnapshot PASS1/backfill must not restore the legacy four-decimal value");
    }

    @Test
    void amountTotalEdgeMatrixPreservesExistingSemanticsThroughPass1AndBackfill() throws Exception {
        String snapshotJson = """
            [ { "componentId":"edge-detail", "componentCode":"EDGE", "tabName":"Edge detail",
                "componentType":"NORMAL", "sortOrder":1,
                "fields":[
                  {"name":"amountA","field_type":"INPUT_NUMBER","is_amount":true,"is_subtotal":true},
                  {"name":"amountB","field_type":"INPUT_NUMBER","is_amount":true,"is_subtotal":true}
                ],
                "formulas":[], "formula_assignments":[] },
              { "componentId":"edge-subtotal", "componentCode":"EDGE-ST", "tabName":"Edge subtotal",
                "componentType":"SUBTOTAL", "sortOrder":2, "fields":[], "formula_assignments":[],
                "formulas":[ { "expression":[
                  { "type":"component_subtotal", "component_code":"EDGE", "value":"__amount_total__" }
                ] } ] } ]
            """;
        List<AmountEdgeCase> cases = List.of(
            new AmountEdgeCase("empty", "[]", "0"),
            new AmountEdgeCase("zero",
                "[{\"driverRow\":{\"amountA\":\"0\",\"amountB\":\"0\"},\"basicDataValues\":{}}]", "0"),
            new AmountEdgeCase("negative",
                "[{\"driverRow\":{\"amountA\":\"-1.2345\",\"amountB\":\"0.2345\"},\"basicDataValues\":{}}]", "-1"),
            new AmountEdgeCase("exact-four-decimals",
                "[{\"driverRow\":{\"amountA\":\"1.2000\",\"amountB\":\"0.0345\"},\"basicDataValues\":{}}]", "1.2345"));

        for (AmountEdgeCase edgeCase : cases) {
            JsonNode snapshot = M.readTree(snapshotJson);
            var rowsByComponent = new LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
            rowsByComponent.put("edge-detail",
                (com.fasterxml.jackson.databind.node.ArrayNode) M.readTree(edgeCase.rowsJson()));
            rowsByComponent.put("edge-subtotal", M.createArrayNode());

            JsonNode root = M.readTree(
                svc.assembleTabsWithFormulaResultsForTest(snapshot, rowsByComponent, null));
            JsonNode subtotal = root.path("tabs").get(1).path("subtotal");

            assertTrue(subtotal.isTextual(), edgeCase.name() + ": subtotal must be a decimal string");
            assertEquals(0, new BigDecimal(edgeCase.expected()).compareTo(new BigDecimal(subtotal.asText())),
                edgeCase.name() + ": actual=" + subtotal);
        }
    }

    private record AmountEdgeCase(String name, String rowsJson, String expected) {}

    /**
     * 回归：零 is_subtotal 列的普通页签（无金额列可言）不应因本次改动而报错或产生非零哨兵值——
     * 空 subtotalFields 早退分支同样要正确登记 0（详见 backfillSubtotalsFromResolved 改动说明）。
     */
    @Test
    void noSubtotalColumnsStillAssemblesWithoutError() throws Exception {
        String snap = """
            [ { "componentId":"c1", "componentCode":"C1", "tabName":"产品",
                "componentType":"NORMAL", "sortOrder":1,
                "fields":[ {"name":"型号","field_type":"INPUT_TEXT","is_amount":false,"is_subtotal":false} ],
                "formulas":[], "formula_assignments":[] } ]
            """;
        JsonNode snapshot = M.readTree(snap);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();
        var r = M.createObjectNode();
        r.set("driverRow", M.createObjectNode());
        var bdv = M.createObjectNode(); bdv.put("型号", "X1"); r.set("basicDataValues", bdv);
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        assertNotNull(root.path("tabs").get(0), "无 is_subtotal 列的页签仍应正常装配，不抛异常");
    }
}
