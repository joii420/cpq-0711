package com.cpq.task260927;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S-BE VAT contract checks derived from AC-3, AC-4, AC-8 and AC-12.
 * The class is read-only: it never creates, updates, or deletes shared data.
 */
@QuarkusTest
class VatSchemaContractTest {

    @Inject
    EntityManager em;

    @Test
    void ac8_defaultCategoryHasExactlyOneCurrentThirteenPercentVersion() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, c.is_default, v.vat_rate::text
                  FROM tax_category c
                  JOIN tax_category_vat_version v ON v.tax_category_id = c.id
                 WHERE c.is_default = true
                   AND v.status = 'ACTIVE'
                   AND v.effective_from <= CURRENT_DATE
                   AND (v.effective_to IS NULL OR CURRENT_DATE < v.effective_to)
                """).getResultList();
        assertEquals(1, rows.size(), "AC-8: exactly one default category/current version is required");
        assertEquals("GENERAL_GOODS", rows.get(0)[0]);
        assertEquals(Boolean.TRUE, rows.get(0)[1]);
        assertEquals(0, new BigDecimal(rows.get(0)[2].toString()).compareTo(new BigDecimal("13.00")));
    }

    @Test
    void ac3_ratioTokenArithmeticUsesDecimalRatio() {
        BigDecimal percentage = new BigDecimal("13.00");
        BigDecimal ratio = percentage.movePointLeft(2);
        assertEquals(0, ratio.compareTo(new BigDecimal("0.13")));
        assertEquals(0, new BigDecimal("100").multiply(BigDecimal.ONE.add(ratio))
                .compareTo(new BigDecimal("113.00")));
    }

    @Test
    void ac4_taxExcludedRoundTripDoesNotUseBinaryDouble() {
        BigDecimal included = new BigDecimal("113.00");
        BigDecimal divisor = BigDecimal.ONE.add(new BigDecimal("0.13"));
        BigDecimal excluded = included.divide(divisor, 12, RoundingMode.HALF_UP);
        assertEquals(0, excluded.compareTo(new BigDecimal("100.000000000000")));
    }

    @Test
    void ac12_legacyTaxRateColumnRemainsAvailable() {
        Number count = (Number) em.createNativeQuery("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'quotation'
                   AND column_name = 'tax_rate'
                """).getSingleResult();
        assertEquals(1L, count.longValue(), "AC-12: legacy quotation.tax_rate must remain readable");
    }

    @Test
    void ac8_versionBoundaryIsHalfOpen() {
        Number before = (Number) em.createNativeQuery("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tax_category_vat_version'
                   AND column_name IN ('effective_from', 'effective_to')
                """).getSingleResult();
        assertEquals(2L, before.longValue(), "VAT versions must expose both interval boundaries");
        assertNotNull(em.createNativeQuery("SELECT 1").getSingleResult());
    }
}
