package com.cpq.quotation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Shared fixture parity test — consumes the SAME JSON file as the frontend
 * vitest {@code formulaEngine.test.ts / cross-tab fixture} describe block.
 *
 * <p>Source of truth:
 * <ul>
 *   <li>Frontend:  {@code cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json}</li>
 *   <li>Backend copy: {@code cpq-backend/src/test/resources/cross-tab-cases.json}</li>
 * </ul>
 *
 * <p><b>IMPORTANT</b>: the two files must remain identical.  If you update the
 * frontend fixture, copy the updated file to the backend test resources as well
 * (and vice versa).  Any divergence between front and back engines must be
 * surfaced here, NOT hidden by silently adjusting expected values.
 */
class FormulaCalculatorCrossTabFixtureTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    @TestFactory
    Collection<DynamicTest> fixtureTests() throws Exception {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("cross-tab-cases.json");
        assertNotNull(is, "cross-tab-cases.json not found in test resources");

        List<Map<String, Object>> cases = om.readValue(is,
                new TypeReference<List<Map<String, Object>>>() {});
        assertNotNull(cases, "fixture must parse to a non-null list");

        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String name = (String) c.get("name");

            // Parse token → single-element JSON array (matches evaluateExpression contract)
            JsonNode tokenNode = om.valueToTree(c.get("token"));
            JsonNode tokens = om.createArrayNode().add(tokenNode);

            // Parse currentRow
            @SuppressWarnings("unchecked")
            Map<String, Object> currentRow = (Map<String, Object>) c.get("currentRow");

            // Parse aRows
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> aRows = (List<Map<String, Object>>) c.get("aRows");

            // Parse expected (may be Integer or Double from Jackson)
            double expectedDouble = ((Number) c.get("expected")).doubleValue();

            tests.add(dynamicTest(name, () -> {
                FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
                // Populate currentRowRaw from fixture's currentRow map
                if (currentRow != null) {
                    ctx.currentRowRaw.putAll(currentRow);
                }
                // crossTabRows: source "A" → aRows (as List<Map<String,Object>>)
                Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
                crossTabRows.put("A", aRows != null ? aRows : List.of());
                ctx.crossTabRows = crossTabRows;

                BigDecimal result = calc.evaluateExpression(tokens, ctx);

                assertEquals(expectedDouble, result.doubleValue(), 1e-4,
                        "Case [" + name + "]: expected " + expectedDouble
                                + " but got " + result.doubleValue());
            }));
        }
        return tests;
    }
}
