package com.cpq.engine.unit;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
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

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode fieldsJson() throws Exception {
        return M.readTree("[" +
            "{\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"}," +
            "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\"}," +
            "{\"name\":\"数量\",\"field_type\":\"INPUT_NUMBER\"}]");
    }

    @Test
    void convertObjectRow_convertsConfiguredColumnByRowUnit() throws Exception {
        Map<String,Object> row = new HashMap<>();
        row.put("重量", "500"); row.put("单位", "g"); row.put("数量", 3);
        Map<String,Object> out = UnitConversion.convertObjectRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("0.5").compareTo(new BigDecimal(out.get("重量").toString())));
        assertEquals("g", out.get("单位"));
        assertEquals(3, ((Number) out.get("数量")).intValue());
        assertEquals("500", row.get("重量")); // 原 row 未被 mutate
    }

    @Test
    void convertObjectRow_unknownUnit_passthrough() throws Exception {
        Map<String,Object> row = new HashMap<>();
        row.put("重量", "500"); row.put("单位", "mm");
        Map<String,Object> out = UnitConversion.convertObjectRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("500").compareTo(new BigDecimal(out.get("重量").toString())));
    }

    @Test
    void convertNodeRow_convertsConfiguredColumn() throws Exception {
        Map<String,JsonNode> row = new HashMap<>();
        row.put("重量", new TextNode("2"));
        row.put("单位", new TextNode("吨"));
        Map<String,JsonNode> out = UnitConversion.convertNodeRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("2000").compareTo(out.get("重量").decimalValue()));
        assertEquals("吨", out.get("单位").asText());
    }
}
