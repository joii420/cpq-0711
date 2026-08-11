package com.cpq.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecimalJacksonCustomizerTest {

    private final ObjectMapper mapper = DecimalJacksonCustomizer.newMapper();

    @Test
    void serializesBigDecimalAsPlainDecimalString() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(Map.of(
                "value", new BigDecimal("98765431.123456789012"),
                "integer", new BigDecimal("5.000000000000"))));
        assertEquals("98765431.123456789012", json.path("value").textValue());
        assertEquals("5", json.path("integer").textValue());
    }

    @Test
    void historicalNumericTokenIsReadDirectlyAsDecimalNode() throws Exception {
        JsonNode node = mapper.readTree("{\"value\":98765431.123456789012}").path("value");
        assertFalse(node.isDouble());
        assertEquals("98765431.123456789012", node.decimalValue().toPlainString());
    }

    @Test
    void precisionRequestFieldAcceptsStringAndRejectsJsonNumber() throws Exception {
        var accepted = mapper.readValue("{\"finalDiscountRate\":\"12.345678901234\"}",
                com.cpq.quotation.dto.SaveDraftRequest.class);
        assertEquals("12.345678901234", accepted.finalDiscountRate.toPlainString());
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"finalDiscountRate\":12.345678901234}",
                com.cpq.quotation.dto.SaveDraftRequest.class));
    }
}
