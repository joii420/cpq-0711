package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotationDisplayPrecisionPolicyTest {

    @Test
    void mapsAuthenticatedRolesToQuoteOutputCaps() {
        assertEquals(4, QuotationDisplayPrecisionPolicy.maxDecimalsForRole("SALES_REP"));
        assertEquals(4, QuotationDisplayPrecisionPolicy.maxDecimalsForRole("SALES_MANAGER"));
        assertEquals(9, QuotationDisplayPrecisionPolicy.maxDecimalsForRole("PRICING_MANAGER"));
        assertEquals(9, QuotationDisplayPrecisionPolicy.maxDecimalsForRole("SYSTEM_ADMIN"));
    }

    @Test
    void unknownInternalRoleKeepsLegacyDisplayCap() {
        assertEquals(9, QuotationDisplayPrecisionPolicy.maxDecimalsForRole("UNKNOWN"));
        assertEquals(9, QuotationDisplayPrecisionPolicy.maxDecimalsForRole(null));
    }
}
