package com.cpq.engine.unit;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class UnitConversionTest {

    @Test
    void factorFor_allPresetUnits() {
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("克")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("g")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("G")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("千克")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("KG")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("kG")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("吨")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("t")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("片")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("pcs")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("KPCS")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("千片")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("g/PCS")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("G/pcs")));
    }

    @Test
    void factorFor_unknownOrBlank_returnsOne() {
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("mm")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor(null)));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("  ")));
    }

    @Test
    void factorFor_normalizesWhitespaceAndCase() {
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor(" g / PCS ")));
    }
}
