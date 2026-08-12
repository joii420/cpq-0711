package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndependentResultScaleProductionEntryTest {

    @Test
    void productSubtotalAndQuotationTotalProductionEntriesDoNotShareScale() {
        BigDecimal value = new BigDecimal("1.2345678905");
        assertEquals("1.2345679", CardSnapshotService.productCardSubtotalResult(value, 7).toPlainString());
        assertEquals("1.23456789", QuotationService.quotationTotalResult(value, 8).toPlainString());
        assertEquals("1.23456789", CardSnapshotService.productCardSubtotalResult(value, 8).toPlainString());
        assertEquals("1.2345679", QuotationService.quotationTotalResult(value, 7).toPlainString());
    }
}
