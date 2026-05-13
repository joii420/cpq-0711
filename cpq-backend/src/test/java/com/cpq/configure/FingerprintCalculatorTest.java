package com.cpq.configure;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class FingerprintCalculatorTest {

    @Inject
    FingerprintCalculator calc;

    @Test
    void simpleFingerprint_isDeterministic_regardlessOfElementOrder() {
        var elems1 = List.of(
            new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0")),
            new FingerprintCalculator.ElementInput("Ni", new BigDecimal("10.0")));
        var elems2 = List.of(
            new FingerprintCalculator.ElementInput("Ni", new BigDecimal("10.0")),
            new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0")));
        assertEquals(
            calc.simpleFingerprint("AgNi90", elems1),
            calc.simpleFingerprint("AgNi90", elems2),
            "element order must not affect fingerprint");
    }

    @Test
    void simpleFingerprint_normalizesTrailingZeros() {
        var a = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        var b = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0")));
        var c = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.00")));
        String fpA = calc.simpleFingerprint("X", a);
        String fpB = calc.simpleFingerprint("X", b);
        String fpC = calc.simpleFingerprint("X", c);
        assertEquals(fpA, fpB);
        assertEquals(fpB, fpC);
    }

    @Test
    void simpleFingerprint_differentRecipe_differentHash() {
        var elems = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        assertNotEquals(
            calc.simpleFingerprint("AgNi90", elems),
            calc.simpleFingerprint("AgCu90", elems));
    }

    @Test
    void simpleFingerprint_differentPct_differentHash() {
        var a = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        var b = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("92")));
        assertNotEquals(
            calc.simpleFingerprint("AgNi90", a),
            calc.simpleFingerprint("AgNi90", b));
    }

    @Test
    void compositeFingerprint_orderIndependent() {
        assertEquals(
            calc.compositeFingerprint(List.of("CFG-AgCu-000001", "CFG-AgNi-000003")),
            calc.compositeFingerprint(List.of("CFG-AgNi-000003", "CFG-AgCu-000001")));
    }

    @Test
    void compositeFingerprint_differentChildren_differentHash() {
        assertNotEquals(
            calc.compositeFingerprint(List.of("A", "B")),
            calc.compositeFingerprint(List.of("A", "C")));
    }

    @Test
    void simpleFingerprint_isExactly64HexChars() {
        var elems = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        String fp = calc.simpleFingerprint("X", elems);
        assertEquals(64, fp.length());
        assert fp.matches("^[0-9a-f]{64}$");
    }

    @Test
    void simpleFingerprint_nullRecipeCode_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> calc.simpleFingerprint(null, List.of(new FingerprintCalculator.ElementInput("Ag", BigDecimal.TEN))));
    }

    @Test
    void compositeFingerprint_lessThan2Children_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> calc.compositeFingerprint(List.of("only-one")));
    }
}
