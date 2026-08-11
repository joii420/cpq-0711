package com.cpq.costing.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ComparisonViewServicePrecisionTest {

    private final ComparisonViewService service = new ComparisonViewService();

    @Test
    void readsNewDecimalStringsAndHistoricalNumericTokensWithoutDouble() {
        ComparisonViewService.SideValues current = service.extractSide("""
                {"tabs":[{"componentId":"subtotal","componentType":"SUBTOTAL",
                "subtotal":"98765431.123456789012",
                "subtotalByColumn":{"amount":"0.333333333333"}}]}
                """);
        assertNotNull(current);
        assertEquals(new BigDecimal("98765431.123456789012"), current.productTotal);
        assertEquals(new BigDecimal("0.333333333333"),
                current.tabs.get("subtotal").subtotals.get("amount"));

        ComparisonViewService.SideValues historical = service.extractSide("""
                {"tabs":[{"componentId":"subtotal","componentType":"SUBTOTAL",
                "subtotal":98765431.123456789012,
                "subtotalByColumn":{"amount":0.333333333333}}]}
                """);
        assertNotNull(historical);
        assertEquals(new BigDecimal("98765431.123456789012"), historical.productTotal);
        assertEquals(new BigDecimal("0.333333333333"),
                historical.tabs.get("subtotal").subtotals.get("amount"));
    }
}
