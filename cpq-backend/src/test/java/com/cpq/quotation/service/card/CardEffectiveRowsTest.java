package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
}
