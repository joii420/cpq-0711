package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuotationServiceFormulaResultBoundaryTest {

    private static final Method MERGE_FORMULA_RESULTS = mergeFormulaResultsMethod();

    @Inject
    QuotationService quotationService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void preservesExistingStructuralNumbersAndExactLargeNumericWhileBigDecimalBecomesString()
            throws Throwable {
        String existing = "{\"rowIndex\":1,\"legacy\":98765431.123456789012}";

        JsonNode merged = objectMapper.readTree(merge(existing, Map.of(
            "calculated", new BigDecimal("6.250000000000"))));

        assertTrue(merged.path("rowIndex").isIntegralNumber(), "existing structural integer must remain numeric");
        assertEquals(1, merged.path("rowIndex").intValue());
        assertTrue(merged.path("legacy").isNumber(), "existing historical numeric token must remain numeric");
        assertFalse(merged.path("legacy").isDouble(), "existing large numeric must never pass through Double");
        assertEquals("98765431.123456789012",
            merged.path("legacy").decimalValue().toPlainString());
        assertTrue(merged.path("calculated").isTextual());
        assertEquals("6.25", merged.path("calculated").textValue());
    }

    @Test
    void integralFormulaNumberWrappersBecomeCanonicalDecimalStrings() throws Throwable {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("fromLong", 2L);
        results.put("fromBigInteger", new BigInteger("3"));

        JsonNode merged = objectMapper.readTree(merge("{\"rowIndex\":1}", results));

        assertAll(
            () -> assertTrue(merged.path("fromLong").isTextual(), "Long formula result must be string"),
            () -> assertEquals("2", merged.path("fromLong").textValue()),
            () -> assertTrue(merged.path("fromBigInteger").isTextual(),
                "BigInteger formula result must be string"),
            () -> assertEquals("3", merged.path("fromBigInteger").textValue()),
            () -> assertTrue(merged.path("rowIndex").isIntegralNumber(),
                "pre-existing structural integer must remain numeric"));
    }

    @Test
    void topLevelNumericJsonNodesBecomeCanonicalDecimalStrings() throws Throwable {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("fromDecimalNode", DecimalNode.valueOf(new BigDecimal("4.500000000000")));
        results.put("fromIntNode", IntNode.valueOf(5));

        JsonNode merged = objectMapper.readTree(merge("{}", results));

        assertAll(
            () -> assertTrue(merged.path("fromDecimalNode").isTextual(),
                "top-level DecimalNode formula result must be string"),
            () -> assertEquals("4.5", merged.path("fromDecimalNode").textValue()),
            () -> assertTrue(merged.path("fromIntNode").isTextual(),
                "top-level IntNode formula result must be string"),
            () -> assertEquals("5", merged.path("fromIntNode").textValue()));
    }

    @Test
    void rejectsFloatAndDoubleFormulaResults() {
        IllegalArgumentException doubleFailure = assertThrows(IllegalArgumentException.class,
            () -> merge("{}", Map.of("fromDouble", 1.25d)));
        IllegalArgumentException floatFailure = assertThrows(IllegalArgumentException.class,
            () -> merge("{}", Map.of("fromFloat", 1.25f)));

        assertTrue(doubleFailure.getMessage().contains("fromDouble"));
        assertTrue(floatFailure.getMessage().contains("fromFloat"));
    }

    @Test
    void rejectsContainersWithNestedNumericNodesWithoutFieldMetadata() {
        ObjectNode objectValue = objectMapper.createObjectNode();
        objectValue.put("amount", new BigDecimal("7.125000000000"));
        ArrayNode arrayValue = objectMapper.createArrayNode();
        arrayValue.add(8);

        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> merge("{}", Map.of("objectValue", objectValue)),
                "ObjectNode containing numeric children must not bypass the formula boundary"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> merge("{}", Map.of("arrayValue", arrayValue)),
                "ArrayNode containing numeric children must not bypass the formula boundary"));
    }

    private String merge(String existing, Map<String, Object> results) throws Throwable {
        try {
            Object target = quotationService instanceof ClientProxy
                ? ClientProxy.unwrap(quotationService)
                : quotationService;
            return (String) MERGE_FORMULA_RESULTS.invoke(target, existing, results);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method mergeFormulaResultsMethod() {
        try {
            Method method = QuotationService.class.getDeclaredMethod(
                "mergeFormulaResults", String.class, Map.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
