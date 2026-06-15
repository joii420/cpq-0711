package com.cpq.quotation.card;

import com.cpq.quotation.service.card.CardEffectiveRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: CardEffectiveRows.parse 单位换算物化点4。
 * 纯静态，无 DB。
 */
class CardEffectiveRowsUnitConversionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 4-arg parse（含 fieldsOf）：resolvedRows 路径下 重量=500g → 换算后应为 0.5（KG归一）。
     */
    @Test
    void parse4Arg_resolvedRows_convertUnit() throws Exception {
        // cardValues: tab c1, resolvedRows 含 重量=500, 单位="g"
        JsonNode cardValues = MAPPER.readTree(
            "{\"tabs\":[{\"componentId\":\"c1\",\"resolvedRows\":[{\"重量\":500,\"单位\":\"g\"}]}]}"
        );

        // componentsSnapshot: c1 sortOrder=0, fields 含 重量(unit_source_field=单位) + 单位
        JsonNode componentsSnapshot = MAPPER.readTree(
            "[{\"componentId\":\"c1\",\"sortOrder\":0,\"fields\":[" +
            "{\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"}," +
            "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\"}" +
            "]}]"
        );

        // Build fieldsOf from componentsSnapshot (mirrors Part B logic)
        java.util.Map<String, JsonNode> fieldsByCid = new java.util.HashMap<>();
        for (JsonNode c : componentsSnapshot) {
            String cid = c.path("componentId").asText("");
            if (!cid.isBlank()) fieldsByCid.put(cid, c.path("fields"));
        }
        Function<String, JsonNode> fieldsOf = fieldsByCid::get;

        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, componentsSnapshot, cid -> null, fieldsOf);

        assertNotNull(result, "result should not be null");
        CardEffectiveRows.TabRows tab = result.get("c1:0");
        assertNotNull(tab, "tab 'c1:0' should exist");
        assertEquals(1, tab.rows.size(), "should have 1 row");

        Map<String, Object> row = tab.rows.get(0);
        Object weightVal = row.get("重量");
        assertNotNull(weightVal, "重量 should not be null after conversion");
        // 500g → KG: 500 * 0.001 = 0.5
        assertEquals(0, new BigDecimal(weightVal.toString()).compareTo(new BigDecimal("0.5")),
            "重量 should be 0.5 (KG-normalized from 500g) but was: " + weightVal);

        // 单位 field stays unchanged
        assertEquals("g", row.get("单位").toString(), "单位 should remain 'g'");
    }

    /**
     * 3-arg parse（无 fieldsOf）：resolvedRows 路径下 重量=500 应原样保留（无换算）。
     */
    @Test
    void parse3Arg_noFieldsOf_noConversion() throws Exception {
        JsonNode cardValues = MAPPER.readTree(
            "{\"tabs\":[{\"componentId\":\"c1\",\"resolvedRows\":[{\"重量\":500,\"单位\":\"g\"}]}]}"
        );
        JsonNode componentsSnapshot = MAPPER.readTree(
            "[{\"componentId\":\"c1\",\"sortOrder\":0,\"fields\":[" +
            "{\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"}," +
            "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\"}" +
            "]}]"
        );

        // 3-arg overload: fieldsOf is implicitly null
        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, componentsSnapshot, cid -> null);

        CardEffectiveRows.TabRows tab = result.get("c1:0");
        assertNotNull(tab, "tab 'c1:0' should exist");
        Map<String, Object> row = tab.rows.get(0);
        Object weightVal = row.get("重量");
        assertNotNull(weightVal, "重量 should not be null");
        // No conversion: should remain 500
        assertEquals(0, new BigDecimal(weightVal.toString()).compareTo(new BigDecimal("500")),
            "重量 should remain 500 (no fieldsOf → no conversion) but was: " + weightVal);
    }
}
