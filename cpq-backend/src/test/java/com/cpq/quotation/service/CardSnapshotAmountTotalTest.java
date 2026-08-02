package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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
        var dr1 = M.createObjectNode(); dr1.put("金额", 10); dr1.put("数量", 3);
        r1.set("driverRow", dr1);
        r1.set("basicDataValues", M.createObjectNode());
        baseRows.add(r1);

        var r2 = M.createObjectNode();
        var dr2 = M.createObjectNode(); dr2.put("金额", 20); dr2.put("数量", 4);
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
