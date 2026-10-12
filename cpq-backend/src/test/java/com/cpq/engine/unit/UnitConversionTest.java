package com.cpq.engine.unit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AC-2/AC-4~AC-6/AC-12 directed conversion contract. */
class UnitConversionTest {

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void quantityRelations_areDirectedAndCaseInsensitive() {
        assertDecimal("2", UnitConversion.convert(new BigDecimal("2000"), "pcs", "KPCS"));
        assertDecimal("2000", UnitConversion.convert(new BigDecimal("2"), "kpcs", "pcs"));
        assertDecimal("2", UnitConversion.convert(new BigDecimal("2"), "PCS", "pcs"));
        assertDecimal("2", UnitConversion.convert(new BigDecimal("2"), "ea", "EA"));
    }

    @Test
    void weightRelations_includeGramKilogramTonAndOunce() {
        assertDecimal("1", UnitConversion.convert(new BigDecimal("1000"), "g", "kg"));
        assertDecimal("1", UnitConversion.convert(new BigDecimal("1000"), "kg", "t"));
        assertDecimal("28.349523125", UnitConversion.convert(new BigDecimal("1"), "盎司", "g"));
        assertDecimal("0.03527396194958", UnitConversion.convert(new BigDecimal("1"), "g", "盎司"));
    }

    @Test
    void sameUnit_isImplicitIdentity() {
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "Pcs", "PCS"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "kg", "KG"));
    }

    @Test
    void crossCategory_unknownChineseAndCompound_passthrough() {
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "kg", "PCS"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "PCS", "kg"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "克", "kg"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "G/PCS", "kg"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "g", "G/PCS"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "mm", "kg"));
    }

    @Test
    void nullOrBlankUnits_areNonBlocking() {
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), null, "kg"));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "g", null));
        assertDecimal("12.5", UnitConversion.convert(new BigDecimal("12.5"), "", "kg"));
    }

    @Test
    void businessRowConversion_withoutTarget_isIdentity() throws Exception {
        var fields = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "[{\"name\":\"重量\",\"unit_source_field\":\"单位\"}]" );
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("重量", "1000");
        row.put("单位", "g");
        assertDecimal("1000", (BigDecimal) UnitConversion.convertObjectRow(fields, row).get("重量"));
    }
}
