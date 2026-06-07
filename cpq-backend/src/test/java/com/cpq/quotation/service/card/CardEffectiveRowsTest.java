package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CardEffectiveRowsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String COMPONENTS_SNAPSHOT = """
        [ { "componentId": "comp-A", "sortOrder": 2, "tabName": "元素" } ]
        """;

    private static final String CARD_VALUES = """
        { "tabs": [ {
            "componentId": "comp-A",
            "tabName": "元素",
            "baseRows": [ { "driverRow": {"hf_part_no":"P1"},
                            "basicDataValues": {"类型":"非银点类","含量":0.5} } ],
            "editRows": [ { "rowKey": "0", "values": {"单价":12.3} } ],
            "formulaResults": [ { "rowKey": "0", "values": {"金额":6.15} } ]
        } ] }
        """;

    @Test
    void parsesEffectiveRowsKeyedByComponentIdAndSortOrder() throws Exception {
        JsonNode cardValues = M.readTree(CARD_VALUES);
        JsonNode componentsSnapshot = M.readTree(COMPONENTS_SNAPSHOT);

        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, componentsSnapshot, (cid) -> null);

        CardEffectiveRows.TabRows tab = result.get("comp-A:2");
        assertNotNull(tab, "应按 componentId:sortOrder 建键");
        assertEquals(1, tab.rows.size());

        Map<String, Object> row0 = tab.rows.get(0);
        assertEquals("非银点类", row0.get("类型"));
        assertEquals(0.5, ((Number) row0.get("含量")).doubleValue(), 1e-9);
        assertEquals(12.3, ((Number) row0.get("单价")).doubleValue(), 1e-9);
        assertEquals(6.15, ((Number) row0.get("金额")).doubleValue(), 1e-9);
        assertEquals("P1", row0.get("hf_part_no"));
    }

    @Test
    void editRowsOverrideBasicAndFormula() throws Exception {
        String cv = CARD_VALUES.replace("\"单价\":12.3", "\"单价\":12.3,\"含量\":9.9");
        JsonNode cardValues = M.readTree(cv);
        JsonNode cs = M.readTree(COMPONENTS_SNAPSHOT);

        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, cs, (cid) -> null);
        Map<String, Object> row0 = result.get("comp-A:2").rows.get(0);
        assertEquals(9.9, ((Number) row0.get("含量")).doubleValue(), 1e-9,
            "editRows 同名字段应覆盖 basicDataValues");
    }

    // ── P2-B 核价 Excel 树：filterByNodeId 按 spine 节点过滤有效行 ──────────────
    private Map<String, Object> nodeRow(String nodeId, String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("__nodeId", nodeId);
        m.put(k, v);
        return m;
    }

    @Test
    void filterByNodeId_keepsOnlyMatchingNode() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("c1:0", new CardEffectiveRows.TabRows(new ArrayList<>(List.of(
            nodeRow("e1", "含量", 10), nodeRow("e1", "含量", 20), nodeRow("e2", "含量", 99))), null));

        Map<String, CardEffectiveRows.TabRows> only = CardEffectiveRows.filterByNodeId(eff, "e1");
        assertEquals(2, only.get("c1:0").rows.size(), "应只留 __nodeId=e1 的两行");
        assertTrue(only.get("c1:0").rows.stream().allMatch(r -> "e1".equals(r.get("__nodeId"))));

        // 无匹配节点 → 行列表为空但保留 tab key（节点该组件无数据 → 列空白）
        Map<String, CardEffectiveRows.TabRows> none = CardEffectiveRows.filterByNodeId(eff, "eX");
        assertTrue(none.containsKey("c1:0"));
        assertEquals(0, none.get("c1:0").rows.size());

        // 原 eff 不被改动
        assertEquals(3, eff.get("c1:0").rows.size(), "filterByNodeId 不应改原 eff");
    }

    @Test
    void filterByNodeId_nullSafe() {
        assertTrue(CardEffectiveRows.filterByNodeId(null, "e1").isEmpty());
    }

    @Test
    void parse_carriesNodeIdFromBaseRowFallbackPath() throws Exception {
        // 无 resolvedRows → 走回退路径，应把 baseRow 的 __nodeId 带入有效行
        String cv = """
            { "tabs": [ {
                "componentId": "comp-A", "tabName": "元素",
                "baseRows": [ { "__nodeId": "e1/e2", "driverRow": {"hf_part_no":"P1"},
                                "basicDataValues": {"含量":0.5} } ]
            } ] }
            """;
        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(M.readTree(cv), M.readTree(COMPONENTS_SNAPSHOT), (cid) -> null);
        assertEquals("e1/e2", result.get("comp-A:2").rows.get(0).get("__nodeId"));
    }
}
