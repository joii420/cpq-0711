package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * ①b <b>editRows override (card-edit sync)</b>: the same FEEDING component, but with a per-row
     * editRows entry that overrides 单价. The new overload (accepting editRows + rowKeyFields) must
     * thread the edit into BOTH the flat INPUT value (单价 reflects the edited value, not the driver
     * value) AND the recomputed FORMULA leaf (材料成本 = edited 单价 × 用量).
     *
     * <p>rowKeyFields = ["料号"] so the editRows rowKey aligns by business key (AP-54), not row index.
     */
    @Test
    void editRowsOverrideFlowsIntoInputValueAndFormulaLeaf() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "componentId": "11111111-1111-1111-1111-111111111111",
                "componentCode": "FEEDING",
                "componentType": "NORMAL",
                "tabName": "来料",
                "fields": [
                    {"name": "料号", "field_type": "INPUT_TEXT"},
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

        // two driver rows keyed by 料号; driver 单价 = 10 / 5.
        JsonNode snapshotRows = MAPPER.readTree("""
            [
                {"driverRow": {"料号": "P-A", "单价": 10, "用量": 2}, "basicDataValues": {}},
                {"driverRow": {"料号": "P-B", "单价": 5,  "用量": 3}, "basicDataValues": {}}
            ]
            """);

        // rowKeyFields = ["料号"]; editRows rowKey = 料号 value (computeRowKey/uniquify口径).
        JsonNode rowKeyFields = MAPPER.readTree("[\"料号\"]");
        // user edits row P-A: 单价 10 → 99.
        JsonNode editRows = MAPPER.readTree("""
            [ {"rowKey": "P-A", "values": {"单价": 99}} ]
            """);

        JsonNode out = newMaterializer().materializeComponentRows(
                componentsSnapshot, "FEEDING", snapshotRows,
                editRows, rowKeyFields, Map.of(), Map.of(), null, null);

        assertEquals(2, out.size());

        // row P-A: INPUT 单价 reflects the edit (99), FORMULA 材料成本 recomputed = 99 × 2 = 198.
        JsonNode rA = out.get(0);
        assertEquals("P-A", rA.get("料号").asText());
        assertEquals(0, new java.math.BigDecimal("99").compareTo(rA.get("单价").decimalValue()),
                "INPUT 单价 must reflect the edited value (99), not driver 10");
        assertEquals(0, new java.math.BigDecimal("198").compareTo(rA.get("材料成本").decimalValue()),
                "FORMULA 材料成本 must recompute from edited 单价: 99 × 2 = 198");

        // row P-B: untouched → driver values.
        JsonNode rB = out.get(1);
        assertEquals(0, new java.math.BigDecimal("5").compareTo(rB.get("单价").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("15").compareTo(rB.get("材料成本").decimalValue()),
                "untouched row: 5 × 3 = 15");
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

    /**
     * ⑤ <b>Issue #1 regression — topo-ordered single pass</b>:
     * the chain B —(cross_tab_ref)→ A.调整, where A.调整 —(component_subtotal)→ C.基数.
     *
     * <p>If components are materialized in plain snapshot order (C absent → A absent), A computes 调整
     * with C's subtotal still 0, and B then pulls A's stale (pre-subtotal) value. The production fix
     * (see {@link com.cpq.configure.service.ConfigureSnapshotService}) orders components via
     * {@link CrossTabComponentOrder#topoOrder} over {@code cross_tab_ref} +
     * {@code component_subtotal} deps (mirroring
     * {@link CardSnapshotService#assembleTabsWithFormulaResults}), so by the time A is computed C's
     * subtotal is present, and by the time B is computed A's CORRECTED rows are in crossTabRows.
     *
     * <p>This test replays that exact single-pass loop (topoOrder → materialize → accumulate column
     * subtotals → thread crossTabRows) over the REAL {@link RowDataMaterializer} engine and asserts B
     * sees A's corrected value (210), not the pre-subtotal value (10).
     */
    @Test
    void singlePassTopoOrderResolvesCrossTabIntoSubtotalDependentColumn() throws Exception {
        // C(基数=200, INPUT) — provides component_subtotal CC#基数 = 200
        // A(调整 = component_subtotal(CC#基数) + 10) — corrected value 210; stale value (subtotal 0) = 10
        // B(引用 = cross_tab_ref SUM over AA.调整) — must see A's corrected 210
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [
              {
                "componentId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
                "componentCode": "CC", "componentType": "NORMAL", "tabName": "基数表",
                "fields": [ {"name": "基数", "field_type": "INPUT_NUMBER"} ],
                "formulas": []
              },
              {
                "componentId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "componentCode": "AA", "componentType": "NORMAL", "tabName": "调整表",
                "fields": [ {"name": "调整", "field_type": "FORMULA", "formula_name": "调整"} ],
                "formulas": [
                  {"name": "调整", "expression": [
                    {"type": "component_subtotal", "component_code": "CC", "value": "基数", "tab_name": "基数表"},
                    {"type": "operator", "value": "+"},
                    {"type": "number", "value": "10"}
                  ]}
                ]
              },
              {
                "componentId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "componentCode": "BB", "componentType": "NORMAL", "tabName": "引用表",
                "fields": [ {"name": "引用", "field_type": "FORMULA", "formula_name": "引用"} ],
                "formulas": [
                  {"name": "引用", "expression": [
                    {"type": "cross_tab_ref", "agg": "SUM", "source": "AA", "match": [], "target": "调整"}
                  ]}
                ]
              }
            ]
            """);

        Map<String, JsonNode> rowsByCode = new LinkedHashMap<>();
        rowsByCode.put("CC", MAPPER.readTree("[ {\"driverRow\": {\"基数\": 200}, \"basicDataValues\": {}} ]"));
        rowsByCode.put("AA", MAPPER.readTree("[ {\"driverRow\": {}, \"basicDataValues\": {}} ]"));
        rowsByCode.put("BB", MAPPER.readTree("[ {\"driverRow\": {}, \"basicDataValues\": {}} ]"));

        // ── topo order over cross_tab_ref + component_subtotal deps (same model as production) ──
        Map<String, String> refToCid = new HashMap<>();
        List<String> compCodes = new ArrayList<>();
        Map<String, JsonNode> tabByCode = new LinkedHashMap<>();
        for (JsonNode tab : componentsSnapshot) {
            String code = tab.path("componentCode").asText("");
            compCodes.add(code);
            tabByCode.put(code, tab);
            refToCid.put(code, code);
            refToCid.put(tab.path("tabName").asText(""), code);
        }
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (JsonNode tab : componentsSnapshot) {
            String code = tab.path("componentCode").asText("");
            Set<String> d = new LinkedHashSet<>(CrossTabComponentOrder.extractSourceRefs(tab.path("formulas")));
            for (String r : CrossTabComponentOrder.extractSubtotalRefs(tab.path("formulas"))) {
                String tc = refToCid.get(r);
                if (tc != null && !tc.equals(code)) d.add(tc);
            }
            deps.put(code, d);
        }
        List<String> order = CrossTabComponentOrder.topoOrder(compCodes, deps);
        // C and A must precede their referrers
        assertTrue(order.indexOf("CC") < order.indexOf("AA"), "CC (subtotal source) must precede AA");
        assertTrue(order.indexOf("AA") < order.indexOf("BB"), "AA (cross_tab source) must precede BB");

        // ── single pass: materialize in topo order, accumulate subtotals + thread crossTabRows ──
        RowDataMaterializer mat = newMaterializer();
        Map<String, Double> componentSubtotals = new HashMap<>();
        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        Map<String, ArrayNode> outByCode = new LinkedHashMap<>();
        for (String code : order) {
            JsonNode tab = tabByCode.get(code);
            ArrayNode flat = mat.materializeComponentRows(
                    componentsSnapshot, code, rowsByCode.get(code), componentSubtotals, crossTabRows);
            outByCode.put(code, flat);
            // thread sibling rows (componentCode key) for downstream cross_tab_ref
            List<Map<String, Object>> flatRows = new ArrayList<>();
            for (JsonNode r : flat) flatRows.add(MAPPER.convertValue(r, Map.class));
            crossTabRows.put(code, flatRows);
            // accumulate column subtotals (code#col convention) for downstream component_subtotal
            Map<String, Double> colSums = new LinkedHashMap<>();
            for (JsonNode r : flat) {
                if (r == null || !r.isObject()) continue;
                r.fields().forEachRemaining(en -> {
                    if (en.getValue() != null && en.getValue().isNumber())
                        colSums.merge(en.getKey(), en.getValue().doubleValue(), Double::sum);
                });
            }
            for (Map.Entry<String, Double> e : colSums.entrySet())
                componentSubtotals.put(code + "#" + e.getKey(), e.getValue());
        }

        // A's 调整 corrected = CC#基数(200) + 10 = 210 (NOT the pre-subtotal 10)
        assertEquals(0, new java.math.BigDecimal("210").compareTo(
                        outByCode.get("AA").get(0).get("调整").decimalValue()),
                "A.调整 must use C's subtotal (200) → 210, not pre-subtotal 10");

        // B's 引用 reads A's CORRECTED value via cross_tab_ref → 210 (the regression assertion)
        assertEquals(0, new java.math.BigDecimal("210").compareTo(
                        outByCode.get("BB").get(0).get("引用").decimalValue()),
                "B.引用 must see A's corrected 调整 (210), not the stale pre-subtotal value (10)");
    }
}
