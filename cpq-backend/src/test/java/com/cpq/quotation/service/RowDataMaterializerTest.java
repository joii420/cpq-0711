package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task A1: verifies {@link RowDataMaterializer} computes FORMULA leaf columns
 * (写时算齐) into row_data, reusing the backend formula engine
 * {@link com.cpq.engine.formula.FormulaCalculationService}.
 */
@QuarkusTest
class RowDataMaterializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    RowDataMaterializer materializer;

    /**
     * ① snapshot rows carry 单价/用量 but lack the FORMULA leaf 材料成本
     *    → after materialize, each output row has 材料成本 = 单价 × 用量.
     */
    @Test
    void materializesFormulaLeafColumn() throws Exception {
        // 材料成本 = 单价 × 用量 (FORMULA leaf column)
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "code": "FEEDING",
                "columns": [
                    {"field_name": "单价", "field_type": "INPUT_NUMBER"},
                    {"field_name": "用量", "field_type": "INPUT_NUMBER"},
                    {"field_name": "材料成本", "field_type": "FORMULA",
                     "expression": [
                        {"type": "field", "value": "单价"},
                        {"type": "operator", "value": "×"},
                        {"type": "field", "value": "用量"}
                     ]}
                ]
            }]
            """);

        JsonNode snapshotRows = MAPPER.readTree("""
            [
                {"单价": 10, "用量": 2},
                {"单价": 5, "用量": 3}
            ]
            """);

        JsonNode out = materializer.materializeComponentRows(
                componentsSnapshot, "FEEDING", snapshotRows, null);

        assertTrue(out.isArray(), "output must be an array");
        assertEquals(2, out.size());

        // row 0: 10 × 2 = 20
        assertTrue(out.get(0).has("材料成本"));
        assertEquals(0, new java.math.BigDecimal("20").compareTo(
                out.get(0).get("材料成本").decimalValue()));
        // original driver columns preserved
        assertEquals(0, new java.math.BigDecimal("10").compareTo(
                out.get(0).get("单价").decimalValue()));

        // row 1: 5 × 3 = 15
        assertEquals(0, new java.math.BigDecimal("15").compareTo(
                out.get(1).get("材料成本").decimalValue()));
    }

    /** ② empty snapshot rows → empty array, no exception. */
    @Test
    void emptyRowsReturnsEmptyArray() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("[]");

        JsonNode emptyArr = MAPPER.readTree("[]");
        JsonNode out1 = materializer.materializeComponentRows(
                componentsSnapshot, "FEEDING", emptyArr, null);
        assertTrue(out1.isArray());
        assertEquals(0, out1.size());

        // null snapshotRows → empty array
        JsonNode out2 = materializer.materializeComponentRows(
                componentsSnapshot, "FEEDING", null, null);
        assertTrue(out2.isArray());
        assertEquals(0, out2.size());
    }

    /**
     * ③ cross-component subtotal: 产品小计 = component_subtotal(FEEDING) referencing
     *    a cross-component subtotal passed in (key convention code#col, matching
     *    ComponentDataEffectiveRows.SUBTOTAL_KEY_SEP).
     */
    @Test
    void usesCrossComponentSubtotal() throws Exception {
        JsonNode componentsSnapshot = MAPPER.readTree("""
            [{
                "code": "TOTAL",
                "columns": [
                    {"field_name": "合计", "field_type": "FORMULA",
                     "expression": [
                        {"type": "component_subtotal", "component_code": "FEEDING"},
                        {"type": "operator", "value": "+"},
                        {"type": "number", "value": "100"}
                     ]}
                ]
            }]
            """);

        JsonNode snapshotRows = MAPPER.readTree("[{}]");

        Map<String, Double> cross = new HashMap<>();
        cross.put("FEEDING", 500.0);

        JsonNode out = materializer.materializeComponentRows(
                componentsSnapshot, "TOTAL", snapshotRows, cross);

        assertEquals(1, out.size());
        assertEquals(0, new java.math.BigDecimal("600").compareTo(
                out.get(0).get("合计").decimalValue()));
    }
}
