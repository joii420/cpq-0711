package com.cpq.engine;

import com.cpq.engine.formula.FormulaCalculationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormulaCalculationTest {

    @Inject
    FormulaCalculationService formulaService;

    @Test
    @Order(1)
    void simpleMultiplication() {
        // expression: field1 * field2
        String snapshot = """
            [{
                "code": "COMP1",
                "columns": [{
                    "field_name": "total",
                    "field_type": "FORMULA",
                    "expression": [
                        {"type": "field", "value": "quantity"},
                        {"type": "operator", "value": "×"},
                        {"type": "field", "value": "unit_price"}
                    ]
                }]
            }]
            """;

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("quantity", 10);
        rowData.put("unit_price", 25.5);

        Map<String, BigDecimal> results = formulaService.calculateRowFormulas(
                snapshot, "COMP1", rowData, null);

        assertTrue(results.containsKey("total"));
        assertEquals(0, new BigDecimal("255.0000").compareTo(results.get("total")));
    }

    @Test
    @Order(2)
    void expressionWithBrackets() {
        // expression: (field1 + field2) * field3
        String snapshot = """
            [{
                "code": "COMP2",
                "columns": [{
                    "field_name": "result",
                    "field_type": "FORMULA",
                    "expression": [
                        {"type": "bracket_open"},
                        {"type": "field", "value": "a"},
                        {"type": "operator", "value": "+"},
                        {"type": "field", "value": "b"},
                        {"type": "bracket_close"},
                        {"type": "operator", "value": "×"},
                        {"type": "field", "value": "c"}
                    ]
                }]
            }]
            """;

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("a", 10);
        rowData.put("b", 20);
        rowData.put("c", 3);

        Map<String, BigDecimal> results = formulaService.calculateRowFormulas(
                snapshot, "COMP2", rowData, null);

        assertTrue(results.containsKey("result"));
        assertEquals(0, new BigDecimal("90.0000").compareTo(results.get("result")));
    }

    @Test
    @Order(3)
    void crossComponentSubtotal_defaultsToZero() {
        // expression: component_subtotal(FEEDING) + number(100)
        String subtotalFormula = """
            [
                {"type": "component_subtotal", "component_code": "FEEDING"},
                {"type": "operator", "value": "+"},
                {"type": "component_subtotal", "component_code": "MISSING_COMPONENT"},
                {"type": "operator", "value": "+"},
                {"type": "number", "value": "100"}
            ]
            """;

        Map<String, BigDecimal> componentSubtotals = new HashMap<>();
        componentSubtotals.put("FEEDING", new BigDecimal("500"));
        // MISSING_COMPONENT not in map, should default to 0

        BigDecimal result = formulaService.calculateProductSubtotal(
                subtotalFormula, componentSubtotals, null);

        assertEquals(0, new BigDecimal("600.0000").compareTo(result));
    }

    @Test
    @Order(4)
    void toleranceCheck_withinRange() {
        assertTrue(formulaService.validateConsistency(
                new BigDecimal("100.005"), new BigDecimal("100.000")));
        assertTrue(formulaService.validateConsistency(
                new BigDecimal("99.995"), new BigDecimal("100.000")));
        assertFalse(formulaService.validateConsistency(
                new BigDecimal("100.02"), new BigDecimal("100.000")));
    }
}
