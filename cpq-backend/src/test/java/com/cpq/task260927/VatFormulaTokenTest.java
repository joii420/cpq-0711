package com.cpq.task260927;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure formula-contract checks; no database or mutable fixture is required. */
class VatFormulaTokenTest {

    @Test
    void quotationVatTokenUsesSnapshotRatio() {
        ArrayNode tokens = JsonNodeFactory.instance.arrayNode();
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "number").put("value", "100"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "operator").put("value", "×"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "bracket_open"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "number").put("value", "1"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "operator").put("value", "+"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "quotation_field").put("value", "vat_rate"));
        tokens.add(JsonNodeFactory.instance.objectNode()
                .put("type", "bracket_close"));

        FormulaCalculator.RowContext context = new FormulaCalculator.RowContext();
        context.productAttributes.put("vat_rate", new BigDecimal("0.13"));

        assertEquals(0, new FormulaCalculator().evaluateExpression(tokens, context)
                .compareTo(new BigDecimal("113")));
    }
}
