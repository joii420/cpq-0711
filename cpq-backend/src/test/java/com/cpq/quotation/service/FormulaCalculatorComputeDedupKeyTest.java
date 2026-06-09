package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorComputeDedupKeyTest {

    private static final ObjectMapper M = new ObjectMapper();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test
    void driverColumnFromDriverRow() {
        JsonNode rkf = j("[\"child_no\",\"elem\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"child_no\":\"P1\",\"elem\":\"Cu\"}"), j("{}"));
        assertEquals("P1||Cu", k);
    }

    @Test
    void inputFieldFallsBackToRowValues() {
        JsonNode rkf = j("[\"child_no\",\"material\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"child_no\":\"P1\"}"), j("{\"material\":\"SUS304\"}"));
        assertEquals("P1||SUS304", k);
    }

    @Test
    void driverNonEmptyWinsOverRowValues() {
        JsonNode rkf = j("[\"elem\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"elem\":\"Cu\"}"), j("{\"elem\":\"Ni\"}"));
        assertEquals("Cu", k);
    }

    @Test
    void allBlankReturnsNull() {
        JsonNode rkf = j("[\"a\",\"b\"]");
        assertNull(FormulaCalculator.computeDedupKey(rkf, j("{}"), j("{}")));
    }

    @Test
    void emptyRowKeyFieldsReturnsNull() {
        assertNull(FormulaCalculator.computeDedupKey(j("[]"), j("{\"x\":1}"), j("{}")));
    }

    @Test
    void seqNoSentinelReturnsNull() {
        assertNull(FormulaCalculator.computeDedupKey(j("[\"__seq_no__\"]"), j("{}"), j("{}")));
    }

    @Test
    void partialKeyKept() {
        JsonNode rkf = j("[\"a\",\"b\"]");
        assertEquals("P1||", FormulaCalculator.computeDedupKey(rkf, j("{\"a\":\"P1\"}"), j("{}")));
    }
}
