package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CardEffectiveRowsResolvedTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String COMPONENTS_SNAPSHOT = """
        [ { "componentId":"c1","sortOrder":2,"tabName":"元素" } ]
        """;

    // tab 带 resolvedRows: 两行, 类型 已解析成干净字段名标量
    private static final String CARD_VALUES = """
        { "tabs":[ {
            "componentId":"c1","tabName":"元素",
            "baseRows":[ {"driverRow":{"元素":"Ag","material_type":"银点类"},"basicDataValues":{}},
                         {"driverRow":{"元素":"Cu","material_type":"非银点类"},"basicDataValues":{}} ],
            "editRows":[], "formulaResults":[],
            "resolvedRows":[ {"元素":"Ag","含量":75,"类型":"银点类"},
                             {"元素":"Cu","含量":70,"类型":"非银点类"} ],
            "subtotal": 145
        } ] }
        """;

    @Test
    void prefersResolvedRowsKeyedByFieldName() throws Exception {
        JsonNode cv = M.readTree(CARD_VALUES);
        JsonNode cs = M.readTree(COMPONENTS_SNAPSHOT);
        Map<String, CardEffectiveRows.TabRows> res =
            CardEffectiveRows.parse(cv, cs, (cid) -> null);

        CardEffectiveRows.TabRows tab = res.get("c1:2");
        assertNotNull(tab);
        assertEquals(2, tab.rows.size());
        // 直接用 resolvedRows: 干净字段名 类型(非 material_type)
        assertEquals("银点类", tab.rows.get(0).get("类型"));
        assertEquals("非银点类", tab.rows.get(1).get("类型"));
        assertEquals(75, ((Number) tab.rows.get(0).get("含量")).intValue());
        assertEquals(0, new java.math.BigDecimal("145").compareTo(tab.subtotal));
    }

    @Test
    void fallsBackToMergeWhenNoResolvedRows() throws Exception {
        // 旧快照: 无 resolvedRows → 回退合并 driverRow/basicDataValues
        String legacy = """
            { "tabs":[ {
                "componentId":"c1","tabName":"元素",
                "baseRows":[ {"driverRow":{"元素":"Ag","含量":75},"basicDataValues":{}} ],
                "editRows":[], "formulaResults":[]
            } ] }
            """;
        JsonNode cv = M.readTree(legacy);
        JsonNode cs = M.readTree(COMPONENTS_SNAPSHOT);
        Map<String, CardEffectiveRows.TabRows> res =
            CardEffectiveRows.parse(cv, cs, (cid) -> null);
        // 回退路径仍能从 driverRow 取到 含量
        assertEquals(75, ((Number) res.get("c1:2").rows.get(0).get("含量")).intValue());
    }
}
