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
 *
 * <p>Fixture fields:
 * <ul>
 *   <li>{@code name}         — test name (required)</li>
 *   <li>{@code token}        — single token object (legacy; used when {@code tokens} absent)</li>
 *   <li>{@code tokens}       — token array (optional; overrides {@code [token]})</li>
 *   <li>{@code aRows}        — rows for source "A" (legacy; used when {@code crossTabRows} absent)</li>
 *   <li>{@code crossTabRows} — map of source→rows (optional; overrides {@code {A: aRows}})</li>
 *   <li>{@code currentRow}   — current row values (optional)</li>
 *   <li>{@code expected}     — expected numeric result (required)</li>
 *   <li>{@code expectError}  — if true: assert result===0 only (error-path case)</li>
 * </ul>
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

            // Resolve tokens: explicit "tokens" array overrides single "token" wrapped in array
            final JsonNode tokens;
            if (c.get("tokens") != null) {
                tokens = om.valueToTree(c.get("tokens"));
            } else {
                JsonNode tokenNode = om.valueToTree(c.get("token"));
                tokens = om.createArrayNode().add(tokenNode);
            }

            // Parse currentRow
            @SuppressWarnings("unchecked")
            Map<String, Object> currentRow = (Map<String, Object>) c.get("currentRow");

            // Parse aRows (legacy)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> aRows = (List<Map<String, Object>>) c.get("aRows");

            // Parse crossTabRows: explicit map overrides {A: aRows}
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> crossTabRowsFixture =
                    (Map<String, List<Map<String, Object>>>) c.get("crossTabRows");

            // Parse optional context extras
            @SuppressWarnings("unchecked")
            Map<String, Object> quotationFieldsRaw =
                    (Map<String, Object>) c.get("quotationFields");
            @SuppressWarnings("unchecked")
            Map<String, Object> componentSubtotalsRaw =
                    (Map<String, Object>) c.get("componentSubtotals");

            // Parse expected (may be Integer or Double from Jackson)
            double expectedDouble = ((Number) c.get("expected")).doubleValue();

            // expectError: if true, assert result collapses to 0 (error-path)
            boolean expectError = Boolean.TRUE.equals(c.get("expectError"));

            tests.add(dynamicTest(name, () -> {
                FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
                // Populate currentRowRaw from fixture's currentRow map
                if (currentRow != null) {
                    ctx.currentRowRaw.putAll(currentRow);
                }
                // Resolve crossTabRows
                if (crossTabRowsFixture != null) {
                    ctx.crossTabRows = new HashMap<>(crossTabRowsFixture);
                } else {
                    Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
                    crossTabRows.put("A", aRows != null ? aRows : List.of());
                    ctx.crossTabRows = crossTabRows;
                }
                // Optional context extras
                if (quotationFieldsRaw != null) {
                    for (Map.Entry<String, Object> e : quotationFieldsRaw.entrySet()) {
                        if (e.getValue() instanceof Number n) ctx.quotationFields.put(e.getKey(), n.doubleValue());
                    }
                }
                if (componentSubtotalsRaw != null) {
                    for (Map.Entry<String, Object> e : componentSubtotalsRaw.entrySet()) {
                        if (e.getValue() instanceof Number n) ctx.componentSubtotals.put(e.getKey(), n.doubleValue());
                    }
                }

                BigDecimal result = calc.evaluateExpression(tokens, ctx);

                if (expectError) {
                    // Error-path: result must collapse to 0
                    assertEquals(0.0, result.doubleValue(), 1e-4,
                            "Case [" + name + "] expectError=true: expected 0.0 but got "
                                    + result.doubleValue());
                } else {
                    assertEquals(expectedDouble, result.doubleValue(), 1e-4,
                            "Case [" + name + "]: expected " + expectedDouble
                                    + " but got " + result.doubleValue());
                }
            }));
        }
        return tests;
    }
}
