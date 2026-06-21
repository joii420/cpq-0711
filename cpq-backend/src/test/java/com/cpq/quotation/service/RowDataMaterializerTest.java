package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task A1 (rewrite): verifies {@link RowDataMaterializer} computes FORMULA leaf columns
 * (写时算齐) into FLAT row_data using the REAL production engine {@link FormulaCalculator}
 * and the REAL production schema shapes:
 * <ul>
 *   <li>component snapshot tab = {@code {componentCode, componentType, fields:[{name, field_type, formula_name,...}],
 *       formulas:[{name, expression:[…tokens]}]}} — FORMULA fields reference {@code formulas[]} by {@code formula_name}
 *       (no inline expression).</li>
 *   <li>snapshot_rows = {@code [{driverRow:{…}, basicDataValues:{…}}]} (nested).</li>
 *   <li>output row_data = FLAT top-level {@code {fieldName:val,…, row_index:N}}.</li>
 * </ul>
 *
 * <p>Pure-function unit test ({@code new FormulaCalculator()} + {@code new RowDataMaterializer(fc)}),
 * no Quarkus container needed.
 */
class RowDataMaterializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RowDataMaterializer newMaterializer() {
        return new RowDataMaterializer(new FormulaCalculator());
    }

    /**
     * ① driverRow carries 单价/用量 (resolved into INPUT_NUMBER fields via driverRow),
     *    FORMULA leaf 材料成本 = 单价 × 用量 referenced via formula_name → formulas[].
     *    After materialize, each FLAT output row has the computed leaf column + driver columns + row_index.
     */
    @Test
    void materializesFormulaLeafColumnRealSchema() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "componentId": "11111111-1111-1111-1111-111111111111",
                "componentCode": "FEEDING",
                "componentType": "NORMAL",
                "tabName": "来料",
                "fields": [
                    {"name": "单价", "field_type": "INPUT_NUMBER"},
                    {"name": "用量", "field_type": "INPUT_NUMBER"},
                    {"name": "材料成本", "field_type": "FORMULA", "formula_name": "材料成本"}
                ],
                "formulas": [
                    {"name": "材料成本", "expression": [
                        {"type": "field", "value": "单价"},
                        {"type": "operator", "value": "×"},
                        {"type": "field", "value": "用量"}
                    ]}
                ]
            }]
            """);

        // snapshot_rows nested shape: driverRow carries the INPUT field values.
        JsonNode snapshotRows = MAPPER.readTree("""
            [
                {"driverRow": {"单价": 10, "用量": 2}, "basicDataValues": {}},
                {"driverRow": {"单价": 5,  "用量": 3}, "basicDataValues": {}}
            ]
            """);

        JsonNode out = newMaterializer().materializeComponentRows(
                componentsSnapshot, "FEEDING", snapshotRows, null);

        assertTrue(out.isArray(), "output must be an array");
        assertEquals(2, out.size());

        // FLAT top-level keys
        JsonNode r0 = out.get(0);
        assertTrue(r0.has("材料成本"), "FORMULA leaf must be present top-level");
        assertEquals(0, new java.math.BigDecimal("20").compareTo(r0.get("材料成本").decimalValue()),
                "材料成本 = 10 × 2 = 20");
        // driver columns preserved (flat)
        assertEquals(0, new java.math.BigDecimal("10").compareTo(r0.get("单价").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("2").compareTo(r0.get("用量").decimalValue()));
        // row_index injected
        assertEquals(0, r0.get("row_index").asInt());

        JsonNode r1 = out.get(1);
        assertEquals(0, new java.math.BigDecimal("15").compareTo(r1.get("材料成本").decimalValue()),
                "材料成本 = 5 × 3 = 15");
        assertEquals(1, r1.get("row_index").asInt());
    }

    /** ② empty / null snapshot rows → empty array, no exception. */
    @Test
    void emptyRowsReturnsEmptyArray() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("[]");

        JsonNode out1 = newMaterializer().materializeComponentRows(
                componentsSnapshot, "FEEDING", MAPPER.readTree("[]"), null);
        assertTrue(out1.isArray());
        assertEquals(0, out1.size());

        JsonNode out2 = newMaterializer().materializeComponentRows(
                componentsSnapshot, "FEEDING", null, null);
        assertTrue(out2.isArray());
        assertEquals(0, out2.size());
    }

    /**
     * ③ cross-component subtotal: a FORMULA referencing component_subtotal of another component's column,
     *    keyed with the {@code code#col} convention (matching
     *    {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} SUBTOTAL_KEY_SEP and
     *    {@link FormulaCalculator}'s {@code component_code + "#" + value} lookup).
     */
    @Test
    void usesCrossComponentSubtotalCodeColKey() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "componentId": "22222222-2222-2222-2222-222222222222",
                "componentCode": "MGMT",
                "componentType": "NORMAL",
                "tabName": "管理费",
                "fields": [
                    {"name": "合计", "field_type": "FORMULA", "formula_name": "合计"}
                ],
                "formulas": [
                    {"name": "合计", "expression": [
                        {"type": "component_subtotal", "component_code": "FEEDING", "value": "材料成本", "tab_name": "来料"},
                        {"type": "operator", "value": "+"},
                        {"type": "number", "value": "100"}
                    ]}
                ]
            }]
            """);

        // one base row (no driver fields needed; the formula is pure cross-subtotal)
        JsonNode snapshotRows = MAPPER.readTree("""
            [ {"driverRow": {}, "basicDataValues": {}} ]
            """);

        Map<String, Double> cross = new HashMap<>();
        cross.put("FEEDING#材料成本", 500.0); // code#col convention

        JsonNode out = newMaterializer().materializeComponentRows(
                componentsSnapshot, "MGMT", snapshotRows, cross);

        assertEquals(1, out.size());
        assertEquals(0, new java.math.BigDecimal("600").compareTo(out.get(0).get("合计").decimalValue()),
                "合计 = component_subtotal(FEEDING#材料成本=500) + 100 = 600");
    }

    /**
     * ④ cross_tab_ref overload: a FORMULA pulls a sibling component's row value via cross_tab_ref
     *    (KSUM over a source keyed by componentCode), proving the crossTabRows overload threads
     *    sibling resolved rows into the engine at materialize time.
     */
    @Test
    void usesCrossTabRowsOverload() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "componentId": "33333333-3333-3333-3333-333333333333",
                "componentCode": "TARGET",
                "componentType": "NORMAL",
                "tabName": "目标",
                "fields": [
                    {"name": "引用值", "field_type": "FORMULA", "formula_name": "引用值"}
                ],
                "formulas": [
                    {"name": "引用值", "expression": [
                        {"type": "cross_tab_ref", "agg": "SUM", "source": "SRC",
                         "match": [], "target": "金额"}
                    ]}
                ]
            }]
            """);

        JsonNode snapshotRows = MAPPER.readTree("""
            [ {"driverRow": {}, "basicDataValues": {}} ]
            """);

        // sibling SRC resolved rows: two rows of 金额, KSUM(match=[]) → 30 + 70 = 100
        List<Map<String, Object>> srcRows = new ArrayList<>();
        Map<String, Object> r0 = new LinkedHashMap<>(); r0.put("金额", 30); srcRows.add(r0);
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("金额", 70); srcRows.add(r1);
        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put("SRC", srcRows);

        JsonNode out = newMaterializer().materializeComponentRows(
                componentsSnapshot, "TARGET", snapshotRows, null, crossTabRows);

        assertEquals(1, out.size());
        assertEquals(0, new java.math.BigDecimal("100").compareTo(out.get(0).get("引用值").decimalValue()),
                "引用值 = KSUM over SRC.金额 = 30 + 70 = 100");
    }
}
