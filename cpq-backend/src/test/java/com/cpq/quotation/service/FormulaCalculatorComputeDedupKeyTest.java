package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorComputeDedupKeyTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
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

    // ======================================================================
    // 外购件场景：driverRow 键为 _前缀视图列，rowValues 里也无字段名 → 通过 fields 解析
    // ======================================================================

    @Test
    void fieldAware_resolvesThroughDefaultSource() {
        // 外购件：料件(INPUT_TEXT, default_source=$wgj_view._料件), 要素(INPUT_TEXT, default_source=$wgj_view._要素)
        JsonNode fields = j("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._料件\"}},"
            + "{\"name\":\"要素\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._要素\"}}"
            + "]");
        JsonNode rkf = j("[\"料件\",\"要素\"]");
        JsonNode driverRow = j("{\"_料件\":\"料9\",\"_要素\":\"加工费\"}");
        JsonNode basicDataValues = j("{\"{$wgj_view._料件}\":\"料9\",\"{$wgj_view._要素}\":\"加工费\"}");
        JsonNode rowValues = j("{}");

        // 5-arg 实例重载（传 fields + basicDataValues）：通过 defaultSource 解析出 料9||加工费
        String key = calc.computeDedupKey(rkf, fields, driverRow, basicDataValues, rowValues);
        assertEquals("料9||加工费", key,
            "通过 fields defaultSource 解析，应正确拼出 料9||加工费（不是 ||）");
    }

    @Test
    void fieldAware_allEmptyReturnsNull() {
        JsonNode fields = j("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._料件\"}}"
            + "]");
        JsonNode rkf = j("[\"料件\"]");
        // driverRow 和 basicDataValues 均无值
        String key = calc.computeDedupKey(rkf, fields, j("{}"), j("{}"), j("{}"));
        assertNull(key, "全部 key 字段为空时应返回 null");
    }
}
