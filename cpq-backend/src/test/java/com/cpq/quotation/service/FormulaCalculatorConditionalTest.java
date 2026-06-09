package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Plan 3a：条件公式逐行选公式。纯 JUnit。 */
class FormulaCalculatorConditionalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    // 加工费：类型==车削 → 单价*1.2；类型==铣削 → 单价*1.5；默认 → 单价。
    private static final String FIELDS = "["
        + "{\"name\":\"类型\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.t\"},"
        + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.p\"},"
        + "{\"name\":\"加工费\",\"fieldType\":\"FORMULA\",\"conditional_formula\":{"
        + "  \"rules\":["
        + "    {\"when\":{\"kind\":\"leaf\",\"left\":\"类型\",\"op\":\"eq\",\"rhs\":{\"type\":\"literal\",\"value\":\"车削\"}},\"formula\":\"f_turn\"},"
        + "    {\"when\":{\"kind\":\"leaf\",\"left\":\"类型\",\"op\":\"eq\",\"rhs\":{\"type\":\"literal\",\"value\":\"铣削\"}},\"formula\":\"f_mill\"}"
        + "  ],\"default\":\"f_base\"}}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"f_turn\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"number\",\"value\":\"1.2\"}]},"
        + "{\"name\":\"f_mill\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"number\",\"value\":\"1.5\"}]},"
        + "{\"name\":\"f_base\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"}]}"
        + "]";
    private static final String RKF = "[\"k\"]";
    private static final String BASEROWS = "["
        + "{\"driverRow\":{\"k\":\"r0\"},\"basicDataValues\":{\"{v.t}\":\"车削\",\"{v.p}\":100}},"
        + "{\"driverRow\":{\"k\":\"r1\"},\"basicDataValues\":{\"{v.t}\":\"铣削\",\"{v.p}\":100}},"
        + "{\"driverRow\":{\"k\":\"r2\"},\"basicDataValues\":{\"{v.t}\":\"钻孔\",\"{v.p}\":100}}"
        + "]";

    @Test
    void conditionalPicksFormulaPerRow() {
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        java.util.Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        assertEquals(120.0, byKey.get("r0").path("values").path("加工费").asDouble(), 1e-9); // 车削 *1.2
        assertEquals(150.0, byKey.get("r1").path("values").path("加工费").asDouble(), 1e-9); // 铣削 *1.5
        assertEquals(100.0, byKey.get("r2").path("values").path("加工费").asDouble(), 1e-9); // 默认 *1
    }
}
