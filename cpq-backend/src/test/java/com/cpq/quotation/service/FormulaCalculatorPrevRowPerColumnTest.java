package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Plan 2b：previous_row_subtotal = 上一行本列值，多列各自独立累加。纯 JUnit。 */
class FormulaCalculatorPrevRowPerColumnTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    // 两个累加列：累计A = 上一行累计A + a；累计B = 上一行累计B + b。各自独立。
    private static final String FIELDS = "["
        + "{\"name\":\"a\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.a\"},"
        + "{\"name\":\"b\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.b\"},"
        + "{\"name\":\"累计A\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true},"
        + "{\"name\":\"累计B\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"累计A\",\"expression\":[{\"type\":\"previous_row_subtotal\"},"
        + "{\"type\":\"operator\",\"value\":\"+\"},{\"type\":\"field\",\"value\":\"a\"}]},"
        + "{\"name\":\"累计B\",\"expression\":[{\"type\":\"previous_row_subtotal\"},"
        + "{\"type\":\"operator\",\"value\":\"+\"},{\"type\":\"field\",\"value\":\"b\"}]}"
        + "]";
    private static final String RKF = "[\"k\"]";
    private static final String BASEROWS = "["
        + "{\"driverRow\":{\"k\":\"r0\"},\"basicDataValues\":{\"{v.a}\":10,\"{v.b}\":1}},"
        + "{\"driverRow\":{\"k\":\"r1\"},\"basicDataValues\":{\"{v.a}\":20,\"{v.b}\":2}},"
        + "{\"driverRow\":{\"k\":\"r2\"},\"basicDataValues\":{\"{v.a}\":30,\"{v.b}\":3}}"
        + "]";

    @Test
    void twoColumnsAccumulateIndependently() {
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        // 累计A: 10, 30, 60 ; 累计B: 1, 3, 6（各自累加，互不串）
        assertEquals(10.0, byKey.get("r0").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(30.0, byKey.get("r1").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(60.0, byKey.get("r2").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(1.0, byKey.get("r0").path("values").path("累计B").asDouble(), 1e-9);
        assertEquals(3.0, byKey.get("r1").path("values").path("累计B").asDouble(), 1e-9);
        assertEquals(6.0, byKey.get("r2").path("values").path("累计B").asDouble(), 1e-9);
    }

    @Test
    void threeRowsProduced() {
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertEquals(3, fr.size());
    }
}
